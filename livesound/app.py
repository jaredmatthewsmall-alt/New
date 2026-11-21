"""Live Sound Analyzer - Windows desktop GUI (PySide6 + pyqtgraph)."""

from __future__ import annotations

import datetime as _dt
import math
import os
import sys
import time

import numpy as np
import pyqtgraph as pg
from PySide6 import QtCore, QtGui, QtWidgets

from . import __version__
from .analysis import (
    ADVISORY,
    CRITICAL,
    TARGET_CURVES,
    WARNING,
    FeedbackTracker,
    LiveSoundAnalyzer,
)
from .audio import (
    SD_IMPORT_ERROR,
    DemoSource,
    MicSource,
    default_input_device,
    list_input_devices,
)
from .dsp import (
    THIRD_OCTAVE_EXACT,
    THIRD_OCTAVE_NOMINAL,
    ExpAverager,
    PeakHold,
    Spectrum,
    band_levels,
    db,
    format_hz,
    note_name,
)

FMIN, FMAX = 20.0, 20000.0
DB_MIN, DB_MAX = -110.0, 0.0
DEMO = "demo"

SEVERITY_COLORS = {
    CRITICAL: QtGui.QColor(190, 40, 40),
    WARNING: QtGui.QColor(200, 120, 20),
    ADVISORY: QtGui.QColor(60, 110, 170),
    0: QtGui.QColor(90, 90, 90),
}

AVERAGING = {"Fast (0.1 s)": 0.1, "Medium (0.5 s)": 0.5, "Slow (2 s)": 2.0, "Very slow (5 s)": 5.0}
FFT_SIZES = [4096, 8192, 16384, 32768]

FREQ_TICKS = [20, 31.5, 50, 63, 100, 125, 200, 250, 500, 1000, 2000, 4000, 5000, 8000, 10000, 16000, 20000]
FREQ_TICKS_MAJOR = [20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000]


def _freq_label(f: float) -> str:
    return f"{f / 1000:g}k" if f >= 1000 else f"{f:g}"


def _log_ticks():
    return [[(math.log10(f), _freq_label(f)) for f in FREQ_TICKS_MAJOR],
            [(math.log10(f), "") for f in FREQ_TICKS if f not in FREQ_TICKS_MAJOR]]


class LevelMeter(QtWidgets.QWidget):
    """Horizontal dBFS meter with RMS bar, peak marker and numeric readout."""

    def __init__(self, label: str):
        super().__init__()
        self.rms_db = -120.0
        self.peak_db = -120.0
        self.hold_db = -120.0
        self._hold_t = 0.0
        self.label = label
        self.setMinimumHeight(26)

    def set_levels(self, rms_db: float, peak_db: float) -> None:
        now = time.perf_counter()
        self.rms_db = rms_db
        self.peak_db = peak_db
        if peak_db >= self.hold_db or now - self._hold_t > 2.0:
            self.hold_db = peak_db
            self._hold_t = now
        self.update()

    def paintEvent(self, _event):
        p = QtGui.QPainter(self)
        r = self.rect().adjusted(56, 3, -96, -3)
        p.fillRect(self.rect(), QtGui.QColor(30, 30, 30))
        p.setPen(QtGui.QColor(220, 220, 220))
        p.drawText(QtCore.QRect(4, 0, 50, self.height()), QtCore.Qt.AlignVCenter, self.label)

        def x_of(d):
            return r.left() + r.width() * (min(max(d, -60.0), 0.0) + 60.0) / 60.0

        grad = QtGui.QLinearGradient(r.left(), 0, r.right(), 0)
        grad.setColorAt(0.0, QtGui.QColor(40, 160, 60))
        grad.setColorAt(0.75, QtGui.QColor(60, 190, 60))   # -15 dBFS
        grad.setColorAt(0.85, QtGui.QColor(220, 200, 40))  # -9 dBFS
        grad.setColorAt(1.0, QtGui.QColor(230, 40, 40))
        p.fillRect(r, QtGui.QColor(55, 55, 55))
        p.fillRect(QtCore.QRectF(r.left(), r.top(), x_of(self.rms_db) - r.left(), r.height()),
                   QtGui.QBrush(grad))
        p.fillRect(QtCore.QRectF(r.left(), r.top() + r.height() * 0.65,
                                 x_of(self.peak_db) - r.left(), r.height() * 0.35),
                   QtGui.QColor(255, 255, 255, 110))
        hold_col = QtGui.QColor(255, 60, 60) if self.hold_db > -1 else QtGui.QColor(255, 255, 255)
        p.setPen(QtGui.QPen(hold_col, 2))
        xh = x_of(self.hold_db)
        p.drawLine(QtCore.QPointF(xh, r.top()), QtCore.QPointF(xh, r.bottom()))
        p.setPen(QtGui.QColor(140, 140, 140))
        for d in (-48, -36, -24, -18, -12, -6):
            x = x_of(d)
            p.drawLine(QtCore.QPointF(x, r.bottom() - 4), QtCore.QPointF(x, r.bottom()))
        p.setPen(QtGui.QColor(220, 220, 220))
        txt = f"{self.rms_db:5.1f} / {self.hold_db:5.1f}" if self.rms_db > -119 else "  --"
        p.drawText(QtCore.QRect(r.right() + 6, 0, 92, self.height()), QtCore.Qt.AlignVCenter, txt)


class MainWindow(QtWidgets.QMainWindow):
    def __init__(self, start_demo: bool = False):
        super().__init__()
        self.setWindowTitle(f"Live Sound Analyzer {__version__}")
        self.resize(1400, 860)
        pg.setConfigOptions(antialias=True, imageAxisOrder="row-major")

        self.source = None
        self.spectrum: Spectrum | None = None
        self.analyzer: LiveSoundAnalyzer | None = None
        self.disp_avg = ExpAverager(0.5)
        self.peak_hold = PeakHold(hold_s=2.0, decay_db_per_s=12.0)
        self._last_total = 0
        self._last_rec_refresh = 0.0
        self._recs = []
        self._fb_lines: list = []
        self._frozen = False

        self._build_ui()
        self._populate_devices()
        self.timer = QtCore.QTimer(self)
        self.timer.setInterval(33)
        self.timer.timeout.connect(self._tick)
        self.timer.start()
        if start_demo:
            self.source_combo.setCurrentIndex(self.source_combo.findData(DEMO))
            self.start()

    # ------------------------------------------------------------------ UI
    def _build_ui(self) -> None:
        central = QtWidgets.QWidget()
        self.setCentralWidget(central)
        outer = QtWidgets.QVBoxLayout(central)
        outer.setContentsMargins(6, 6, 6, 6)

        # --- controls row
        bar = QtWidgets.QHBoxLayout()
        outer.addLayout(bar)
        self.source_combo = QtWidgets.QComboBox()
        self.source_combo.setMinimumWidth(300)
        self.source_combo.setToolTip("Audio input: measurement mic, console matrix/aux feed, or the demo signal")
        self.source_combo.currentIndexChanged.connect(self._source_changed)
        refresh = QtWidgets.QToolButton()
        refresh.setText("↻")
        refresh.setToolTip("Rescan audio devices")
        refresh.clicked.connect(self._populate_devices)
        self.channel_combo = QtWidgets.QComboBox()
        self.channel_combo.setToolTip("Which input channel to analyse")
        self.channel_combo.currentIndexChanged.connect(self._restart_if_running)
        self.fft_combo = QtWidgets.QComboBox()
        for n in FFT_SIZES:
            self.fft_combo.addItem(f"{n}", n)
        self.fft_combo.setCurrentIndex(FFT_SIZES.index(8192))
        self.fft_combo.setToolTip("FFT size: larger = finer frequency resolution, slower response")
        self.fft_combo.currentIndexChanged.connect(self._restart_if_running)
        self.avg_combo = QtWidgets.QComboBox()
        self.avg_combo.addItems(list(AVERAGING))
        self.avg_combo.setCurrentIndex(1)
        self.avg_combo.currentTextChanged.connect(lambda k: setattr(self.disp_avg, "tau", AVERAGING[k]))
        self.target_combo = QtWidgets.QComboBox()
        self.target_combo.addItems(list(TARGET_CURVES))
        self.target_combo.setToolTip("Reference curve the tonal-balance analysis compares against")
        self.target_combo.currentTextChanged.connect(self._target_changed)
        self.sens_combo = QtWidgets.QComboBox()
        self.sens_combo.addItems(list(FeedbackTracker.SENSITIVITY))
        self.sens_combo.setCurrentText("Medium")
        self.sens_combo.setToolTip("Feedback detection sensitivity (High catches rings earlier, "
                                   "but flags more musical notes)")
        self.sens_combo.currentTextChanged.connect(
            lambda s: self.analyzer and self.analyzer.feedback.set_sensitivity(s))

        self.start_btn = QtWidgets.QPushButton("Start")
        self.start_btn.setCheckable(True)
        self.start_btn.setMinimumWidth(80)
        self.start_btn.toggled.connect(lambda on: self.start() if on else self.stop())
        self.freeze_btn = QtWidgets.QPushButton("Freeze")
        self.freeze_btn.setCheckable(True)
        self.freeze_btn.setToolTip("Freeze the graphs (analysis keeps running)")
        self.freeze_btn.toggled.connect(lambda on: setattr(self, "_frozen", on))
        reset_btn = QtWidgets.QPushButton("Reset")
        reset_btn.setToolTip("Clear averages, peak hold and detections")
        reset_btn.clicked.connect(self.reset)
        export_btn = QtWidgets.QPushButton("Export report…")
        export_btn.clicked.connect(self.export_report)

        def labeled(text, w):
            bar.addWidget(QtWidgets.QLabel(text))
            bar.addWidget(w)

        labeled("Input:", self.source_combo)
        bar.addWidget(refresh)
        labeled("Ch:", self.channel_combo)
        labeled("FFT:", self.fft_combo)
        labeled("Avg:", self.avg_combo)
        labeled("Target:", self.target_combo)
        labeled("Feedback:", self.sens_combo)
        bar.addStretch(1)
        for w in (self.start_btn, self.freeze_btn, reset_btn, export_btn):
            bar.addWidget(w)

        # --- plots
        main_split = QtWidgets.QSplitter(QtCore.Qt.Horizontal)
        outer.addWidget(main_split, 1)
        left = QtWidgets.QSplitter(QtCore.Qt.Vertical)
        main_split.addWidget(left)

        spec_box = QtWidgets.QWidget()
        sb = QtWidgets.QVBoxLayout(spec_box)
        sb.setContentsMargins(0, 0, 0, 0)
        self.cursor_label = QtWidgets.QLabel("Hover over the graph for frequency / level readout")
        sb.addWidget(self.cursor_label)
        self.spec_plot = pg.PlotWidget()
        sb.addWidget(self.spec_plot)
        left.addWidget(spec_box)
        sp = self.spec_plot
        sp.setLogMode(x=True, y=False)
        sp.setXRange(math.log10(FMIN), math.log10(FMAX), padding=0)
        sp.setYRange(DB_MIN, DB_MAX, padding=0)
        sp.setLimits(xMin=math.log10(10), xMax=math.log10(24000), yMin=-160, yMax=20)
        sp.showGrid(x=True, y=True, alpha=0.25)
        sp.setLabel("left", "Level", units="dBFS")
        sp.setLabel("bottom", "Frequency (Hz)")
        sp.getAxis("bottom").setTicks(_log_ticks())
        sp.addLegend(offset=(-10, 10))
        self.peak_curve = sp.plot(pen=pg.mkPen((255, 255, 255, 70), width=1), name="Peak hold")
        self.long_curve = sp.plot(pen=pg.mkPen((255, 170, 40), width=2), name="Long-term (4 s)")
        self.live_curve = sp.plot(pen=pg.mkPen((60, 200, 255), width=1.5), name="Live")
        self.spec_plot.scene().sigMouseMoved.connect(self._mouse_moved)

        bottom = QtWidgets.QSplitter(QtCore.Qt.Horizontal)
        left.addWidget(bottom)
        # RTA (1/3 octave)
        self.rta_plot = pg.PlotWidget(title="1/3-octave RTA (31-band GEQ centres) vs target")
        rp = self.rta_plot
        rp.setYRange(DB_MIN + 30, DB_MAX, padding=0)
        rp.setXRange(-0.7, len(THIRD_OCTAVE_EXACT) - 0.3, padding=0)
        rp.setMouseEnabled(x=False, y=True)
        rp.showGrid(y=True, alpha=0.25)
        rp.setLabel("left", "dBFS")
        rp.getAxis("bottom").setTicks([
            [(i, _freq_label(f)) for i, f in enumerate(THIRD_OCTAVE_NOMINAL) if i % 3 == 0],
            [(i, _freq_label(f)) for i, f in enumerate(THIRD_OCTAVE_NOMINAL) if i % 3 != 0]])
        self.rta_bars = pg.BarGraphItem(x=np.arange(len(THIRD_OCTAVE_EXACT)),
                                        height=np.zeros(len(THIRD_OCTAVE_EXACT)),
                                        y0=DB_MIN, width=0.8, brush=(60, 180, 90))
        rp.addItem(self.rta_bars)
        self.target_line = rp.plot(pen=pg.mkPen((255, 255, 255, 180), width=2, style=QtCore.Qt.DashLine))
        bottom.addWidget(self.rta_plot)
        # Spectrogram
        self.sg_plot = pg.PlotWidget(title="Spectrogram (newest at top)")
        self.sg_plot.setMouseEnabled(x=False, y=False)
        self.sg_plot.setToolTip("Feedback and ringing show up as bright vertical streaks")
        self.sg_plot.getAxis("bottom").setTicks(_log_ticks())
        self.sg_plot.hideAxis("left")
        self.sg_cols = 480
        self.sg_rows = 240
        self.sg_logf = np.linspace(math.log10(FMIN), math.log10(FMAX), self.sg_cols)
        self.sg_data = np.full((self.sg_rows, self.sg_cols), DB_MIN, dtype=np.float32)
        self.sg_img = pg.ImageItem(self.sg_data)
        self.sg_img.setLookupTable(pg.colormap.get("inferno").getLookupTable(nPts=256))
        self.sg_img.setLevels([-100, -20])
        x0, x1 = self.sg_logf[0], self.sg_logf[-1]
        self.sg_img.setRect(QtCore.QRectF(x0, 0, x1 - x0, self.sg_rows))
        self.sg_plot.addItem(self.sg_img)
        self.sg_plot.setXRange(x0, x1, padding=0)
        self.sg_plot.setYRange(0, self.sg_rows, padding=0)
        bottom.addWidget(self.sg_plot)
        left.setSizes([520, 300])

        # --- right-hand panel
        right = QtWidgets.QWidget()
        rl = QtWidgets.QVBoxLayout(right)
        rl.setContentsMargins(4, 0, 0, 0)
        main_split.addWidget(right)
        main_split.setSizes([920, 480])
        rl.addWidget(QtWidgets.QLabel("<b>Input level</b> (RMS / peak hold, dBFS)"))
        self.meter = LevelMeter("Input")
        rl.addWidget(self.meter)
        self.status_label = QtWidgets.QLabel("Stopped")
        self.status_label.setWordWrap(True)
        rl.addWidget(self.status_label)
        rl.addWidget(QtWidgets.QLabel("<b>Recommendations</b> (click one for details)"))
        self.rec_tree = QtWidgets.QTreeWidget()
        self.rec_tree.setHeaderLabels(["Severity", "Issue", "Suggested change"])
        self.rec_tree.setRootIsDecorated(False)
        self.rec_tree.setAlternatingRowColors(True)
        self.rec_tree.setWordWrap(True)
        hdr = self.rec_tree.header()
        hdr.setSectionResizeMode(0, QtWidgets.QHeaderView.ResizeToContents)
        hdr.setSectionResizeMode(1, QtWidgets.QHeaderView.ResizeToContents)
        hdr.setSectionResizeMode(2, QtWidgets.QHeaderView.Stretch)
        self.rec_tree.setTextElideMode(QtCore.Qt.ElideNone)
        self.rec_tree.setUniformRowHeights(False)
        self.rec_tree.itemSelectionChanged.connect(self._show_detail)
        rl.addWidget(self.rec_tree, 3)
        self.detail = QtWidgets.QTextBrowser()
        self.detail.setPlaceholderText("Details of the selected recommendation appear here.")
        rl.addWidget(self.detail, 1)
        hint = QtWidgets.QLabel(
            "Tip: for system tuning play pink noise through the PA and set Target to "
            "'Flat'. For a show, use the house curve and a measurement mic at FOH.")
        hint.setWordWrap(True)
        hint.setStyleSheet("color: gray;")
        rl.addWidget(hint)

        self.statusBar().showMessage("Choose an input and press Start.")

    # --------------------------------------------------------------- devices
    def _populate_devices(self) -> None:
        current = self.source_combo.currentData()
        self.source_combo.blockSignals(True)
        self.source_combo.clear()
        self._devices = {d["index"]: d for d in list_input_devices()}
        for d in self._devices.values():
            self.source_combo.addItem(f"{d['name']}  [{d['hostapi']}]", d["index"])
        self.source_combo.addItem("Demo signal (no microphone needed)", DEMO)
        self.source_combo.blockSignals(False)
        idx = self.source_combo.findData(current) if current is not None else -1
        if idx < 0:
            dflt = default_input_device()
            idx = self.source_combo.findData(dflt) if dflt is not None else -1
        self.source_combo.setCurrentIndex(max(idx, 0))
        self._source_changed()
        if SD_IMPORT_ERROR is not None:
            self.statusBar().showMessage(f"Audio input unavailable ({SD_IMPORT_ERROR}); demo signal only.")

    def _source_changed(self) -> None:
        data = self.source_combo.currentData()
        self.channel_combo.blockSignals(True)
        self.channel_combo.clear()
        if data == DEMO or data is None:
            self.channel_combo.addItem("1", 0)
        else:
            n = self._devices[data]["channels"]
            for c in range(min(n, 64)):
                self.channel_combo.addItem(str(c + 1), c)
            if n > 1:
                self.channel_combo.addItem("Mix", None)
        self.channel_combo.blockSignals(False)
        self._restart_if_running()

    def _target_changed(self, name: str) -> None:
        if self.analyzer:
            self.analyzer.set_target(name)
            self._last_rec_refresh = 0

    # -------------------------------------------------------------- control
    def start(self) -> None:
        self.stop(update_button=False)
        data = self.source_combo.currentData()
        fft = int(self.fft_combo.currentData())
        try:
            if data == DEMO or data is None:
                self.source = DemoSource(48000)
            else:
                self.source = MicSource(data, channel=self.channel_combo.currentData())
            self.source.start()
        except Exception as exc:
            self.source = None
            QtWidgets.QMessageBox.critical(self, "Could not open input",
                                           f"Could not start audio input:\n\n{exc}\n\n"
                                           "Try another device / host API (WASAPI or MME), "
                                           "and check Windows Privacy settings allow microphone access.")
            self.start_btn.blockSignals(True)
            self.start_btn.setChecked(False)
            self.start_btn.blockSignals(False)
            self.start_btn.setText("Start")
            return
        sr = self.source.sample_rate
        self.spectrum = Spectrum(sr, fft)
        self.analyzer = LiveSoundAnalyzer(sr, fft, self.spectrum.enbw,
                                          target=self.target_combo.currentText(),
                                          sensitivity=self.sens_combo.currentText())
        self.disp_avg.reset()
        self.peak_hold.reset()
        self._last_total = 0
        self._sg_cache = None
        self.start_btn.blockSignals(True)
        self.start_btn.setChecked(True)
        self.start_btn.blockSignals(False)
        self.start_btn.setText("Stop")
        self.statusBar().showMessage(
            f"Running: {self.source.device_name} @ {sr} Hz, FFT {fft} "
            f"({sr / fft:.1f} Hz resolution, {fft / sr * 1000:.0f} ms window)")

    def stop(self, update_button: bool = True) -> None:
        if self.source is not None:
            try:
                self.source.stop()
            except Exception:
                pass
            self.source = None
        if update_button:
            self.start_btn.blockSignals(True)
            self.start_btn.setChecked(False)
            self.start_btn.blockSignals(False)
            self.start_btn.setText("Start")
            self.status_label.setText("Stopped")
            self.statusBar().showMessage("Stopped.")

    def _restart_if_running(self, *_):
        if self.source is not None:
            self.start()

    def reset(self) -> None:
        self.disp_avg.reset()
        self.peak_hold.reset()
        if self.analyzer:
            self.analyzer.reset()
        self.sg_data[:] = DB_MIN
        self.sg_img.setImage(self.sg_data, autoLevels=False)
        self._recs = []
        self._refresh_recs(force=True)

    def closeEvent(self, event):
        self.stop()
        super().closeEvent(event)

    # ------------------------------------------------------------ main loop
    def _tick(self) -> None:
        src = self.source
        if src is None or self.spectrum is None:
            return
        total = src.buffer.total
        new = total - self._last_total
        if new <= 0:
            return
        self._last_total = total
        fft = self.spectrum.fft_size
        block, _ = src.buffer.read_latest(max(fft, 1))
        recent = block[-min(new, len(block)):]
        rms_db = float(db(np.mean(recent.astype(np.float64) ** 2)))
        peak = src.pop_peak()
        peak_db = float(db(peak ** 2))
        t = time.perf_counter()
        power = self.spectrum.power(block)
        self.analyzer.update(power, t, peak_abs=peak, rms_dbfs=rms_db)
        self.meter.set_levels(rms_db, peak_db)

        if not self._frozen:
            self._draw(power, t)
        if t - self._last_rec_refresh > 0.5:
            self._last_rec_refresh = t
            self._refresh_recs()
            self._update_status()

    def _draw(self, power: np.ndarray, t: float) -> None:
        f = self.spectrum.freqs
        avg = self.disp_avg.update(power, t)
        d = db(avg)
        self.live_curve.setData(f[1:], d[1:])
        self.peak_curve.setData(f[1:], self.peak_hold.update(db(power), t)[1:])
        if self.analyzer.slow.value is not None:
            self.long_curve.setData(f[1:], db(self.analyzer.slow.value)[1:])

        # RTA bars, coloured by deviation from target.
        bands = band_levels(f, avg, self.spectrum.enbw)
        bands = np.nan_to_num(bands, nan=DB_MIN)
        dev = self.analyzer.band_dev
        brushes = []
        for i in range(len(bands)):
            dv = 0.0 if dev is None or not np.isfinite(dev[i]) else dev[i]
            if dv > 6:
                brushes.append(pg.mkBrush(220, 60, 50))
            elif dv > 3:
                brushes.append(pg.mkBrush(230, 160, 40))
            elif dv < -4:
                brushes.append(pg.mkBrush(70, 120, 220))
            else:
                brushes.append(pg.mkBrush(60, 180, 90))
        self.rta_bars.setOpts(height=np.maximum(bands - DB_MIN, 0), brushes=brushes)
        if self.analyzer.band_db is not None:
            tgt = self.analyzer.target_curve(THIRD_OCTAVE_EXACT) + getattr(self.analyzer, "band_offset", 0)
            self.target_line.setData(np.arange(len(tgt)), tgt)

        # Spectrogram row on a log-frequency grid.
        # Max-pool FFT bins into each column so a single-bin feedback tone is
        # never lost; columns narrower than a bin (low end) are interpolated.
        if getattr(self, "_sg_cache", None) is None or self._sg_cache[0] is not f:
            step = self.sg_logf[1] - self.sg_logf[0]
            edges = 10 ** np.append(self.sg_logf - step / 2, self.sg_logf[-1] + step / 2)
            idx = np.clip(np.searchsorted(f, edges), 0, len(f) - 1)
            wide = idx[1:] > idx[:-1]
            self._sg_cache = (f, np.log10(np.maximum(f, 1e-3)), idx[:-1], wide)
        _, logf, starts, wide = self._sg_cache
        pdb = db(power)
        row = np.interp(self.sg_logf, logf, pdb)
        pooled = np.maximum.reduceat(pdb, starts)
        row[wide] = pooled[wide]
        self.sg_data[:-1] = self.sg_data[1:]
        self.sg_data[-1] = row
        self.sg_img.setImage(self.sg_data, autoLevels=False)

    def _update_status(self) -> None:
        a = self.analyzer
        peak, rms = a.level_stats()
        fb = sum(1 for r in self._recs if r.category == "Feedback")
        hum = f"hum {a.hum['freq']:.0f} Hz" if a.hum else "no hum"
        ov = f", {self.source.overflows} input overflows" if self.source and self.source.overflows else ""
        self.status_label.setText(
            f"Avg {rms:.1f} dBFS, peak {peak:.1f} dBFS (last 3 s) | "
            f"{fb} feedback/ring candidate(s) | {hum}{ov}")

    # -------------------------------------------------------- recommendations
    def _refresh_recs(self, force: bool = False) -> None:
        if self.analyzer is not None:
            self._recs = self.analyzer.recommendations()
        sel = self.rec_tree.selectedItems()
        sel_key = sel[0].data(0, QtCore.Qt.UserRole) if sel else None
        self.rec_tree.blockSignals(True)
        self.rec_tree.clear()
        for i, r in enumerate(self._recs):
            it = QtWidgets.QTreeWidgetItem([r.severity_name, f"{r.title}\n({r.category})", r.action])
            key = (r.category, r.title, round(r.freq or 0, -1))
            it.setData(0, QtCore.Qt.UserRole, key)
            it.setData(1, QtCore.Qt.UserRole, i)
            col = SEVERITY_COLORS[r.severity]
            it.setForeground(0, QtGui.QBrush(col))
            f = it.font(0)
            f.setBold(r.severity >= WARNING)
            it.setFont(0, f)
            it.setFont(1, f)
            for c in range(3):
                it.setToolTip(c, r.detail)
            self.rec_tree.addTopLevelItem(it)
            if key == sel_key:
                it.setSelected(True)
        if not self._recs:
            msg = ("No issues detected." if self.analyzer and self.analyzer.elapsed > 3
                   else "Listening... (tonal analysis starts after a few seconds)")
            self.rec_tree.addTopLevelItem(QtWidgets.QTreeWidgetItem(["", msg, ""]))
        self.rec_tree.blockSignals(False)
        self._show_detail()
        self._update_feedback_markers()

    def _show_detail(self) -> None:
        sel = self.rec_tree.selectedItems()
        if sel and sel[0].data(1, QtCore.Qt.UserRole) is not None:
            r = self._recs[sel[0].data(1, QtCore.Qt.UserRole)]
        elif self._recs:
            r = self._recs[0]  # nothing selected: describe the most urgent item
        else:
            self.detail.clear()
            return
        extra = ""
        if r.freq:
            extra = f"<br><i>Frequency {format_hz(r.freq)} ({note_name(r.freq)})"
            if r.gain_db is not None:
                extra += f", gain {r.gain_db:+.1f} dB"
            if r.q:
                extra += f", Q {r.q:g}"
            extra += "</i>"
        self.detail.setHtml(f"<b>{r.title}</b> &mdash; {r.severity_name}<br>"
                            f"<b>Do:</b> {r.action}<br><br>{r.detail}{extra}")

    def _update_feedback_markers(self) -> None:
        for ln in self._fb_lines:
            self.spec_plot.removeItem(ln)
        self._fb_lines = []
        for r in self._recs:
            if r.category != "Feedback" or not r.freq:
                continue
            color = (255, 60, 60) if r.severity >= CRITICAL else (255, 170, 40) if r.severity >= WARNING else (150, 170, 255)
            ln = pg.InfiniteLine(pos=math.log10(r.freq), angle=90,
                                 pen=pg.mkPen(color, width=1.5, style=QtCore.Qt.DashLine),
                                 label=format_hz(r.freq),
                                 labelOpts={"position": 0.95, "color": color, "fill": (0, 0, 0, 150)})
            ln.setZValue(-10)  # behind the traces so the peak itself stays visible
            self.spec_plot.addItem(ln)
            self._fb_lines.append(ln)

    # ---------------------------------------------------------------- misc
    def _mouse_moved(self, pos) -> None:
        vb = self.spec_plot.getPlotItem().vb
        if not self.spec_plot.sceneBoundingRect().contains(pos):
            return
        pt = vb.mapSceneToView(pos)
        f = 10 ** pt.x()
        txt = f"Cursor: {format_hz(f)}  ({note_name(f)})  {pt.y():.1f} dBFS"
        if self.spectrum is not None and self.disp_avg.value is not None:
            i = int(round(f / self.spectrum.bin_hz))
            if 0 < i < len(self.disp_avg.value):
                txt += f"   |   live level here: {float(db(self.disp_avg.value[i])):.1f} dBFS"
        self.cursor_label.setText(txt)

    def export_report(self) -> None:
        stamp = _dt.datetime.now().strftime("%Y%m%d_%H%M%S")
        default = os.path.join(os.path.expanduser("~"), f"livesound_report_{stamp}.txt")
        path, _ = QtWidgets.QFileDialog.getSaveFileName(self, "Export report", default, "Text (*.txt)")
        if not path:
            return
        try:
            write_report(path, self)
            png = os.path.splitext(path)[0] + ".png"
            self.grab().save(png)
            self.statusBar().showMessage(f"Saved {path} and {png}")
        except OSError as exc:
            QtWidgets.QMessageBox.critical(self, "Export failed", str(exc))


def write_report(path: str, win: MainWindow) -> None:
    a = win.analyzer
    lines = [f"Live Sound Analyzer report - {_dt.datetime.now():%Y-%m-%d %H:%M:%S}", ""]
    if a is None:
        lines.append("No analysis has been run.")
    else:
        peak, rms = a.level_stats()
        lines += [
            f"Input:        {win.source.device_name if win.source else '(stopped)'}",
            f"Sample rate:  {a.sample_rate} Hz   FFT: {a.fft_size}",
            f"Target curve: {a.target_name}",
            f"Levels:       avg {rms:.1f} dBFS, peak {peak:.1f} dBFS",
            f"Mains hum:    {str(int(a.hum['freq'])) + ' Hz' if a.hum else 'not detected'}",
            "", "RECOMMENDATIONS", "-" * 60,
        ]
        recs = a.recommendations()
        if not recs:
            lines.append("No issues detected.")
        for r in recs:
            lines += [f"[{r.severity_name}] {r.category}: {r.title}",
                      f"    Do:  {r.action}", f"    Why: {r.detail}", ""]
        lines += ["", "1/3-OCTAVE BANDS (long-term average)", "-" * 60,
                  f"{'Band':>10} {'Level dBFS':>11} {'vs target dB':>13}"]
        if a.band_db is not None:
            for nom, lv, dv in zip(THIRD_OCTAVE_NOMINAL, a.band_db, a.band_dev):
                lines.append(f"{format_hz(nom):>10} {lv:11.1f} {dv:+13.1f}")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv if argv is None else argv)
    if sys.platform == "win32":
        try:  # Show our own icon/name in the taskbar rather than python.exe's.
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("LiveSoundAnalyzer")
        except Exception:
            pass
    app = QtWidgets.QApplication(argv)
    app.setApplicationName("Live Sound Analyzer")
    app.setStyle("Fusion")
    pal = QtGui.QPalette()
    pal.setColor(QtGui.QPalette.Window, QtGui.QColor(45, 45, 48))
    pal.setColor(QtGui.QPalette.WindowText, QtGui.QColor(230, 230, 230))
    pal.setColor(QtGui.QPalette.Base, QtGui.QColor(30, 30, 32))
    pal.setColor(QtGui.QPalette.AlternateBase, QtGui.QColor(40, 40, 44))
    pal.setColor(QtGui.QPalette.Text, QtGui.QColor(230, 230, 230))
    pal.setColor(QtGui.QPalette.Button, QtGui.QColor(60, 60, 64))
    pal.setColor(QtGui.QPalette.ButtonText, QtGui.QColor(230, 230, 230))
    pal.setColor(QtGui.QPalette.Highlight, QtGui.QColor(50, 110, 180))
    pal.setColor(QtGui.QPalette.ToolTipBase, QtGui.QColor(250, 250, 220))
    pal.setColor(QtGui.QPalette.ToolTipText, QtGui.QColor(20, 20, 20))
    app.setPalette(pal)
    win = MainWindow(start_demo="--demo" in argv)
    win.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
