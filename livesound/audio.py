"""Audio input sources.

``MicSource`` captures from a sound-card input (microphone, mixer feed, USB
interface) using PortAudio via the ``sounddevice`` package. On Windows this
works with MME, DirectSound, WASAPI and (if PortAudio was built with it) ASIO
devices.

``DemoSource`` generates a synthetic "live room" signal (pink noise with a
muddy low-mid bump, mains hum, and a slowly building feedback tone) so the
analyzer can be explored without a microphone.

Both sources write mono float32 samples into a ``RingBuffer`` which the GUI
reads from on a timer.
"""

from __future__ import annotations

import threading
import time

import numpy as np

try:  # sounddevice needs the PortAudio DLL, which the Windows wheel bundles.
    import sounddevice as sd

    SD_IMPORT_ERROR: Exception | None = None
except Exception as exc:  # pragma: no cover - depends on the host system
    sd = None
    SD_IMPORT_ERROR = exc


class RingBuffer:
    """Thread-safe circular buffer of mono float32 samples."""

    def __init__(self, size: int):
        self.size = int(size)
        self._buf = np.zeros(self.size, dtype=np.float32)
        self._pos = 0
        self._total = 0
        self._lock = threading.Lock()

    def write(self, x: np.ndarray) -> None:
        x = np.asarray(x, dtype=np.float32).ravel()
        n = len(x)
        if n == 0:
            return
        with self._lock:
            if n >= self.size:
                self._buf[:] = x[-self.size:]
                self._pos = 0
            else:
                end = self._pos + n
                if end <= self.size:
                    self._buf[self._pos:end] = x
                else:
                    k = self.size - self._pos
                    self._buf[self._pos:] = x[:k]
                    self._buf[: n - k] = x[k:]
                self._pos = end % self.size
            self._total += n

    def read_latest(self, n: int) -> tuple[np.ndarray, int]:
        """Return the newest ``n`` samples (oldest first) and the running sample count."""
        n = min(int(n), self.size)
        with self._lock:
            idx = (self._pos - n + np.arange(n)) % self.size
            return self._buf[idx].copy(), self._total

    @property
    def total(self) -> int:
        with self._lock:
            return self._total


class AudioSource:
    """Common interface for input sources."""

    def __init__(self, sample_rate: int, buffer_seconds: float = 4.0):
        self.sample_rate = int(sample_rate)
        self.buffer = RingBuffer(int(sample_rate * buffer_seconds))
        self._peak = 0.0
        self._peak_lock = threading.Lock()
        self.overflows = 0
        self.running = False

    def _push(self, mono: np.ndarray) -> None:
        self.buffer.write(mono)
        if len(mono):
            p = float(np.max(np.abs(mono)))
            with self._peak_lock:
                self._peak = max(self._peak, p)

    def pop_peak(self) -> float:
        """Absolute sample peak since the last call (so short clips are never missed)."""
        with self._peak_lock:
            p, self._peak = self._peak, 0.0
        return p

    def start(self) -> None:  # pragma: no cover - interface
        raise NotImplementedError

    def stop(self) -> None:  # pragma: no cover - interface
        raise NotImplementedError


def list_input_devices() -> list[dict]:
    """Input-capable devices as dicts: index, name, hostapi, channels, default_samplerate."""
    if sd is None:
        return []
    hostapis = sd.query_hostapis()
    out = []
    for i, d in enumerate(sd.query_devices()):
        if d["max_input_channels"] > 0:
            out.append(
                {
                    "index": i,
                    "name": d["name"],
                    "hostapi": hostapis[d["hostapi"]]["name"],
                    "channels": int(d["max_input_channels"]),
                    "default_samplerate": float(d["default_samplerate"]),
                }
            )
    return out


def default_input_device() -> int | None:
    if sd is None:
        return None
    try:
        dev = sd.default.device[0]
        return int(dev) if dev is not None and dev >= 0 else None
    except Exception:
        return None


class MicSource(AudioSource):
    """Live capture from a sound-card input.

    ``channel`` selects which input channel to analyse (0-based), or ``None``
    to average all channels to mono.
    """

    def __init__(self, device: int | None, sample_rate: int | None = None,
                 channel: int | None = 0, blocksize: int = 512):
        if sd is None:
            raise RuntimeError(f"Audio input unavailable: {SD_IMPORT_ERROR}")
        info = sd.query_devices(device, "input")
        if sample_rate is None:
            sample_rate = int(info["default_samplerate"])
        super().__init__(sample_rate)
        self.device = device
        self.device_name = info["name"]
        self.n_channels = int(info["max_input_channels"]) if channel is None else max(1, channel + 1)
        self.n_channels = min(self.n_channels, int(info["max_input_channels"]))
        self.channel = channel
        self.blocksize = blocksize
        self._stream = None

    def _callback(self, indata, frames, time_info, status):  # runs on the audio thread
        if status.input_overflow:
            self.overflows += 1
        if self.channel is None:
            mono = indata.mean(axis=1)
        else:
            mono = indata[:, min(self.channel, indata.shape[1] - 1)]
        self._push(mono)

    def start(self) -> None:
        self._stream = sd.InputStream(
            device=self.device,
            channels=self.n_channels,
            samplerate=self.sample_rate,
            blocksize=self.blocksize,
            dtype="float32",
            callback=self._callback,
        )
        self._stream.start()
        self.running = True

    def stop(self) -> None:
        if self._stream is not None:
            try:
                self._stream.stop()
                self._stream.close()
            finally:
                self._stream = None
        self.running = False


def pink_noise(n: int, rng: np.random.Generator, sample_rate: int,
               bumps: list[tuple[float, float, float]] = (),
               tilt=None) -> np.ndarray:
    """Pink noise (equal energy per octave) shaped in the frequency domain.

    ``bumps`` is a list of (centre_hz, gain_db, width_octaves) bell shapes to
    apply, e.g. to simulate a muddy room.
    """
    white = rng.standard_normal(n)
    spec = np.fft.rfft(white)
    f = np.fft.rfftfreq(n, 1 / sample_rate)
    f[0] = f[1]
    shape = 1 / np.sqrt(f)
    for fc, gain_db, width in bumps:
        octs = np.log2(f / fc)
        shape *= 10 ** (gain_db * np.exp(-0.5 * (octs / (width / 2.355)) ** 2) / 20)
    if tilt is not None:
        shape *= 10 ** (tilt(f) / 20)
    shape[f < 15] *= (f[f < 15] / 15) ** 2  # remove DC/subsonic content
    out = np.fft.irfft(spec * shape, n)
    return (out / np.sqrt(np.mean(out ** 2))).astype(np.float32)


class DemoSource(AudioSource):
    """Synthetic live-room signal for trying the analyzer without a mic.

    - pink noise (shaped to the live-music house curve) at about -24 dBFS RMS with a +7 dB bump at 250 Hz (mud) and a
      -6 dB dip around 3 kHz (missing presence)
    - mains hum at ``hum_hz`` plus harmonics
    - a feedback tone at ``feedback_hz`` that builds up for 8 s, then is
      "pulled down" for 6 s, repeating
    """

    def __init__(self, sample_rate: int = 48000, feedback_hz: float = 2530.0,
                 hum_hz: float = 60.0, seed: int = 1):
        super().__init__(sample_rate)
        self.device_name = "Demo signal"
        from .analysis import TARGET_CURVES  # demo follows the house curve

        rng = np.random.default_rng(seed)
        self._noise = 0.06 * pink_noise(sample_rate * 8, rng, sample_rate,
                                        bumps=[(250, 7, 1.0), (3000, -6, 1.2)],
                                        tilt=TARGET_CURVES["Live music (house curve)"])
        self._noise_pos = 0
        self.feedback_hz = feedback_hz
        self.hum_hz = hum_hz
        self._n = 0
        self._thread = None
        self._stop = threading.Event()
        self.block = 512

    def generate(self, frames: int) -> np.ndarray:
        """Produce the next ``frames`` samples (used by the thread and by tests)."""
        sr = self.sample_rate
        idx = (self._noise_pos + np.arange(frames)) % len(self._noise)
        self._noise_pos = (self._noise_pos + frames) % len(self._noise)
        x = self._noise[idx].astype(np.float64)
        t = (self._n + np.arange(frames)) / sr
        self._n += frames
        # Mains hum with 2nd and 3rd harmonics.
        x += 0.05 * np.sin(2 * np.pi * self.hum_hz * t)
        x += 0.025 * np.sin(2 * np.pi * 2 * self.hum_hz * t)
        x += 0.012 * np.sin(2 * np.pi * 3 * self.hum_hz * t)
        # Feedback: 14 s cycle, ramps up exponentially for 8 s, then silent.
        cyc = t % 14.0
        amp = np.where(cyc < 8.0, 0.002 * 10 ** (cyc * 1.6 / 8.0), 0.0)
        amp = np.minimum(amp, 0.12)
        x += amp * np.sin(2 * np.pi * self.feedback_hz * t)
        return x.astype(np.float32)

    def _run(self) -> None:
        period = self.block / self.sample_rate
        next_t = time.perf_counter()
        while not self._stop.is_set():
            self._push(self.generate(self.block))
            next_t += period
            delay = next_t - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            elif delay < -0.5:  # fell far behind (e.g. machine asleep): resync
                next_t = time.perf_counter()

    def start(self) -> None:
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        self.running = True

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=1.0)
            self._thread = None
        self.running = False
