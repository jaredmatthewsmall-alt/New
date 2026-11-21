"""Spectrum analysis primitives: FFT, averaging, 1/3-octave bands, peak finding."""

from __future__ import annotations

import math

import numpy as np

EPS = 1e-20

# ISO 266 nominal 1/3-octave centre frequencies (20 Hz .. 20 kHz) -- the same
# bands found on a 31-band graphic EQ.
THIRD_OCTAVE_NOMINAL = [
    20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630,
    800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000,
    12500, 16000, 20000,
]
THIRD_OCTAVE_EXACT = np.array([1000.0 * 2 ** (n / 3) for n in range(-17, 14)])

NOTE_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def db(power: np.ndarray | float) -> np.ndarray | float:
    return 10.0 * np.log10(np.asarray(power) + EPS)


def format_hz(f: float) -> str:
    if f >= 1000:
        s = f"{f / 1000:.2f}".rstrip("0").rstrip(".")
        return f"{s} kHz"
    return f"{f:.0f} Hz"


def nearest_geq_band(f: float) -> str:
    """Nearest 31-band graphic-EQ slider label for frequency ``f``."""
    i = int(np.argmin(np.abs(np.log2(THIRD_OCTAVE_EXACT / f))))
    return format_hz(THIRD_OCTAVE_NOMINAL[i])


def note_name(f: float) -> str:
    """Nearest musical note, e.g. 'D#7 +12c' (A4 = 440 Hz)."""
    if f <= 0:
        return ""
    midi = 69 + 12 * math.log2(f / 440.0)
    n = int(round(midi))
    cents = int(round((midi - n) * 100))
    return f"{NOTE_NAMES[n % 12]}{n // 12 - 1} {cents:+d}c"


def q_from_octaves(bw_oct: float) -> float:
    """Convert a bandwidth in octaves to a parametric-EQ Q value."""
    bw_oct = max(bw_oct, 1e-3)
    return math.sqrt(2 ** bw_oct) / (2 ** bw_oct - 1)


class Spectrum:
    """Windowed FFT with time-constant based exponential averaging.

    Levels are in dBFS: a full-scale sine reads 0 dB at its frequency.
    """

    def __init__(self, sample_rate: int, fft_size: int):
        self.sample_rate = int(sample_rate)
        self.fft_size = int(fft_size)
        self.window = np.hanning(self.fft_size).astype(np.float64)
        self._scale = 2.0 / self.window.sum()
        self.freqs = np.fft.rfftfreq(self.fft_size, 1.0 / self.sample_rate)
        self.bin_hz = self.sample_rate / self.fft_size
        # Equivalent noise bandwidth of the Hann window, in bins.
        self.enbw = float(self.fft_size * np.sum(self.window ** 2) / self.window.sum() ** 2)

    def power(self, block: np.ndarray) -> np.ndarray:
        """Linear power spectrum (amplitude^2 of sine components) of one block."""
        x = np.asarray(block, dtype=np.float64)
        if len(x) < self.fft_size:
            x = np.pad(x, (self.fft_size - len(x), 0))
        x = x[-self.fft_size:]
        x = x - x.mean()
        mag = np.abs(np.fft.rfft(x * self.window)) * self._scale
        return mag ** 2


class ExpAverager:
    """Exponential averager of linear power with a time constant in seconds."""

    def __init__(self, tau: float):
        self.tau = float(tau)
        self.value: np.ndarray | None = None
        self._last_t: float | None = None

    def reset(self) -> None:
        self.value = None
        self._last_t = None

    def update(self, x: np.ndarray, t: float) -> np.ndarray:
        if self.value is None or self.value.shape != x.shape or self.tau <= 0:
            self.value = x.copy()
        else:
            dt = max(t - (self._last_t if self._last_t is not None else t), 1e-3)
            a = math.exp(-dt / self.tau)
            self.value = a * self.value + (1 - a) * x
        self._last_t = t
        return self.value


class PeakHold:
    """Peak-hold trace (dB) that decays at ``decay_db_per_s`` after ``hold_s``."""

    def __init__(self, hold_s: float = 2.0, decay_db_per_s: float = 10.0):
        self.hold_s = hold_s
        self.decay = decay_db_per_s
        self.value: np.ndarray | None = None
        self._age: np.ndarray | None = None
        self._last_t: float | None = None

    def reset(self) -> None:
        self.value = None

    def update(self, x_db: np.ndarray, t: float) -> np.ndarray:
        if self.value is None or self.value.shape != x_db.shape:
            self.value = x_db.copy()
            self._age = np.zeros_like(x_db)
            self._last_t = t
            return self.value
        dt = max(t - self._last_t, 0.0)
        self._last_t = t
        self._age += dt
        decaying = self._age > self.hold_s
        self.value[decaying] -= self.decay * dt
        higher = x_db >= self.value
        self.value[higher] = x_db[higher]
        self._age[higher] = 0.0
        return self.value


def band_levels(freqs: np.ndarray, power: np.ndarray, enbw: float = 1.5,
                centers: np.ndarray = THIRD_OCTAVE_EXACT, fraction: int = 3) -> np.ndarray:
    """Fractional-octave band levels (dB) from a linear FFT power spectrum.

    Integrates power density across each band. Low bands narrower than an FFT
    bin are handled by interpolating the density, so every band gets a value.
    """
    df = freqs[1] - freqs[0]
    half = 2 ** (1 / (2 * fraction))
    out = np.empty(len(centers))
    for i, fc in enumerate(centers):
        lo, hi = fc / half, min(fc * half, freqs[-1])
        if lo >= freqs[-1]:
            out[i] = np.nan
            continue
        pts = np.geomspace(lo, hi, 16)
        dens = np.interp(pts, freqs, power).mean()  # power per bin
        out[i] = db(dens * (hi - lo) / df / enbw)
    return out


def find_narrow_peaks(freqs: np.ndarray, spec_db: np.ndarray, min_prominence: float = 12.0,
                      min_level: float = -80.0, fmin: float = 40.0, fmax: float = 16000.0,
                      max_peaks: int = 20) -> list[dict]:
    """Find narrow, prominent spectral peaks (feedback / ringing / hum candidates).

    Returns dicts with keys: freq (parabolic-interpolated Hz), level (dB),
    prominence (dB above the surrounding 1/3-octave median), width_hz (-6 dB width).
    """
    df = freqs[1] - freqs[0]
    i0 = max(int(np.searchsorted(freqs, fmin)), 2)
    i1 = min(int(np.searchsorted(freqs, fmax)), len(freqs) - 3)
    s = spec_db
    seg = s[i0:i1]
    is_max = (seg > s[i0 - 1:i1 - 1]) & (seg >= s[i0 + 1:i1 + 1]) & (seg > min_level)
    # Cheap pre-screen: a narrow peak must already stand well above the bins
    # 6 away on both sides (the Hann main lobe is only +-2 bins wide).
    j = np.arange(i0, i1)
    left = s[np.maximum(j - 6, 0)]
    right = s[np.minimum(j + 6, len(s) - 1)]
    is_max &= (seg - np.maximum(left, right)) >= 0.5 * min_prominence
    cand = np.nonzero(is_max)[0] + i0
    if len(cand) == 0:
        return []
    # Only evaluate the strongest remaining maxima to keep this cheap.
    cand = cand[np.argsort(s[cand])[::-1][:60]]
    half = 2 ** (1 / 6)
    peaks = []
    for i in cand:
        f = freqs[i]
        lo = max(int(np.searchsorted(freqs, f / half)), 0)
        hi = min(int(np.searchsorted(freqs, f * half)), len(freqs))
        lo, hi = min(lo, i - 8), max(hi, i + 9)
        lo, hi = max(lo, 0), min(hi, len(freqs))
        around = np.concatenate([s[lo:max(lo, i - 3)], s[min(hi, i + 4):hi]])
        if len(around) == 0:
            continue
        prom = s[i] - float(np.median(around))
        if prom < min_prominence:
            continue
        # -6 dB width by walking outwards.
        thr = s[i] - 6.0
        a = i
        while a > 0 and s[a] > thr and i - a < 64:
            a -= 1
        b = i
        while b < len(s) - 1 and s[b] > thr and b - i < 64:
            b += 1
        width_hz = (b - a) * df
        # Narrow = within a few bins (window main lobe) or under ~1/12 octave.
        if width_hz > max(5 * df, f * 0.06):
            continue
        # Parabolic interpolation on the dB values for sub-bin accuracy.
        y0, y1, y2 = s[i - 1], s[i], s[i + 1]
        denom = y0 - 2 * y1 + y2
        delta = 0.5 * (y0 - y2) / denom if denom != 0 else 0.0
        delta = float(np.clip(delta, -0.5, 0.5))
        peaks.append({
            "freq": float(f + delta * df),
            "level": float(y1 - 0.25 * (y0 - y2) * delta),
            "prominence": float(prom),
            "width_hz": float(width_hz),
        })
    peaks.sort(key=lambda p: p["prominence"], reverse=True)
    return peaks[:max_peaks]
