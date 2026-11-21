# PyInstaller build spec:  pyinstaller --noconfirm --clean LiveSoundAnalyzer.spec
#
# Windows: dist/LiveSoundAnalyzer.exe   (single file)
# macOS:   dist/Live Sound Analyzer.app (app bundle, with microphone permission text)
import sys

from PyInstaller.utils.hooks import collect_data_files

sys.path.insert(0, SPECPATH)  # noqa: F821 (provided by PyInstaller)
from livesound import __version__  # noqa: E402

a = Analysis(
    ["LiveSoundAnalyzer.py"],
    datas=collect_data_files("pyqtgraph"),  # colour maps used by the spectrogram
    hiddenimports=[],
    excludes=["tkinter", "matplotlib", "PySide6.QtWebEngineCore", "PySide6.QtQuick",
              "PySide6.Qt3DCore", "PySide6.QtMultimedia", "PySide6.QtPdf"],
)
pyz = PYZ(a.pure)

if sys.platform == "darwin":
    # On macOS an app bundle must be a folder build (onedir).
    exe = EXE(
        pyz, a.scripts, [],
        exclude_binaries=True,
        name="LiveSoundAnalyzer",
        console=False,
        upx=False,
        argv_emulation=False,
    )
    coll = COLLECT(exe, a.binaries, a.datas, name="LiveSoundAnalyzer", upx=False)
    app = BUNDLE(
        coll,
        name="Live Sound Analyzer.app",
        bundle_identifier="com.livesound.analyzer",
        version=__version__,
        info_plist={
            "CFBundleDisplayName": "Live Sound Analyzer",
            "CFBundleShortVersionString": __version__,
            # Required: without this macOS refuses (or silences) microphone access.
            "NSMicrophoneUsageDescription":
                "Live Sound Analyzer listens to the microphone to display and analyze "
                "the frequency spectrum of your live sound.",
            "NSHighResolutionCapable": True,
            "LSMinimumSystemVersion": "11.0",
        },
    )
else:
    exe = EXE(
        pyz, a.scripts, a.binaries, a.datas, [],
        name="LiveSoundAnalyzer",
        console=False,          # GUI app: no console window
        upx=False,
    )
