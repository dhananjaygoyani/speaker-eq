# Speaker EQ 1.0.0

The first release of Speaker EQ: one-click tuning for badly tuned speakers, on Windows, Linux and Android.

Speaker EQ plays a short test sweep through your speaker and listens with your computer's or phone's
microphone. It finds what's wrong (boomy bass, harsh treble, muffled vocals) and applies a correcting EQ to
everything you play.

## Highlights

- **One-click Auto-Tune**: measure and correct your speaker in about a minute
- **Plain-language diagnosis**: "boomy bass at 190 Hz", "harsh at 2.4 kHz", "bass rolls off below 84 Hz"
- **Tone sliders**: Bass, Warmth, Vocals, Clarity and Treble on top of the auto-tune
- **On/off switches** to compare with and without the EQ at the same loudness
- **Moving or Still mode**: tune a whole listening area, or safely from one fixed spot
- **Safe tuning**: never boosts what the speaker can't play or where it already distorts; never clips
- **Export** to the Equalizer APO / AutoEQ format for other EQ apps

## Downloads

| File | Platform | Requires |
|---|---|---|
| `SpeakerEQ.exe` | Windows 10/11 | [Equalizer APO](https://sourceforge.net/projects/equalizerapo/) (free) |
| `speaker-eq_1.0.0_all.deb` | Ubuntu / Debian Linux | PipeWire, EasyEffects (installed automatically) |
| `SpeakerEQ-1.0.0.apk` | Android 9 or newer | Allow installing apps from unknown sources |

## Install

**Windows:** install Equalizer APO and tick your speaker in its Configurator, then run `SpeakerEQ.exe`. Windows
asks for permission once, the first time the EQ is applied.

**Linux:**

    sudo apt install ./speaker-eq_1.0.0_all.deb

Then open **Speaker EQ** from your apps menu.

**Android:** open the APK on your phone and allow the installation. Open **Speaker EQ** and allow the
microphone when you first measure.

## Quick start

1. Connect your speaker and make it the default audio output.
2. Set **Moving While Measuring**: off if everything stays still, on if you'll move the device slowly.
3. Set your normal volume, keep the room quiet, and press **Measure & Auto-Tune**.
4. Adjust the sliders to taste.

## Known limitations

- Built-in laptop and phone mics aren't accurate in the treble, so corrections above 5 kHz are limited to ±3 dB.
  A calibrated USB mic gives full accuracy on desktop.
- **Windows:** the EQ lowers the overall volume a little to avoid clipping (Equalizer APO has no limiter). If a
  measurement clips, lower the microphone level in Windows Sound settings.
- **Android:** some phones block system-wide audio effects; the app tells you if yours does. After a phone
  restart, open the app once to switch the EQ back on.
- The EQ applies to audio from the device running Speaker EQ only.

## Tested

- **Linux:** tested end to end with a real Bluetooth speaker. The measured boomy 190 Hz and harsh 2.3 kHz peaks
  were removed, and overall deviation from the target dropped from 6.4 to 4.6 dB.
- **Windows and Android:** pass all automated tests, including a smoke test of the built exe and an emulator
  test of the APK. Real-device testing on these platforms is welcome; please report any problems in Issues.

See the README for full instructions and troubleshooting.
