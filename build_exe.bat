@echo off
REM Build a standalone Windows executable: dist\LiveSoundAnalyzer.exe
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
    py -3 -m venv .venv || python -m venv .venv || exit /b 1
)
.venv\Scripts\python.exe -m pip install --upgrade pip
.venv\Scripts\python.exe -m pip install -r requirements.txt pyinstaller || exit /b 1
.venv\Scripts\python.exe -m PyInstaller --noconfirm --clean LiveSoundAnalyzer.spec || exit /b 1
echo.
echo Built dist\LiveSoundAnalyzer.exe
pause
