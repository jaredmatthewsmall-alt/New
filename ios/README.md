# Live Sound Analyzer for iPhone (and iPad)

A native SwiftUI version of the desktop analyzer. It uses the same analysis engine, ported
to Swift. It listens through the iPhone's microphone (or a USB audio interface with a
measurement mic), draws the spectrum live and tells you what to change: feedback notches,
muddy or harsh regions, rumble, mains hum and input-level problems.

**Requires:** iOS 17 or later. To install: a Mac with Xcode 15 or later, **or just a Windows PC**
(see [Only have a Windows PC?](#only-have-a-windows-pc)).

## Screens

* **Level meter:** RMS, peak and peak hold in dBFS.
* **Spectrum:** 20 Hz to 20 kHz on a log axis, with a live trace (cyan), a 4-second average
  (orange) and peak hold. Feedback frequencies are marked with dashed red lines.
  **Touch and drag** for a frequency, note and level readout.
* **1/3-octave RTA or Spectrogram** (segmented switch). RTA bars use the 31-band GEQ centres
  and are coloured by deviation from the target curve. In the spectrogram, feedback shows
  up as a bright vertical streak.
* **Recommendations:** ranked Critical, Warning, Advisory. Tap one for the explanation and
  the exact frequency, gain and Q.
* **Settings:** microphone or demo signal, input port (built-in mic, headset, USB
  interface), target curve, feedback sensitivity, mains 50/60 Hz, FFT size, averaging.
* **Menu (...):** freeze graphs, reset averages, **share report** (text, via
  AirDrop/Mail/Notes and so on).
* Rotate to landscape for graphs and advice side by side.

While measuring, the app puts the audio session in **measurement mode**, which turns off
iOS's automatic gain control, noise suppression and voice EQ, so it sees what the mic
really hears. The screen is kept awake while running. Audio is analyzed on the device and
never recorded.

## Install it on your iPhone

1. On a Mac, install **Xcode** (App Store) and **XcodeGen**:
   ```bash
   brew install xcodegen
   ```
2. Generate the project and open it:
   ```bash
   cd ios
   xcodegen
   open LiveSoundAnalyzer.xcodeproj
   ```
3. In Xcode select the **LiveSoundAnalyzer** target, then **Signing & Capabilities**, and choose
   your **Team**. A free Apple ID works; add it under Xcode > Settings > Accounts. If Xcode
   says the bundle ID is taken, change `com.livesound.analyzer` to something unique.
4. Plug in your iPhone (or pair it over Wi-Fi), select it as the run destination, and press
   **Run** (Cmd+R).
5. The first time, on the iPhone go to **Settings > General > VPN & Device Management**
   and trust your developer certificate. On iOS 16 or later also turn on
   **Settings > Privacy & Security > Developer Mode** if asked.
6. Open **Live Sound**, press **Start** and allow microphone access.

Apps signed with a free Apple ID expire after 7 days (just run it from Xcode again). A paid
Apple Developer account lets the app last a year and allows TestFlight or App Store
distribution (Product > Archive).

No iPhone to hand? Pick an iPhone simulator in Xcode. The simulator uses your Mac's
microphone, or choose **Demo signal** in Settings.

## Only have a Windows PC?

You don't need a Mac. GitHub's Mac build machines compile the app for you on every push,
and a free Windows tool signs it with your Apple ID and installs it.

### Free: sideload with Sideloadly (free Apple ID, re-install every 7 days)

1. **Get the app file.** On GitHub, open the repository's **Actions** tab, click the latest
   **Build apps** run, and under *Artifacts* download **LiveSoundAnalyzer-iOS-unsigned-ipa**.
   Unzip it to get `LiveSoundAnalyzer-unsigned.ipa`.
2. **Install Apple's drivers.** Install **iTunes** and **iCloud** for Windows *from apple.com*
   (Sideloadly needs the apple.com versions, not the Microsoft Store ones).
3. **Install Sideloadly** from https://sideloadly.io and open it.
4. Plug the iPhone in with a USB cable, unlock it, and tap **Trust This Computer**.
5. Drag the `.ipa` into Sideloadly, enter your Apple ID, and click **Start**. Enter the
   two-factor code if asked.
6. On the iPhone:
   - **Settings > General > VPN & Device Management**: tap your Apple ID and choose **Trust**.
   - **Settings > Privacy & Security > Developer Mode**: turn it on and restart (iOS 16 or later).
7. Open **Live Sound**, press **Start**, and allow the microphone.

Limits with a free Apple ID: the app stops opening after **7 days**, so re-run step 5 to
refresh it (your settings are kept). You can have at most 3 sideloaded apps at once.
Sideloadly signs in to Apple with your Apple ID. If you'd rather not give a third-party tool
your main account, create a separate free Apple ID just for this.
(AltStore, https://altstore.io, is an alternative that can refresh the app automatically
over Wi-Fi while your PC is on.)

### Paid: TestFlight (Apple Developer Program, $99/year)

With a paid developer account the whole process can be done from Windows. The CI Mac
signs the app and uploads it to TestFlight, and you install it with Apple's TestFlight app.
Builds last 90 days, update automatically, and you can share them with your crew.
This needs one-time setup: an App Store Connect API key and a distribution certificate,
both creatable in a web browser, stored as GitHub secrets.

### Other options
* **Rent a Mac in the cloud** (e.g. MacinCloud, AWS EC2 Mac) by the hour and follow the
  Xcode steps above over remote desktop.
* **Borrow a Mac once.** With a paid account you only need it for the first install;
  after that, TestFlight works on its own.

## Tips for accurate measurements

* The built-in iPhone mics are fine for spotting feedback and big tonal problems, but they
  roll off the extreme lows and highs. For system tuning, use a measurement mic through a
  class-compliant USB audio interface (select it in Settings > Input).
* Keep the meter's average around -30 to -20 dBFS. If you get "Input clipping", move the
  phone further from the PA.
* Ringing out monitors: set sensitivity to *High* and raise the gain slowly. Apply each
  suggested notch, then continue.

## Code layout

```
ios/
  project.yml                 XcodeGen definition (generates LiveSoundAnalyzer.xcodeproj)
  App/                        SwiftUI app
    ContentView.swift           main screen, recommendations, settings, app entry point
    AnalyzerModel.swift         30 fps processing loop, permissions, interruptions
    AudioCapture.swift          AVAudioEngine microphone capture + demo generator
    GraphViews.swift            spectrum, RTA, spectrogram and level meter drawing
    SpectrogramRenderer.swift   scrolling spectrogram bitmap
  LiveSoundCore/              Swift package: FFT, DSP and analysis engine (no UIKit)
    Tests/                      unit tests, mirroring the desktop Python tests
```

`LiveSoundCore` has no Apple-only dependencies, so its tests also run on Linux:
`swift test --package-path LiveSoundCore`.
