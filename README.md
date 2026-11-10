# Speaker EQ

**Make any speaker sound better with one click.**

Many Bluetooth speakers are badly tuned: boomy bass, harsh treble, muffled vocals. Speaker EQ listens to your
speaker through your computer's or phone's microphone, works out what is wrong, and applies a correcting EQ to
everything you play. Simple tone sliders on top let you adjust the sound to your taste.

![Speaker EQ](docs/screenshot.png)

## Features

- **One-click Auto-Tune:** measures your speaker in about a minute and corrects it automatically.
- **Plain-language diagnosis:** for example, "peak +9.9 dB at 190 Hz (bass: boomy)" or "Bass rolls off below 84 Hz".
- **Tone sliders:** Bass, Warmth, Vocals, Clarity and Treble, applied on top of the auto-tune.
- **On/off switches:** compare with and without the EQ at the same loudness.
- **Moving or Still mode:** average a whole listening area, or tune safely from one fixed spot.
- **Safe by design:**
  - No boost below the speaker's bass limit.
  - No boost where the speaker already distorts.
  - No clipping.
- **Works everywhere:** Linux, Windows and Android, plus export to other EQ apps.

## Download

| Platform | File | Applies the EQ through |
|---|---|---|
| **Windows 10/11** | `SpeakerEQ.exe` | [Equalizer APO](https://sourceforge.net/projects/equalizerapo/) (free) |
| **Linux** (Ubuntu/Debian) | `speaker-eq_1.1.0_all.deb` | EasyEffects + PipeWire |
| **Android 9+** | `SpeakerEQ-1.1.0.apk` | Android's built-in system-wide audio effects |
| **Fire TV / Android TV** | `SpeakerEQ-TV-1.1.0.apk` (the same app; either file works) | Same as Android: measure on a phone or PC, send it to the TV |

Get them from the [Releases](../../releases) page.

## Install

### Windows

1. Install **Equalizer APO**. In its Configurator, tick your speaker (for example "Speakers (K68)") and restart
   Windows when asked.
2. Run **SpeakerEQ.exe**. No installation is needed.
3. The first time the EQ is applied, Windows asks for permission **once**, so Speaker EQ can add its settings
   to Equalizer APO.

Tip: Equalizer APO ships with demo filters. Remove the `Include: example.txt` line from
`C:\Program Files\EqualizerAPO\config\config.txt` so only Speaker EQ shapes the sound.

### Linux

```
sudo apt install ./speaker-eq_1.1.0_all.deb
```

Then open **Speaker EQ** from your apps menu, or run `speaker-eq-gui`. apt installs everything it needs
(Python, EasyEffects, PipeWire tools).

To run from source instead:

```
sudo apt install python3-numpy python3-scipy python3-matplotlib python3-tk python3-pil.imagetk \
                 pipewire-bin wireplumber easyeffects
./install.sh              # adds the app to your menu (./install.sh --uninstall removes it)
```

### Android

1. Open **SpeakerEQ-1.1.0.apk** on your phone. Allow installing from your browser or file manager when Android asks.
2. Open **Speaker EQ** and allow the microphone when you first measure.

The EQ keeps running in the background for every app; a "Speaker EQ is on" notification shows it's active.

### Fire TV Stick / Android TV

Fire TV devices have no microphone that apps can record from, so you measure with your phone and import the
result on the TV. The EQ then applies to every app on the Fire TV (Netflix, YouTube, Prime Video…).

1. On the Fire TV: **Settings → My Fire TV → Developer options** → turn on **Apps from Unknown Sources**
   (if Developer options is hidden: **About** → press the device name 7 times).
2. Install the APK with the **Downloader** app, then pair your speaker under
   **Settings → Controllers & Bluetooth Devices**.
3. On your phone: measure the speaker with Speaker EQ (same spot you'll listen from).
4. Send it wirelessly (below), or **Export…** the file and use **Import…** on the TV, which loads the newest
   EQ file in the TV's Download folder.

Use the remote's arrow keys to move between controls; ◀ ▶ adjusts the focused slider.

### Wireless import (any two devices on the same Wi-Fi)

Every device running Speaker EQ can receive an EQ over Wi-Fi. Its **Wireless import** card shows a 4-digit
**code**, and an EQ is only accepted with that code.

- **From a phone:** Speaker EQ → **Send to TV…** → pick the device (found automatically) → enter the code → **Send**.
- **From the desktop app (Linux/Windows):** **Send to TV…** → pick the TV (found automatically) → enter the code → **Send**.
- **From any computer:** open the address shown on the card (e.g. `http://192.168.1.97:8765`) in a browser,
  choose an exported EQ file or paste it, enter the code and press **Send**. No app needed.

Files from Speaker EQ, AutoEQ and Equalizer APO all work. The receiver keeps listening while the EQ is on,
even with the app closed. The code changes every time the device restarts.

## How to use

1. Connect your speaker and make it the **default audio output**.
2. Set **Moving While Measuring**:
   - **Off:** the laptop/phone and speaker stay completely still.
   - **On:** you'll move it slowly during the measurement (see below).
3. Set the volume you normally listen at, keep the room quiet, and press **Measure & Auto-Tune**.
4. Read the **Diagnosis**, then adjust to taste:
   - **Strength:** how much of the automatic correction to apply.
   - **Tone sliders:** drag, scroll, or double-click/double-tap to reset.
   - **Auto-Tune / Equalizer switches:** compare with and without the EQ.
5. Optional: **Send to TV…** sends the tuning to Speaker EQ on a Fire TV, Android TV or phone on the same Wi-Fi.

Your settings are saved and applied every time the app starts.

### Measuring tips

- **Distance:** put the mic where your head is when you listen, ideally 0.5–1 m from the speaker.
- **Moving mode:** move the laptop/phone slowly (about 10 cm per second) in a figure-8 or circle, within an
  area about 30–60 cm across around your listening spot. Keep the same distance from the speaker and keep
  the mic facing it.
- **Still mode** is designed for setups that can't move. It corrects the bass more gently, because room effects
  at one spot don't represent the whole room.
- **Volume:** measure at your normal volume. Many Bluetooth speakers change their sound with volume, and the
  diagnosis warns you if yours does.

## How it works

| Step | What happens |
|---|---|
| Noise check | Records the room's silence first. Frequencies the mic can't hear clearly above the noise aren't touched |
| Mic level | Adjusts automatically if the mic saturates or a sweep clips |
| Measurement | Plays exponential sine sweeps (20 Hz–20 kHz) and records them. Bluetooth delay is detected automatically |
| Analysis | Impulse response → frequency response with ear-like smoothing; distortion read from the same sweep |
| Speaker DSP check | A second, quieter sweep shows whether the speaker changes its tone with volume |
| Usable range | Finds the speaker's real bass and treble limits; nothing is boosted outside them |
| Target | Flat with a gentle downward slope, the in-room balance listeners prefer (Harman research) |
| EQ design | Up to 10 parametric filters fitted by least squares: cut freely, boost carefully |
| Playback | Loudness-matched so the EQ doesn't sound quieter, with a limiter to prevent clipping |

The approach combines published techniques from **Sonos Trueplay** (noise screening, frequency-dependent
smoothing, partial correction of room peaks), **Dirac Live / Audyssey** (multi-position averaging,
volume-dependent EQ detection), **REW** (no EQ outside the speaker's range), **AutoEQ** (parametric
optimizer) and **Farina's swept-sine method** (response and distortion from one sweep).

## Platform differences

| | Linux | Windows | Android |
|---|---|---|---|
| Automatic mic level | ✅ | ❌ lower it in Sound settings if asked | ✅ plays quieter instead |
| Loudness-matched EQ with limiter | ✅ | ❌ volume drops by the largest boost | ✅ |
| System-wide EQ | ✅ EasyEffects | ✅ Equalizer APO | ✅ on most phones (see below) |

## Accuracy and limitations

- **The microphone is the main limit.** Built-in laptop and phone mics aren't flat in the treble, so
  corrections above 5 kHz are limited to ±3 dB. For full accuracy on desktop, use a calibrated USB mic
  (e.g. miniDSP UMIK-1) and load its file with **Mic Calibration…**.
- **EQ can't create bass** a small speaker physically can't produce, and Speaker EQ won't push it into distortion.
- **The EQ applies to audio from the device running Speaker EQ**, not to other phones or computers connected
  to the same speaker.
- **Fire TV / Android TV:** can't measure (no usable microphone); import a measurement made on your phone.
- **Android:**
  - Some phones block system-wide effects. The app tells you, and the EQ then works only in music players
    that have an equalizer setting.
  - After a phone restart, open the app once to switch the EQ back on.
- **Linux:** Speaker EQ loads its own EasyEffects preset, which replaces other effects you set up in EasyEffects.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Default output is … not a Bluetooth speaker" | Make the speaker the default output in sound settings |
| "Microphone records noise/garbage" | Check the input device. Turn off mic noise suppression or lower the mic level |
| "Recording too quiet" | Turn the speaker up and check it's connected and selected |
| "Recording clipped" | Turn the speaker down or move the mic further away |
| "This device has no usable microphone" | Fire TV / Android TV: measure on your phone, **Export…**, then **Import…** on the TV |
| Sliders change nothing | Check the **Equalizer** switch is on. On Windows, check Equalizer APO is enabled for your speaker |
| Sound got quieter on Windows | Expected: Equalizer APO has no limiter, so the EQ lowers the level to stay clip-free. Turn the speaker up |

## Command line (Linux)

```
speaker-eq                  # measure 5 positions (prompts between them), write ParametricEQ.txt + response.png
speaker-eq --walk           # no prompts; keep moving the mic
speaker-eq --fixed          # mic and speaker stay still
speaker-eq --mic-cal FILE   # use a microphone calibration file
speaker-eq --selftest       # offline check of the whole analysis pipeline
speaker-eq --help           # all options: target tilt, max boost, number of filters, ...
```

The output file uses the standard Equalizer APO / AutoEQ format, so you can also import it into Peace,
Wavelet or Poweramp Equalizer.

## Development

```
python3 -m pytest tests                          # 10 desktop tests
./packaging/build-deb.sh                         # build dist/speaker-eq_<version>_all.deb
cd android && ./gradlew :app:testDebugUnitTest   # 8 Android tests (analysis, import, wireless)
cd android && ./gradlew :app:assembleRelease     # build the APK (needs the Android SDK)
```

GitHub Actions runs all tests on Linux, Windows and Android. It builds the `.deb` and `SpeakerEQ.exe` and
smoke-tests the exe. Pushing a tag like `v1.0.0` publishes a release with the `.deb` and `.exe` attached.

| Path | Purpose |
|---|---|
| `speaker_eq.py` | Measurement, analysis, diagnosis, EQ fitting, command line |
| `speaker_eq_gui.py` | Desktop app; EasyEffects (Linux) and Equalizer APO (Windows) integration |
| `android/` | Android app (Kotlin + Jetpack Compose): `Dsp.kt` analysis, `Audio.kt` audio and EQ engine, `MainActivity.kt` UI |
| `tests/` | Desktop test suite |
| `packaging/build-deb.sh`, `install.sh` | Linux packaging and per-user install |
| `.github/workflows/build.yml` | CI: tests, builds, releases |

Settings and the last measurement are stored in `~/.local/share/speaker-eq/` (Linux),
`%APPDATA%\speaker-eq` (Windows) or the app's private storage (Android).

## References

- Sonos Engineering, *Trueplay spectral correction*: https://tech-blog.sonos.com/posts/trueplay-spectral-correction/
- Dirac Live room correction: https://www.dirac.com/live/rc/
- Audyssey MultEQ: https://www.audioholics.com/room-acoustics/audyssey-room-eq-interview
- REW (Room EQ Wizard) EQ settings: https://www.roomeqwizard.com/help/help/html/eqsettings.html
- AutoEQ: https://github.com/jaakkopasanen/AutoEq
- A. Farina, *Simultaneous measurement of impulse response and distortion with a swept-sine technique*, AES 2000
