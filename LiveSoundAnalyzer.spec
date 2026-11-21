# PyInstaller build spec:  pyinstaller --noconfirm --clean LiveSoundAnalyzer.spec
from PyInstaller.utils.hooks import collect_data_files

a = Analysis(
    ["LiveSoundAnalyzer.py"],
    datas=collect_data_files("pyqtgraph"),  # colour maps used by the spectrogram
    hiddenimports=[],
    excludes=["tkinter", "matplotlib", "PySide6.QtWebEngineCore", "PySide6.QtQuick",
              "PySide6.Qt3DCore", "PySide6.QtMultimedia", "PySide6.QtPdf"],
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, a.binaries, a.datas, [],
    name="LiveSoundAnalyzer",
    console=False,          # GUI app: no console window
    upx=False,
)
