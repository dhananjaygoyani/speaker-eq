#!/bin/sh
# Build dist/speaker-eq_<version>_all.deb  (Debian/Ubuntu, needs only dpkg-deb)
set -e
umask 022
VERSION=1.1.1
ROOT=$(cd "$(dirname "$0")/.." && pwd)
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
P="$BUILD/speaker-eq"

install -Dm644 "$ROOT/speaker_eq.py"     "$P/usr/share/speaker-eq/speaker_eq.py"
install -Dm644 "$ROOT/speaker_eq_gui.py" "$P/usr/share/speaker-eq/speaker_eq_gui.py"
install -Dm644 "$ROOT/speaker-eq.png"    "$P/usr/share/speaker-eq/speaker-eq.png"
install -Dm644 "$ROOT/speaker-eq.png"    "$P/usr/share/icons/hicolor/128x128/apps/speaker-eq.png"
install -Dm644 "$ROOT/README.md"         "$P/usr/share/doc/speaker-eq/README.md"

mkdir -p "$P/usr/bin"
printf '#!/bin/sh\nexec python3 /usr/share/speaker-eq/speaker_eq.py "$@"\n' > "$P/usr/bin/speaker-eq"
printf '#!/bin/sh\nexec python3 /usr/share/speaker-eq/speaker_eq_gui.py "$@"\n' > "$P/usr/bin/speaker-eq-gui"
chmod 755 "$P/usr/bin/speaker-eq" "$P/usr/bin/speaker-eq-gui"

install -Dm644 /dev/stdin "$P/usr/share/applications/speaker-eq.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Speaker EQ
Comment=Auto-tune your speaker with the microphone
Exec=speaker-eq-gui
Icon=speaker-eq
Terminal=false
Categories=AudioVideo;Audio;
EOF

install -Dm644 /dev/stdin "$P/DEBIAN/control" <<EOF
Package: speaker-eq
Version: $VERSION
Section: sound
Priority: optional
Architecture: all
Depends: python3 (>= 3.10), python3-numpy, python3-scipy, python3-matplotlib, python3-tk, python3-pil, python3-pil.imagetk, pipewire-bin, wireplumber, easyeffects
Maintainer: Dhananjay Goyani <dhananjaygoyani000@gmail.com>
Homepage: https://github.com/dhananjaygoyani/speaker-eq
Description: Automatic speaker EQ using your microphone
 Measures a (Bluetooth) speaker with a sine sweep, diagnoses what is wrong
 (boomy bass, harsh treble, distortion, room peaks), and applies a correcting
 parametric EQ live through EasyEffects, with simple tone sliders on top.
EOF

mkdir -p "$ROOT/dist"
dpkg-deb --root-owner-group --build "$P" "$ROOT/dist/speaker-eq_${VERSION}_all.deb"
