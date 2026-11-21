"""Launcher script (also the PyInstaller entry point).

    python LiveSoundAnalyzer.py          # normal start
    python LiveSoundAnalyzer.py --demo   # start straight into the demo signal
"""

import sys

from livesound.app import main

if __name__ == "__main__":
    sys.exit(main())
