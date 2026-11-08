#!/bin/sh
# Install Speaker EQ for the current user: app-menu launcher + `speaker-eq` / `speaker-eq-gui` commands.
# ./install.sh --uninstall removes them (your measurements and settings stay in this folder).
set -e
DIR=$(cd "$(dirname "$0")" && pwd)
APP="$HOME/.local/share/applications/speaker-eq.desktop"
BIN="$HOME/.local/bin"
if [ "$1" = "--uninstall" ]; then
    rm -f "$APP" "$BIN/speaker-eq" "$BIN/speaker-eq-gui"
    echo "Removed."
    exit 0
fi
python3 -c "import numpy, scipy, matplotlib, tkinter" 2>/dev/null || {
    echo "Missing Python packages. Run:"
    echo "  sudo apt install python3-numpy python3-scipy python3-matplotlib python3-tk"
    exit 1
}
for t in pw-play pw-record wpctl easyeffects; do
    command -v "$t" >/dev/null || echo "Warning: $t not found (sudo apt install pipewire-bin wireplumber easyeffects)"
done
mkdir -p "$BIN" "$(dirname "$APP")"
chmod +x "$DIR/speaker_eq.py" "$DIR/speaker_eq_gui.py"
ln -sf "$DIR/speaker_eq.py" "$BIN/speaker-eq"
ln -sf "$DIR/speaker_eq_gui.py" "$BIN/speaker-eq-gui"
cat > "$APP" <<EOF
[Desktop Entry]
Type=Application
Name=Speaker EQ
Comment=Auto-tune your speaker with the microphone
Exec=python3 "$DIR/speaker_eq_gui.py"
Icon=$DIR/speaker-eq.png
Terminal=false
Categories=AudioVideo;Audio;
EOF
echo "Installed. Open 'Speaker EQ' from your apps menu, or run: speaker-eq-gui"
