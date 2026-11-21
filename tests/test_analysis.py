import numpy as np
import pytest

from livesound.analysis import CRITICAL, LiveSoundAnalyzer
from livesound.audio import DemoSource, RingBuffer, pink_noise
from livesound.dsp import (
    THIRD_OCTAVE_EXACT,
    Spectrum,
    band_levels,
    db,
    find_narrow_peaks,
    nearest_geq_band,
    note_name,
    q_from_octaves,
)

SR = 48000
N = 8192
HOP = 1600  # ~30 analysis frames per second, like the GUI


def run(signal, target="Flat (pink-noise system tuning)", sensitivity="Medium"):
    """Feed a signal through the analyzer frame by frame, like the GUI does."""
    sp = Spectrum(SR, N)
    an = LiveSoundAnalyzer(SR, N, sp.enbw, target=target, sensitivity=sensitivity)
    t = 0.0
    for end in range(N, len(signal) + 1, HOP):
        block = signal[end - N:end]
        new = signal[end - HOP:end]
        t += HOP / SR
        an.update(sp.power(block), t, float(np.abs(new).max()),
                  float(db(np.mean(new.astype(np.float64) ** 2))))
    return an, an.recommendations()


def noise(seconds, level=0.05, bumps=(), seed=0):
    rng = np.random.default_rng(seed)
    return level * pink_noise(int(SR * seconds), rng, SR, bumps=list(bumps))


def tone(freq, seconds, amp):
    t = np.arange(int(SR * seconds)) / SR
    return amp * np.sin(2 * np.pi * freq * t)


# ---------------------------------------------------------------- DSP

def test_sine_reads_correct_dbfs_and_frequency():
    sp = Spectrum(SR, N)
    x = tone(1003.7, 0.5, 0.5)
    d = db(sp.power(x))
    i = int(np.argmax(d))
    assert abs(sp.freqs[i] - 1003.7) < sp.bin_hz
    assert d[i] == pytest.approx(-6.02, abs=1.5)  # Hann scalloping < 1.5 dB
    peaks = find_narrow_peaks(sp.freqs, d, min_level=-100)
    assert abs(peaks[0]["freq"] - 1003.7) < 1.0


def test_pink_noise_is_flat_in_third_octaves():
    sp = Spectrum(SR, N)
    x = noise(6)
    power = np.mean([sp.power(x[i:i + N]) for i in range(0, len(x) - N, N // 2)], axis=0)
    bands = band_levels(sp.freqs, power, sp.enbw)
    sel = (THIRD_OCTAVE_EXACT >= 50) & (THIRD_OCTAVE_EXACT <= 16000)
    assert np.ptp(bands[sel]) < 4.0


def test_helpers():
    assert note_name(440.0) == "A4 +0c"
    assert nearest_geq_band(2530) == "2.5 kHz"
    assert q_from_octaves(1.0) == pytest.approx(1.414, abs=0.01)


def test_ring_buffer_wraps():
    rb = RingBuffer(10)
    rb.write(np.arange(7))
    rb.write(np.arange(7, 15))
    data, total = rb.read_latest(10)
    assert total == 15
    assert list(data) == list(range(5, 15))


# ----------------------------------------------------------- analysis

def test_clean_pink_noise_gives_no_recommendations():
    _, recs = run(noise(6))
    assert recs == [], [r.title for r in recs]


def test_growing_feedback_is_detected_with_notch_advice():
    sig = noise(6)
    env = 0.003 * 10 ** (np.arange(len(sig)) / SR * 0.3)  # +6 dB per second
    sig = sig + env * np.sin(2 * np.pi * 3150.0 * np.arange(len(sig)) / SR)
    _, recs = run(sig)
    fb = [r for r in recs if r.category == "Feedback"]
    assert len(fb) == 1
    assert fb[0].severity == CRITICAL
    assert abs(fb[0].freq - 3150) < 5
    assert fb[0].gain_db < 0 and fb[0].q >= 6
    assert "3.15 kHz" in fb[0].action


def test_short_musical_note_with_harmonics_is_not_feedback():
    t = np.arange(int(SR * 2.5)) / SR
    note = sum(0.02 / k * np.sin(2 * np.pi * 440 * k * t) for k in (1, 2, 3, 4))
    sig = noise(2.5) + note
    _, recs = run(sig)
    assert not [r for r in recs if r.category == "Feedback"]


def test_low_mid_buildup_suggests_cut_and_is_called_muddy():
    _, recs = run(noise(6, bumps=[(400, 8, 1.0)]))
    tonal = [r for r in recs if r.category == "Tonal"]
    assert tonal, "expected a tonal recommendation"
    r = tonal[0]
    assert r.title == "Muddy"
    assert 300 < r.freq < 520
    assert r.gain_db < 0


def test_missing_presence_suggests_boost():
    _, recs = run(noise(6, bumps=[(3000, -8, 1.2)]))
    r = [r for r in recs if r.title == "Lacking presence"]
    assert r and r[0].gain_db > 0


def test_mains_hum_detected_and_not_reported_as_feedback():
    t = np.arange(SR * 6) / SR
    hum = 0.05 * np.sin(2 * np.pi * 50 * t) + 0.025 * np.sin(2 * np.pi * 100 * t)
    an, recs = run(noise(6) + hum)
    assert an.hum and an.hum["freq"] == 50
    assert any(r.title == "Mains hum at 50 Hz" for r in recs)
    assert not any(r.category == "Feedback" and abs(r.freq - 100) < 10 for r in recs)


def test_clipping_and_low_level():
    loud = np.clip(noise(3, level=0.8), -1, 1)
    _, recs = run(loud)
    assert recs[0].title == "Input clipping" and recs[0].severity == CRITICAL
    _, recs = run(noise(3, level=0.0003))
    assert any(r.title == "Input level very low" for r in recs)


def test_demo_source_exercises_every_detector():
    src = DemoSource(SR)
    sig = src.generate(SR * 6)
    an, recs = run(sig, target="Live music (house curve)")
    titles = {r.title for r in recs}
    assert "Feedback" in titles
    assert "Mains hum at 60 Hz" in titles
    assert any(r.category == "Tonal" and r.gain_db < 0 and 150 < r.freq < 350 for r in recs)
