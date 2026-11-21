#!/bin/bash
# Live Sound Analyzer - run from source on macOS.
# Double-click in Finder (or run ./run_mac.command). The first run creates a
# virtual environment and installs dependencies. Pass --demo to start with the demo signal.
set -e
cd "$(dirname "$0")"

find_python() {
    for py in python3.13 python3.12 python3.11 python3.10 python3; do
        if command -v "$py" >/dev/null 2>&1 &&
           "$py" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
            echo "$py"; return 0
        fi
    done
    return 1
}

if [ ! -x .venv/bin/python ]; then
    if ! PY=$(find_python); then
        echo "Python 3.10 or newer is required."
        echo "Install it from https://www.python.org/downloads/macos/ (or: brew install python)"
        echo "then run this file again."
        read -r -p "Press Return to close." _
        exit 1
    fi
    echo "Creating virtual environment with $PY ..."
    "$PY" -m venv .venv
    .venv/bin/python -m pip install --upgrade pip
    .venv/bin/python -m pip install -r requirements.txt
fi

exec .venv/bin/python LiveSoundAnalyzer.py "$@"
