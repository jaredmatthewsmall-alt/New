"""Live-sound analysis: turns spectra and levels into actionable recommendations.

Detectors
---------
* Feedback / ringing  - narrow, prominent peaks that persist (and often grow)
  over time. Suggests a notch filter (frequency, cut, Q) plus the nearest
  31-band graphic-EQ slider.
* Tonal balance       - long-term 1/3-octave spectrum compared with a target
  curve. Broad excesses/deficits become parametric EQ suggestions with a
  plain-English description (muddy, boxy, harsh, dull...).
* Low-frequency rumble - excess energy below ~40 Hz -> high-pass filter advice.
* Mains hum           - steady peaks at 50/60 Hz and harmonics -> grounding advice.
* Input level         - clipping, too little level, little headroom.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from .dsp import (
    THIRD_OCTAVE_EXACT,
    THIRD_OCTAVE_NOMINAL,
    ExpAverager,
    band_levels,
    db,
    find_narrow_peaks,
    format_hz,
    nearest_geq_band,
    note_name,
    q_from_octaves,
)

INFO, ADVISORY, WARNING, CRITICAL = 0, 1, 2, 3
SEVERITY_NAMES = {INFO: "Info", ADVISORY: "Advisory", WARNING: "Warning", CRITICAL: "Critical"}


@dataclass
class Recommendation:
    category: str          # Feedback, Tonal, Level, Noise
    severity: int
    title: str
    action: str            # what to do, short
    detail: str = ""       # why, longer
    freq: float | None = None
    gain_db: float | None = None
    q: float | None = None

    @property
    def severity_name(self) -> str:
        return SEVERITY_NAMES[self.severity]


# --------------------------------------------------------------------------
# Target curves (dB relative, as a function of frequency in Hz)
# --------------------------------------------------------------------------

def _flat(f):
    return np.zeros_like(np.asarray(f, dtype=float))


def _live_music(f):
    f = np.asarray(f, dtype=float)
    low = 6.0 * np.clip(np.log2(200.0 / f) / np.log2(200.0 / 50.0), 0, 1)
    high = -1.5 * np.maximum(0.0, np.log2(f / 1000.0))
    return low + high


def _speech(f):
    f = np.asarray(f, dtype=float)
    low = -6.0 * np.maximum(0.0, np.log2(120.0 / f))
    high = -1.0 * np.maximum(0.0, np.log2(f / 5000.0))
    return low + high


TARGET_CURVES = {
    "Live music (house curve)": _live_music,
    "Flat (pink-noise system tuning)": _flat,
    "Speech / spoken word": _speech,
}


# Plain-English descriptions for broad tonal problems.
def describe_excess(f: float) -> tuple[str, str]:
    if f < 40:
        return "Sub-bass rumble", "stage/HVAC rumble, handling noise or mic-stand vibration"
    if f < 100:
        return "Boomy low end", "too much kick/bass energy or room bass build-up"
    if f < 250:
        return "Woolly / boomy", "proximity effect, bass build-up, close-miked sources"
    if f < 500:
        return "Muddy", "low-mid build-up from many open mics and room reflections"
    if f < 1000:
        return "Boxy", "cardboard/boxy coloration, often from toms, guitars or vocals"
    if f < 2000:
        return "Honky / nasal", "nasal midrange, typical of horns and some vocal mics"
    if f < 5000:
        return "Harsh / piercing", "ear-fatiguing upper mids (cymbals, guitars, vocal edge)"
    if f < 9000:
        return "Sibilant / brittle", "esses, cymbals and brittle top end"
    return "Fizzy / hissy", "excess air or hiss"


def describe_deficit(f: float) -> tuple[str, str]:
    if f < 100:
        return "Thin low end", "the mix lacks weight; check subs and kick/bass levels"
    if f < 250:
        return "Lacking warmth", "low-mid body missing"
    if f < 2000:
        return "Hollow / scooped", "midrange is scooped; instruments may lack body"
    if f < 5000:
        return "Lacking presence", "vocals may sound distant or buried in the mix"
    return "Dull / lacking air", "top end is rolled off; mix sounds veiled"


# --------------------------------------------------------------------------
# Feedback tracking
# --------------------------------------------------------------------------

@dataclass
class _Candidate:
    freq: float
    first_seen: float
    last_seen: float
    hits: int = 0
    frames: int = 0
    history: list = field(default_factory=list)  # (t, level, prominence)
    width_hz: float = 0.0
    harmonic: bool = False
    harmonic_score: float = 0.0

    def duration(self) -> float:
        return self.last_seen - self.first_seen

    def slope_db_per_s(self, window: float = 2.0) -> float:
        h = [(t, lv) for t, lv, _ in self.history if t >= self.last_seen - window]
        if len(h) < 4:
            return 0.0
        t = np.array([p[0] for p in h])
        lv = np.array([p[1] for p in h])
        if np.ptp(t) < 0.3:
            return 0.0
        return float(np.polyfit(t - t[0], lv, 1)[0])

    def prominence(self) -> float:
        recent = [p for _, _, p in self.history[-10:]]
        return float(np.median(recent)) if recent else 0.0

    def level(self) -> float:
        return self.history[-1][1] if self.history else -120.0


class FeedbackTracker:
    """Tracks narrow peaks over time and decides which look like feedback.

    A peak is reported once it has been present in most frames for at least
    ``hold_s`` seconds. Peaks that are rising in level are escalated; peaks
    with a matching harmonic series (2f, 3f, f/2) are treated as probable
    musical notes and reported only if they persist much longer.
    """

    SENSITIVITY = {"Low": 18.0, "Medium": 14.0, "High": 10.0}

    def __init__(self, sensitivity: str = "Medium", hold_s: float = 1.0,
                 forget_s: float = 0.6, min_level: float = -75.0):
        self.min_prominence = self.SENSITIVITY.get(sensitivity, 14.0)
        self.hold_s = hold_s
        self.forget_s = forget_s
        self.min_level = min_level
        self.candidates: list[_Candidate] = []

    def set_sensitivity(self, name: str) -> None:
        self.min_prominence = self.SENSITIVITY.get(name, self.min_prominence)

    def reset(self) -> None:
        self.candidates.clear()

    def update(self, freqs: np.ndarray, spec_db: np.ndarray, t: float) -> None:
        # Weaker peaks are still useful as evidence of a harmonic series.
        all_peaks = find_narrow_peaks(freqs, spec_db, min_prominence=9.0,
                                      min_level=self.min_level - 15, fmin=40.0,
                                      fmax=20000.0, max_peaks=60)
        peaks = [p for p in all_peaks if p["prominence"] >= self.min_prominence
                 and p["level"] >= self.min_level and 60.0 <= p["freq"] <= 16000.0]
        df = freqs[1] - freqs[0]
        peak_freqs = [p["freq"] for p in all_peaks]
        for c in self.candidates:
            c.frames += 1
        for p in peaks:
            tol = max(2.5 * df, p["freq"] * 2 ** (1 / 24) - p["freq"])
            match = None
            for c in self.candidates:
                if abs(c.freq - p["freq"]) <= tol:
                    match = c
                    break
            if match is None:
                match = _Candidate(freq=p["freq"], first_seen=t, last_seen=t, frames=1)
                self.candidates.append(match)
            match.freq = 0.8 * match.freq + 0.2 * p["freq"]
            match.last_seen = t
            match.hits += 1
            match.width_hz = p["width_hz"]
            match.history.append((t, p["level"], p["prominence"]))
            if len(match.history) > 200:
                del match.history[:50]
            # Smoothed so a stray noise peak can't flip the verdict for one frame.
            hit = 1.0 if _has_harmonic_partner(p["freq"], peak_freqs, df) else 0.0
            match.harmonic_score = 0.8 * match.harmonic_score + 0.2 * hit
            match.harmonic = match.harmonic_score > 0.5
        self.candidates = [c for c in self.candidates if t - c.last_seen <= self.forget_s]

    def active(self, now: float) -> list[_Candidate]:
        out = []
        for c in self.candidates:
            if now - c.last_seen > 0.3:
                continue
            if c.duration() < self.hold_s:
                continue
            if c.hits / max(c.frames, 1) < 0.6:
                continue
            if c.harmonic and c.duration() < 4 * self.hold_s and c.slope_db_per_s() < 3.0:
                continue  # probably a sustained musical note
            out.append(c)
        return out


def _has_harmonic_partner(f: float, others: list[float], df: float) -> bool:
    for ratio in (2.0, 3.0, 0.5, 1 / 3):
        target = f * ratio
        tol = max(2 * df, target * 0.015)
        if any(abs(o - target) <= tol for o in others):
            return True
    return False


# --------------------------------------------------------------------------
# Main analyzer
# --------------------------------------------------------------------------

class LiveSoundAnalyzer:
    """Feed it raw FFT power frames and sample peaks; ask it for recommendations."""

    def __init__(self, sample_rate: int, fft_size: int, enbw: float = 1.5,
                 target: str = "Live music (house curve)", sensitivity: str = "Medium",
                 mains_hz: float | None = None):
        self.sample_rate = sample_rate
        self.fft_size = fft_size
        self.enbw = enbw
        self.freqs = np.fft.rfftfreq(fft_size, 1.0 / sample_rate)
        self.target_name = target
        self.mains_hz = mains_hz  # None = auto-detect 50 or 60
        self.fast = ExpAverager(0.15)    # feedback detection
        self.slow = ExpAverager(4.0)     # tonal balance / hum
        self.feedback = FeedbackTracker(sensitivity)
        self._peak_hist: list[tuple[float, float]] = []  # (t, peak_abs)
        self._rms_hist: list[tuple[float, float]] = []   # (t, rms dBFS)
        self._clip_until = -1.0
        self._t0: float | None = None
        self._last_t = 0.0
        self.band_db: np.ndarray | None = None
        self.band_dev: np.ndarray | None = None
        self.hum: dict | None = None

    # -- configuration ---------------------------------------------------
    def set_target(self, name: str) -> None:
        if name in TARGET_CURVES:
            self.target_name = name

    def target_curve(self, f) -> np.ndarray:
        return TARGET_CURVES[self.target_name](f)

    def reset(self) -> None:
        self.fast.reset()
        self.slow.reset()
        self.feedback.reset()
        self._peak_hist.clear()
        self._rms_hist.clear()
        self._t0 = None
        self.band_db = self.band_dev = self.hum = None

    # -- input -----------------------------------------------------------
    def update(self, power: np.ndarray, t: float, peak_abs: float | None = None,
               rms_dbfs: float | None = None) -> None:
        if self._t0 is None:
            self._t0 = t
        self._last_t = t
        fast = self.fast.update(power, t)
        slow = self.slow.update(power, t)
        self.feedback.update(self.freqs, db(fast), t)
        if peak_abs is not None:
            self._peak_hist.append((t, peak_abs))
            if peak_abs >= 0.989:  # about -0.1 dBFS
                self._clip_until = t + 3.0
        if rms_dbfs is not None:
            self._rms_hist.append((t, rms_dbfs))
        cutoff = t - 3.0
        self._peak_hist = [p for p in self._peak_hist if p[0] >= cutoff]
        self._rms_hist = [p for p in self._rms_hist if p[0] >= cutoff]
        self.band_db = band_levels(self.freqs, slow, self.enbw)
        self._compute_band_deviation()
        self.hum = self._detect_hum(db(slow))

    @property
    def elapsed(self) -> float:
        return 0.0 if self._t0 is None else self._last_t - self._t0

    # -- analysis helpers ------------------------------------------------
    def _compute_band_deviation(self) -> None:
        f = THIRD_OCTAVE_EXACT
        dev = self.band_db - self.target_curve(f)
        ref = (f >= 100) & (f <= 10000) & np.isfinite(dev)
        offset = float(np.median(dev[ref])) if ref.any() else 0.0
        dev = dev - offset
        # Light 3-band smoothing so single-band blips don't drive EQ moves.
        sm = dev.copy()
        sm[1:-1] = 0.25 * dev[:-2] + 0.5 * dev[1:-1] + 0.25 * dev[2:]
        self.band_dev = sm
        self.band_offset = offset

    def _detect_hum(self, spec_db: np.ndarray) -> dict | None:
        df = self.freqs[1] - self.freqs[0]
        best = None
        for base in ([self.mains_hz] if self.mains_hz else [50.0, 60.0]):
            proms = []
            for k in (1, 2, 3):
                fk = base * k
                i = int(round(fk / df))
                if i < 3 or i + 3 >= len(spec_db):
                    proms.append(0.0)
                    continue
                pk = spec_db[max(i - 1, 0):i + 2].max()
                lo = int(fk / 2 ** (1 / 6) / df)
                hi = int(math.ceil(fk * 2 ** (1 / 6) / df)) + 1
                lo, hi = min(lo, i - 6), max(hi, i + 7)
                around = np.concatenate([spec_db[max(lo, 0):i - 2], spec_db[i + 3:hi]])
                proms.append(float(pk - np.median(around)))
            score = proms[0] + 0.5 * (proms[1] + proms[2])
            found = proms[0] >= 15 or (proms[0] >= 8 and max(proms[1], proms[2]) >= 8)
            if found and (best is None or score > best["score"]):
                best = {"freq": base, "prominence": proms, "score": score}
        return best

    def level_stats(self) -> tuple[float, float]:
        """(peak dBFS over last 3 s, median RMS dBFS over last 3 s)."""
        pk = max((p for _, p in self._peak_hist), default=0.0)
        rms = float(np.median([r for _, r in self._rms_hist])) if self._rms_hist else -120.0
        return float(db(pk ** 2)), rms

    # -- recommendations -------------------------------------------------
    def recommendations(self) -> list[Recommendation]:
        recs: list[Recommendation] = []
        now = self._last_t
        recs += self._level_recs(now)
        recs += self._feedback_recs(now)
        recs += self._hum_recs()
        recs += self._tonal_recs()
        recs.sort(key=lambda r: (-r.severity, r.freq or 0))
        return recs

    def _level_recs(self, now: float) -> list[Recommendation]:
        recs = []
        peak_db, rms_db = self.level_stats()
        if now <= self._clip_until:
            recs.append(Recommendation(
                "Level", CRITICAL, "Input clipping",
                "Reduce the measurement input gain (preamp/trim) until peaks stay below -6 dBFS.",
                "The analyzer input is hitting full scale. Clipping adds false harmonics, so "
                "the spectrum and recommendations are unreliable until fixed."))
        elif peak_db > -3:
            recs.append(Recommendation(
                "Level", WARNING, "Very little headroom",
                "Lower the input gain a few dB; aim for peaks around -12 to -6 dBFS.",
                f"Peaks are reaching {peak_db:.1f} dBFS."))
        if self._rms_hist and rms_db < -60:
            recs.append(Recommendation(
                "Level", ADVISORY, "Input level very low",
                "Raise the input gain or move the measurement mic closer to the PA/listening area.",
                f"Average level is {rms_db:.0f} dBFS; tonal analysis needs roughly -50 dBFS "
                "or more to stay above the noise floor."))
        return recs

    def _feedback_recs(self, now: float) -> list[Recommendation]:
        recs = []
        hum_freqs = []
        if self.hum:
            hum_freqs = [self.hum["freq"] * k for k in range(1, 6)]
        for c in self.feedback.active(now):
            if any(abs(c.freq - h) < max(3.0, self.freqs[1] * 1.5) for h in hum_freqs):
                continue
            slope = c.slope_db_per_s()
            prom = c.prominence()
            rising = slope > 1.5
            if rising or prom >= 25:
                sev, title = CRITICAL, "Feedback"
            elif c.harmonic:
                sev, title = ADVISORY, "Sustained tone / possible ring"
            else:
                sev, title = WARNING, "Ringing / feedback risk"
            cut = float(np.clip(round(prom * 0.5 * 2) / 2, 3.0, 12.0))
            # Feedback is a near-pure tone; the FFT can't resolve its true width, so use
            # the narrow notch width typical of feedback suppressors (~1/7 octave).
            q = 10.0
            trend = f"rising {slope:+.1f} dB/s" if rising else (
                f"falling {slope:+.1f} dB/s" if slope < -1.5 else "steady")
            action = (f"Notch {format_hz(c.freq)}: cut {cut:.1f} dB, Q {q:.0f}  "
                      f"(31-band GEQ: pull {nearest_geq_band(c.freq)})")
            detail = (f"Narrow peak {prom:.0f} dB above its surroundings for {c.duration():.1f} s, "
                      f"{trend}. Nearest note {note_name(c.freq)}. Also consider lowering the "
                      "offending mic/monitor send, repositioning the mic or wedge, or reducing gain.")
            if c.harmonic:
                detail += (" It has a harmonic partner, so it may be a held musical note; "
                           "confirm by ear before cutting.")
            recs.append(Recommendation("Feedback", sev, title, action, detail,
                                       freq=c.freq, gain_db=-cut, q=q))
        return recs

    def _hum_recs(self) -> list[Recommendation]:
        if not self.hum:
            return []
        f = self.hum["freq"]
        p = self.hum["prominence"]
        return [Recommendation(
            "Noise", WARNING, f"Mains hum at {f:.0f} Hz",
            "Check for ground loops: use balanced cables, a DI ground-lift, keep audio away from "
            "power/lighting cables. As a stopgap, HPF or notch at "
            f"{f:.0f}/{2 * f:.0f}/{3 * f:.0f} Hz on the affected channel.",
            f"Steady peaks at {f:.0f} Hz (+{p[0]:.0f} dB) and harmonics "
            f"(+{p[1]:.0f}/+{p[2]:.0f} dB).",
            freq=f)]

    def _tonal_recs(self) -> list[Recommendation]:
        if self.band_dev is None or self.elapsed < 2.0:
            return []
        _, rms_db = self.level_stats()
        if self._rms_hist and rms_db < -60:
            return []
        f = THIRD_OCTAVE_EXACT
        dev = self.band_dev
        recs = []
        # Sub-bass rumble -> HPF.
        sub = (f >= 20) & (f <= 40)
        if np.nanmax(dev[sub]) > 6:
            recs.append(Recommendation(
                "Tonal", ADVISORY, "Low-frequency rumble",
                "Engage high-pass filters (80-100 Hz on vocals/guitars, 30-40 Hz on the main bus).",
                f"Energy below 40 Hz is {np.nanmax(dev[sub]):.0f} dB above the target. "
                "This wastes amplifier power and muddies the low end.",
                freq=40.0))
        # Broad regions from 50 Hz to 12.5 kHz.
        valid = (f >= 45) & (f <= 13000) & np.isfinite(dev)
        for sign, thresh in ((+1, 3.0), (-1, 4.0)):
            mask = valid & (sign * dev > thresh)
            for lo, hi in _runs(mask):
                region = slice(lo, hi + 1)
                k = lo + int(np.argmax(sign * dev[region]))
                amount = float(sign * dev[k])
                n_bands = hi - lo + 1
                bw_oct = max(n_bands / 3.0, 1 / 3)
                fc = float(np.exp(np.average(np.log(f[region]), weights=np.abs(dev[region]))))
                q = round(q_from_octaves(bw_oct), 1)
                if sign > 0:
                    gain = -min(round(amount * 0.7 * 2) / 2, 9.0)
                    name, why = describe_excess(fc)
                    sev = WARNING if amount >= 6 else ADVISORY
                    action = f"Cut {abs(gain):.1f} dB at {format_hz(fc)}, Q {q:.1f}"
                else:
                    gain = min(round(amount * 0.5 * 2) / 2, 4.0)
                    name, why = describe_deficit(fc)
                    sev = ADVISORY
                    action = (f"Boost {gain:.1f} dB at {format_hz(fc)}, Q {q:.1f} "
                              "(or cut the surrounding bands instead)")
                band_names = f"{_nom(lo)}-{_nom(hi)}"
                detail = (f"{band_names} is {abs(amount):.1f} dB "
                          f"{'above' if sign > 0 else 'below'} the '{self.target_name}' target: "
                          f"{why}.")
                if sign > 0 and fc < 500:
                    detail += " Also try HPFs on channels that don't need low end."
                if sign > 0 and self.hum and any(
                        f[lo] / 1.13 <= self.hum["freq"] * k <= f[hi] * 1.13 for k in (1, 2, 3)):
                    detail += " Part of this is the mains hum - fix that first and re-check."
                if sign < 0 and fc < 100:
                    detail += " Boosting lows costs a lot of amp power; check sub level/polarity first."
                recs.append(Recommendation("Tonal", sev, name, action, detail,
                                           freq=fc, gain_db=gain, q=q))
        return recs


def _runs(mask: np.ndarray) -> list[tuple[int, int]]:
    runs, start = [], None
    for i, m in enumerate(mask):
        if m and start is None:
            start = i
        elif not m and start is not None:
            runs.append((start, i - 1))
            start = None
    if start is not None:
        runs.append((start, len(mask) - 1))
    return runs


def _nom(i: int) -> str:
    return format_hz(THIRD_OCTAVE_NOMINAL[i])
