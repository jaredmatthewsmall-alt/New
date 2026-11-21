@echo off
REM Live Sound Analyzer - run from source on Windows.
REM First run creates a virtual environment and installs dependencies.
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
    echo Creating virtual environment...
    py -3 -m venv .venv || python -m venv .venv || goto :nopython
    .venv\Scripts\python.exe -m pip install --upgrade pip
    .venv\Scripts\python.exe -m pip install -r requirements.txt || goto :fail
)
start "" .venv\Scripts\pythonw.exe LiveSoundAnalyzer.py %*
exit /b 0

:nopython
echo Python 3.10+ was not found. Install it from https://www.python.org/downloads/windows/
echo (tick "Add python.exe to PATH" during setup), then run this file again.
pause
exit /b 1

:fail
echo Dependency installation failed. See the messages above.
pause
exit /b 1
