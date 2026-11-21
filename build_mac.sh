#!/bin/bash
# Build a standalone macOS app: "dist/Live Sound Analyzer.app" (+ a .zip for sharing).
# The build matches this Mac's CPU (Apple silicon or Intel).
set -e
cd "$(dirname "$0")"

PY=""
for p in python3.13 python3.12 python3.11 python3.10 python3; do
    if command -v "$p" >/dev/null 2>&1 &&
       "$p" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
        PY=$p; break
    fi
done
[ -n "$PY" ] || { echo "Python 3.10+ required (https://www.python.org/downloads/macos/)"; exit 1; }

[ -x .venv/bin/python ] || "$PY" -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt pyinstaller
.venv/bin/python -m PyInstaller --noconfirm --clean LiveSoundAnalyzer.spec

APP="dist/Live Sound Analyzer.app"
# Ad-hoc signature so macOS can remember the microphone permission for this app.
codesign --force --deep --sign - "$APP"
ditto -c -k --keepParent "$APP" "dist/LiveSoundAnalyzer-macOS-$(uname -m).zip"
echo
echo "Built: $APP"
echo "       dist/LiveSoundAnalyzer-macOS-$(uname -m).zip"
