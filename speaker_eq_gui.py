#!/usr/bin/env python3
"""Speaker EQ: one-click auto-tune plus simple tone sliders, applied live through EasyEffects."""
import argparse, contextlib, io, json, os, socket, struct, subprocess, sys, tempfile, threading, time
import urllib.error, urllib.parse, urllib.request
import tkinter as tk
from tkinter import filedialog, font as tkfont, messagebox

import numpy as np
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg
from matplotlib.figure import Figure
from PIL import Image, ImageDraw, ImageTk

import speaker_eq as se

# _MEIPASS: bundled files in the Windows exe; realpath: works when launched via ~/.local/bin symlink
HERE = getattr(sys, '_MEIPASS', os.path.dirname(os.path.realpath(__file__)))
# user-writable: the .deb installs the code read-only under /usr/share
DATA = os.path.join(os.environ.get('APPDATA') if se.WIN else
                    os.environ.get('XDG_DATA_HOME') or os.path.expanduser('~/.local/share'), 'speaker-eq')
STATE = os.path.join(DATA, 'gui_state.json')
OLD_STATE = os.path.join(HERE, 'gui_state.json')  # where versions before 1.0 kept it
PRESET = 'SpeakerEQ'
PRESET_PATH = os.path.expanduser(f'~/.config/easyeffects/output/{PRESET}.json')
EE_TYPE = {'PK': 'Bell', 'LSC': 'Lo-shelf', 'HSC': 'Hi-shelf'}

# (label, Hz shown, filter kind, frequency, Q)
SLIDERS = [('Bass', '100 Hz', 'LSC', 100, 0.71),
           ('Warmth', '300 Hz', 'PK', 300, 1.0),
           ('Vocals', '1 kHz', 'PK', 1000, 1.0),
           ('Clarity', '3 kHz', 'PK', 3000, 1.0),
           ('Treble', '8 kHz', 'HSC', 8000, 0.71)]

# light, iOS-like palette
BG, CARD, SHADOW, TEXT, SUB = '#f2f2f7', '#ffffff', '#e3e3e8', '#1c1c1e', '#8e8e93'
BLUE, BLUE_HOVER, GREEN, ORANGE = '#007aff', '#0062cc', '#34c759', '#ff9500'
TRACK, OFF, SEP, DISABLED = '#e5e5ea', '#e9e9eb', '#e5e5ea', '#c7c7cc'
SS = 3  # supersampling: Tk's canvas has no anti-aliasing, so widgets are drawn large with PIL and shrunk
FAMILY = 'TkDefaultFont'


def font(size, weight='normal'):
    return (FAMILY, size, weight)


def rgb(c):
    return tuple(int(c[i:i + 2], 16) for i in (1, 3, 5))


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(rgb(a), rgb(b)))


def render(w, h, paint, bg):
    im = Image.new('RGB', (w * SS, h * SS), bg)
    paint(ImageDraw.Draw(im), SS)
    return ImageTk.PhotoImage(im.resize((w, h), Image.LANCZOS))


def ee_preset(filters, preamp, bypass):
    """EasyEffects 7 output preset: one equalizer, 'APO (DR)' mode = same RBJ math as speaker_eq."""
    bands = {f'band{i}': {'frequency': f, 'gain': g, 'q': q, 'type': EE_TYPE[k], 'mode': 'APO (DR)',
                          'slope': 'x1', 'solo': False, 'mute': False, 'width': 4.0}
             for i, (f, g, q, k) in enumerate(filters)}
    # limiter after the EQ: catches the few peaks that boosts push over 0 dBFS, so the preamp can keep loudness
    limiter = {'bypass': bypass, 'mode': 'Herm Thin', 'oversampling': 'None', 'dithering': 'None',
               'threshold': -1.0, 'attack': 5.0, 'release': 20.0, 'lookahead': 5.0, 'stereo-link': 100.0,
               'gain-boost': False, 'alr': False, 'input-gain': 0.0, 'output-gain': 0.0, 'sidechain-preamp': 0.0,
               'external-sidechain': False}
    return {'output': {'blocklist': [], 'plugins_order': ['equalizer#0', 'limiter#0'], 'limiter#0': limiter,
                       'equalizer#0': {
        'bypass': bypass, 'mode': 'IIR', 'num-bands': len(bands), 'input-gain': preamp, 'output-gain': 0.0,
        'split-channels': False, 'balance': 0.0, 'pitch-left': 0.0, 'pitch-right': 0.0,
        'left': bands, 'right': bands}}}


def loudness_gain(filters):
    """Preamp that keeps average loudness unchanged (mean EQ gain over 100 Hz-6 kHz, where music's energy and
    our hearing are), instead of the worst-case -max(boost) that made everything sound much quieter."""
    band = (se.GRID >= 100) & (se.GRID <= 6000)
    return float(np.clip(-se.peq_db(filters)[band].mean(), -12, 6).round(1))


TV_PORT = 8765  # Speaker EQ's wireless receiver (Android / Fire TV app)


def _dns_name(data, i):
    """DNS name at offset i (follows compression pointers); returns (labels, offset after the name)."""
    labels, end = [], None
    for _ in range(128):  # bounded: a malicious pointer loop can't hang us
        n = data[i]
        if n == 0:
            return labels, end if end is not None else i + 1
        if n & 0xC0 == 0xC0:
            end = i + 2 if end is None else end
            i = ((n & 0x3F) << 8) | data[i + 1]
            continue
        labels.append(data[i + 1:i + 1 + n].decode('utf-8', 'replace'))
        i += 1 + n
    raise IndexError('DNS name too long')


def mdns_instance(data):
    """Instance name ("Speaker EQ on AFTSSS") from an mDNS reply to our PTR query, else None."""
    try:
        qd, an = struct.unpack('>HH', data[4:8])
        i = 12
        for _ in range(qd):
            i = _dns_name(data, i)[1] + 4
        for _ in range(an):
            name, i = _dns_name(data, i)
            typ, _cls, _ttl, rdlen = struct.unpack('>HHIH', data[i:i + 10])
            i += 10
            if typ == 12 and [x.lower() for x in name[:2]] == ['_speakereq', '_tcp']:
                return _dns_name(data, i)[0][0]
            i += rdlen
    except (IndexError, struct.error):
        pass  # malformed packet from the network: ignore it
    return None


def discover_tvs(timeout=3.0):
    """Speaker EQ receivers on the Wi-Fi ({address: name}), via the same DNS-SD service the phone app finds."""
    q = (struct.pack('>6H', 0, 0, 1, 0, 0, 0) +
         b''.join(bytes([len(x)]) + x for x in b'_speakereq._tcp.local'.split(b'.')) + b'\0' + struct.pack('>HH', 12, 1))
    found, end = {}, time.time() + timeout
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    try:
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
        s.settimeout(0.5)
        while time.time() < end:
            s.sendto(q, ('224.0.0.251', 5353))
            try:
                data, (ip, _port) = s.recvfrom(9000)
            except socket.timeout:
                continue
            name = mdns_instance(data)
            if name:
                found[f'{ip}:{TV_PORT}'] = name
    except OSError:
        pass  # no network: the address can still be typed in
    finally:
        s.close()
    return found


def send_to_tv(address, code, text):
    """POST EQ text to a Speaker EQ receiver; returns its reply or a readable error."""
    address = address.strip()
    if ':' not in address:
        address += f':{TV_PORT}'
    url = f'http://{address}/eq?code={urllib.parse.quote(code.strip())}'
    req = urllib.request.Request(url, data=text.encode(), method='POST', headers={'Content-Type': 'text/plain; charset=utf-8'})
    try:
        with urllib.request.urlopen(req, timeout=4) as r:
            return r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:  # wrong code / bad file: the receiver explains why
        return e.read().decode('utf-8', 'replace')
    except (urllib.error.URLError, OSError):
        return f'Could not reach {address}. Check both are on the same Wi-Fi and Speaker EQ is open on the TV.'


def apo_config_dir():
    """Equalizer APO's config folder (Windows), or None if it isn't installed."""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r'SOFTWARE\EqualizerAPO') as k:
            d = os.path.join(winreg.QueryValueEx(k, 'InstallPath')[0], 'config')
    except (ImportError, OSError):
        d = r'C:\Program Files\EqualizerAPO\config'
    return d if os.path.isdir(d) else None


def write_apo_live(cfg, filters, bypass):
    """Write SpeakerEQ.txt, which config.txt includes; Equalizer APO reloads it instantly.
    False means the one-time admin setup (setup_apo) is still needed."""
    try:
        with open(os.path.join(cfg, 'config.txt'), encoding='utf-8', errors='ignore') as fh:
            if not any(l.strip().lower() == 'include: speakereq.txt' for l in fh):
                return False
        se.write_apo(os.path.join(cfg, 'SpeakerEQ.txt'), [] if bypass else filters)
        return True
    except OSError:  # PermissionError: file not yet made writable
        return False


def setup_apo(cfg):
    """One UAC prompt: create SpeakerEQ.txt, let Users write it, and include it from config.txt."""
    import ctypes
    path, conf = os.path.join(cfg, 'SpeakerEQ.txt'), os.path.join(cfg, 'config.txt')
    ps = (f"New-Item -Force -ItemType File '{path}' | Out-Null; icacls '{path}' /grant '*S-1-5-32-545:M' | Out-Null; "
          f"if (-not (Select-String -Quiet -SimpleMatch 'Include: SpeakerEQ.txt' '{conf}')) "
          f"{{ Add-Content '{conf}' ([Environment]::NewLine + 'Include: SpeakerEQ.txt') }}")
    ctypes.windll.shell32.ShellExecuteW(None, 'runas', 'powershell.exe',
                                        f'-NoProfile -WindowStyle Hidden -Command "{ps}"', None, 0)


class Card(tk.Canvas):
    """White rounded card; put widgets in .body. fill_y: body stretches to the card's height."""

    def __init__(self, parent, pad=16, width=380, fill_y=False):
        super().__init__(parent, bg=BG, highlightthickness=0, bd=0, width=width)
        self.pad, self.fill_y = pad, fill_y
        self.body = tk.Frame(self, bg=CARD)
        self.win = self.create_window(pad, pad, window=self.body, anchor='nw')
        if not fill_y:
            self.body.bind('<Configure>', lambda e: self.config(height=e.height + 2 * pad))
        self.bind('<Configure>', self._resize)

    def _resize(self, e):
        self.itemconfig(self.win, width=max(1, e.width - 2 * self.pad))
        if self.fill_y:
            self.itemconfig(self.win, height=max(1, e.height - 2 * self.pad))

        def paint(d, s):
            d.rounded_rectangle([0, s, e.width * s - 1, e.height * s - 1], 14 * s, fill=SHADOW)
            d.rounded_rectangle([0, 0, e.width * s - 1, e.height * s - 2 * s], 14 * s, fill=CARD)
        self.img = render(e.width, e.height, paint, BG)
        self.delete('bg')
        self.create_image(0, 0, image=self.img, anchor='nw', tags='bg')
        self.tag_lower('bg')


class Switch(tk.Canvas):
    """iOS-style toggle bound to a BooleanVar, with a short slide animation."""
    W, H = 51, 31

    def __init__(self, parent, var):
        super().__init__(parent, width=self.W, height=self.H, bg=CARD, highlightthickness=0, cursor='hand2')
        self.var, self.pos = var, float(var.get())
        self.bind('<Button-1>', lambda e: var.set(not var.get()))
        var.trace_add('write', lambda *a: self.animate())
        self.draw()

    def animate(self):
        t = float(self.var.get())
        self.pos = t if abs(t - self.pos) <= 0.25 else self.pos + (0.25 if t > self.pos else -0.25)
        self.draw()
        if self.pos != t:
            self.after(15, self.animate)

    def draw(self):
        p = self.pos

        def paint(d, s):
            W, H, r = self.W * s, self.H * s, 13.5 * s
            track = mix(OFF, GREEN, p)
            d.rounded_rectangle([0, 0, W - 1, H - 1], H // 2, fill=track)
            cx, cy = 2 * s + r + p * (W - 4 * s - 2 * r), H / 2
            d.ellipse([cx - r, cy - r + s, cx + r, cy + r + s], fill=tuple(int(c * 0.85) for c in track))
            d.ellipse([cx - r, cy - r, cx + r, cy + r], fill='white')
        self.img = render(self.W, self.H, paint, CARD)
        self.delete('all')
        self.create_image(0, 0, image=self.img, anchor='nw')


class Slider(tk.Canvas):
    """Thin track, blue fill (from 0 for +/- sliders), round white knob. Drag, scroll, double-click resets."""
    H, R = 26, 11

    def __init__(self, parent, var, lo, hi, step, default, bipolar):
        super().__init__(parent, height=self.H, bg=CARD, highlightthickness=0, cursor='hand2')
        self.var, self.lo, self.hi, self.step, self.bipolar, self.enabled = var, lo, hi, step, bipolar, True
        for ev in ('<Button-1>', '<B1-Motion>'):
            self.bind(ev, self.drag)
        self.bind('<Double-Button-1>', lambda e: self.set(default))
        self.bind('<Button-4>', lambda e: self.set(var.get() + step))  # Linux scroll wheel
        self.bind('<Button-5>', lambda e: self.set(var.get() - step))
        self.bind('<MouseWheel>', lambda e: self.set(var.get() + (step if e.delta > 0 else -step)))  # Windows
        self.bind('<Configure>', lambda e: self.draw())
        var.trace_add('write', lambda *a: self.draw())

    def set_enabled(self, on):
        if on != self.enabled:
            self.enabled = on
            self.config(cursor='hand2' if on else '')
            self.draw()

    def set(self, v):
        if self.enabled:
            v = min(self.hi, max(self.lo, round(v / self.step) * self.step))
            if v != self.var.get():
                self.var.set(v)

    def x_of(self, v, w):
        return self.R + (v - self.lo) / (self.hi - self.lo) * (w - 2 * self.R)

    def drag(self, e):
        w = self.winfo_width()
        self.set(self.lo + (e.x - self.R) / (w - 2 * self.R) * (self.hi - self.lo))

    def draw(self):
        w = self.winfo_width()
        if w < 3 * self.R:
            return
        v = self.var.get()

        def paint(d, s):
            cy, th, R = self.H * s / 2, 4 * s, self.R * s
            d.rounded_rectangle([R, cy - th / 2, w * s - R, cy + th / 2], th / 2, fill=TRACK)
            a, b = sorted((self.x_of(0 if self.bipolar else self.lo, w) * s, self.x_of(v, w) * s))
            if b - a > 1:
                d.rounded_rectangle([a, cy - th / 2, b, cy + th / 2], th / 2, fill=BLUE if self.enabled else DISABLED)
            if self.bipolar:
                c = self.x_of(0, w) * s
                d.rectangle([c - s / 2, cy - 5 * s, c + s / 2, cy + 5 * s], fill=DISABLED)
            x = self.x_of(v, w) * s
            d.ellipse([x - R, cy - R + 1.5 * s, x + R, cy + R + 1.5 * s], fill=SHADOW)
            d.ellipse([x - R, cy - R, x + R, cy + R], fill='white', outline='#d8d8dc', width=s)
        self.img = render(w, self.H, paint, CARD)
        self.delete('all')
        self.create_image(0, 0, image=self.img, anchor='nw')


class Pill(tk.Canvas):
    """Rounded blue primary button."""
    W, H = 220, 40

    def __init__(self, parent, text, command):
        super().__init__(parent, width=self.W, height=self.H, bg=BG, highlightthickness=0, cursor='hand2')
        self.text, self.enabled, self.hover = text, True, False
        self.bind('<Button-1>', lambda e: self.enabled and command())
        self.bind('<Enter>', lambda e: self._hover(True))
        self.bind('<Leave>', lambda e: self._hover(False))
        self.draw()

    def _hover(self, h):
        self.hover = h
        self.draw()

    def set_enabled(self, on, text=None):
        self.enabled = on
        self.label = text
        self.config(cursor='hand2' if on else 'watch')
        self.draw()

    def draw(self):
        c = DISABLED if not self.enabled else BLUE_HOVER if self.hover else BLUE
        self.img = render(self.W, self.H, lambda d, s: d.rounded_rectangle(
            [0, 0, self.W * s - 1, self.H * s - 1], self.H * s // 2, fill=c), BG)
        self.delete('all')
        self.create_image(0, 0, image=self.img, anchor='nw')
        self.create_text(self.W / 2, self.H / 2, text=getattr(self, 'label', None) or self.text,
                         fill='white', font=font(12, 'bold'))


class App:
    def __init__(self, root):
        global FAMILY
        FAMILY = next((f for f in ('Segoe UI', 'Noto Sans') if f in tkfont.families(root)), FAMILY)
        self.root = root
        root.title('Speaker EQ')
        root.configure(bg=BG)
        root.geometry('1120x780')
        root.minsize(980, 720)
        try:
            root.iconphoto(True, tk.PhotoImage(file=os.path.join(HERE, 'speaker-eq.png')))
        except tk.TclError:
            pass  # icon is cosmetic
        self.st = {'auto': [], 'dev': None, 'amount': 100, 'enabled': True, 'auto_on': True, 'mic_cal': None, 'diag': '', 'moving': False,
                   'sliders': {s[0]: 0.0 for s in SLIDERS}}
        try:
            with open(STATE if os.path.exists(STATE) or not os.path.exists(OLD_STATE) else OLD_STATE) as fh:
                self.st.update(json.load(fh))
        except (OSError, ValueError):
            pass  # first run or unreadable state: start from defaults
        self.pending = None
        self.measuring = False
        self.done_msg = ''  # shown once with the next 'Applied' status
        self.on = tk.BooleanVar(value=self.st['enabled'])
        self.auto_on = tk.BooleanVar(value=self.st['auto_on'])
        self.moving = tk.BooleanVar(value=self.st['moving'])
        self.amount = tk.DoubleVar(value=self.st['amount'])
        self.vars = {n: tk.DoubleVar(value=self.st['sliders'].get(n, 0.0)) for n, *_ in SLIDERS}

        outer = tk.Frame(root, bg=BG, padx=24, pady=20)
        outer.pack(fill='both', expand=True)

        head = tk.Frame(outer, bg=BG)
        head.pack(fill='x', pady=(0, 14))
        head.columnconfigure(1, weight=1)
        tk.Label(head, text='Speaker EQ', font=font(24, 'bold'), bg=BG, fg=TEXT).grid(row=0, column=0, sticky='w')
        self.status = tk.Label(head, text='Ready', font=font(11), bg=BG, fg=SUB)
        self.status.grid(row=1, column=0, sticky='w')
        links = tk.Frame(head, bg=BG)
        links.grid(row=0, column=2, rowspan=2, padx=(0, 16))
        for text, cmd in (('Reset', self.reset), ('Import…', self.import_eq), ('Export…', self.export),
                          ('Send to TV…', self.send_tv),
                          ('Mic Calibration…', self.pick_cal)):
            lb = tk.Label(links, text=text, font=font(11), bg=BG, fg=BLUE, cursor='hand2')
            lb.pack(side='left', padx=8)
            lb.bind('<Button-1>', lambda e, cmd=cmd: cmd())
        self.btn = Pill(head, 'Measure & Auto-Tune', self.measure)
        self.btn.grid(row=0, column=3, rowspan=2, sticky='e')

        cols = tk.Frame(outer, bg=BG)
        cols.pack(fill='both', expand=True)
        left = tk.Frame(cols, bg=BG)
        left.pack(side='left', fill='y')
        right = tk.Frame(cols, bg=BG)
        right.pack(side='left', fill='both', expand=True, padx=(18, 0))

        c = Card(left)
        c.pack(fill='x')
        self.switch_row(c.body, 'Auto-Tune', 'Corrects what the measurement found', self.auto_on)
        self.amount_slider = self.slider_row(c.body, 'Strength', '', self.amount, 0, 100, 5, 100,
                                             lambda v: f'{v:.0f}%', bipolar=False)
        self.sep(c.body)
        self.switch_row(c.body, 'Equalizer', 'All sound processing on or off', self.on)
        self.sep(c.body)
        self.switch_row(c.body, 'Moving While Measuring', 'Off if laptop and speaker stay still',
                        self.moving)

        self.section(left, 'TONE')
        c = Card(left)
        c.pack(fill='x')
        for i, (name, hz, *_rest) in enumerate(SLIDERS):
            if i:
                self.sep(c.body)
            self.slider_row(c.body, name, hz, self.vars[name], -12, 12, 0.5, 0,
                            lambda v: f'{v:+.1f} dB' if v else '0 dB', bipolar=True)


        self.section(right, 'FREQUENCY RESPONSE', top=0)
        g = Card(right, fill_y=True)
        g.pack(fill='both', expand=True)
        fig = Figure(figsize=(6, 3.4), dpi=100, facecolor=CARD, constrained_layout=True)
        self.ax = fig.add_subplot()
        self.canvas = FigureCanvasTkAgg(fig, g.body)
        self.canvas.get_tk_widget().configure(bg=CARD, highlightthickness=0)
        self.canvas.get_tk_widget().pack(fill='both', expand=True)

        self.section(right, 'DIAGNOSIS')
        d = Card(right)
        d.pack(fill='x')
        self.log = tk.Text(d.body, height=7, wrap='word', bg=CARD, fg=TEXT, font=font(10), relief='flat', bd=0,
                           highlightthickness=0)
        self.log.pack(fill='x')
        self.say(self.st['diag'] or 'Place the laptop 0.5–1 m from the speaker in a quiet room, set your normal volume, and click '
                 '"Measure & Auto-Tune". Set "Moving While Measuring" to match: on if you will move the laptop slowly, '
                 'off if everything stays still.\n'
                 'Then shape the sound with the Tone sliders. Double-click a slider to reset it. '
                 'Big bass boosts can make small speakers distort.')

        for v in (self.on, self.auto_on, self.amount, *self.vars.values()):
            v.trace_add('write', lambda *a: self.changed())
        self.paint()
        self.draw()

    # --- layout helpers ---
    def section(self, parent, text, top=14):
        tk.Label(parent, text=text, font=font(9, 'bold'), bg=BG, fg=SUB).pack(anchor='w', padx=6, pady=(top, 6))

    def sep(self, parent):
        tk.Frame(parent, bg=SEP, height=1).pack(fill='x', pady=3)

    def switch_row(self, parent, title, subtitle, var):
        r = tk.Frame(parent, bg=CARD)
        r.pack(fill='x', pady=2)
        t = tk.Frame(r, bg=CARD)
        t.pack(side='left')
        tk.Label(t, text=title, font=font(13, 'bold'), bg=CARD, fg=TEXT).pack(anchor='w')
        tk.Label(t, text=subtitle, font=font(10), bg=CARD, fg=SUB).pack(anchor='w')
        Switch(r, var).pack(side='right')

    def slider_row(self, parent, title, hint, var, lo, hi, step, default, fmt, bipolar):
        r = tk.Frame(parent, bg=CARD)
        r.pack(fill='x', pady=2)
        top = tk.Frame(r, bg=CARD)
        top.pack(fill='x')
        tk.Label(top, text=title, font=font(12), bg=CARD, fg=TEXT).pack(side='left')
        tk.Label(top, text=hint, font=font(10), bg=CARD, fg=SUB).pack(side='left', padx=6)
        val = tk.Label(top, font=font(11), bg=CARD, fg=SUB)
        val.pack(side='right')
        s = Slider(r, var, lo, hi, step, default, bipolar)
        s.pack(fill='x')
        update = lambda *a: val.config(text=fmt(var.get()))
        update()
        var.trace_add('write', update)
        return s

    # --- logic ---
    def filters(self):
        k = self.amount.get() / 100
        # measured filters are [f, g, q]; imported ones also carry their kind (shelves)
        auto = [(f, g * k, q, *(kind or ['PK'])) for f, g, q, *kind in self.st['auto']] if self.auto_on.get() else []
        tone = [(f, self.vars[n].get(), q, kind) for n, _hz, kind, f, q in SLIDERS]
        return auto + tone

    def say(self, text):
        self.log.delete('1.0', 'end')
        self.log.insert('end', text)

    def paint(self):
        self.amount_slider.set_enabled(self.auto_on.get())

    def draw(self):
        eq = se.peq_db(self.filters()) if self.on.get() else np.zeros_like(se.GRID)
        ax = self.ax
        ax.clear()
        ax.set_facecolor(CARD)
        ax.axhline(0, color=SUB, ls=(0, (4, 4)), lw=1, label='Target')
        if self.st['dev'] is not None:
            dev = se.oct3(np.array(self.st['dev']))  # same 1/3-oct view the auto-tune decided on; raw is too spiky
            ax.semilogx(se.GRID, dev, color=DISABLED, lw=1.8, label='Speaker')
            ax.semilogx(se.GRID, dev + eq, color=BLUE, lw=2.6, label='With EQ')
        ax.semilogx(se.GRID, eq, color=ORANGE, lw=1.8, label='EQ')
        ax.set_xticks([20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000],
                      ['20', '50', '100', '200', '500', '1k', '2k', '5k', '10k', '20k'])
        ax.minorticks_off()
        ax.set_xlim(20, 20000)
        ax.set_ylim(-20, 15)
        for side in ('top', 'right', 'left'):
            ax.spines[side].set_visible(False)
        ax.spines['bottom'].set_color(SEP)
        ax.tick_params(colors=SUB, labelsize=9, length=0)
        ax.grid(True, color='#efeff4', lw=0.8)
        ax.set_ylabel('dB', color=SUB, fontsize=9)
        ax.legend(loc='upper center', ncol=4, fontsize=9, frameon=False, labelcolor=TEXT)
        self.canvas.draw_idle()

    def changed(self):
        self.paint()
        self.draw()
        if self.pending:
            self.root.after_cancel(self.pending)
        self.pending = self.root.after(300, self.apply)  # debounce slider drags

    def save_state(self):
        self.st.update(amount=self.amount.get(), enabled=self.on.get(), auto_on=self.auto_on.get(), moving=self.moving.get(),
                       sliders={n: v.get() for n, v in self.vars.items()})
        os.makedirs(os.path.dirname(STATE), exist_ok=True)
        with open(STATE, 'w') as fh:
            json.dump(self.st, fh)

    def apply(self, bypass=None):
        self.pending = None
        if self.measuring:
            bypass = True  # a delayed re-apply must never switch the EQ on mid-sweep
        filt = self.filters()
        if se.WIN:
            return self.apply_windows(filt, not self.on.get() if bypass is None else bypass)
        preamp = loudness_gain(filt)
        os.makedirs(os.path.dirname(PRESET_PATH), exist_ok=True)
        with open(PRESET_PATH, 'w') as fh:
            json.dump(ee_preset(filt, preamp, not self.on.get() if bypass is None else bypass), fh, indent=2)
        self.save_state()
        try:
            if subprocess.run(['pgrep', '-x', 'easyeffects'], capture_output=True).returncode:
                subprocess.Popen(['easyeffects', '--gapplication-service'], start_new_session=True,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                self.root.after(2500, self.apply)  # give the service time to start, then load
                self.status.config(text='Starting EasyEffects…')
                return True
            subprocess.run(['easyeffects', '-l', PRESET], timeout=10, capture_output=True)
            self.status.config(text=f'{self.done_msg or "Applied"} · loudness matched ({preamp:+.1f} dB) · limiter on')
            self.done_msg = ''
        except (OSError, subprocess.TimeoutExpired) as e:
            self.status.config(text=f'Could not reach EasyEffects: {e}')

    def apply_windows(self, filt, bypass):
        self.save_state()
        cfg = apo_config_dir()
        if not cfg:
            self.status.config(text='Install Equalizer APO (free) to apply the EQ, then restart Speaker EQ')
        elif write_apo_live(cfg, filt, bypass):
            # no limiter in Equalizer APO, so keep the safe preamp (-largest boost) to avoid clipping
            self.status.config(text=f'{self.done_msg or "Applied"} · Equalizer APO')
            self.done_msg = ''
        elif not getattr(self, 'apo_setup_asked', False):
            self.apo_setup_asked = True
            setup_apo(cfg)
            self.status.config(text='Allow the Windows prompt (one time) so Speaker EQ can use Equalizer APO…')
            self.root.after(5000, self.apply)
        else:
            self.status.config(text='No permission for Equalizer APO config: accept the Windows prompt, '
                                    'or run Speaker EQ once as administrator')

    def pick_cal(self):
        path = filedialog.askopenfilename(title='Microphone calibration file (freq dB per line)')
        if path:
            self.st['mic_cal'] = path
            self.status.config(text=f'Mic calibration: {os.path.basename(path)}')
            self.save_state()

    def reset(self):
        self.amount.set(100)
        for v in self.vars.values():
            v.set(0)

    def import_eq(self, path=None):
        """Load an EQ file (Speaker EQ Export from any device, AutoEQ, Equalizer APO) as the auto-tune."""
        path = path or filedialog.askopenfilename(title='Import EQ file',
                                                  filetypes=[('EQ files', '*.txt'), ('All files', '*')])
        if not path:
            return
        try:
            with open(path, encoding='utf-8', errors='replace') as fh:
                filt = se.read_apo(fh.read(100_000))
        except OSError as e:
            self.status.config(text=f'Could not read {os.path.basename(path)}: {e.strerror}')
            return
        if not filt:
            self.status.config(text=f'No EQ filters found in {os.path.basename(path)}')
            return
        self.st['auto'], self.st['dev'] = [list(f) for f in filt], None
        self.st['diag'] = f'Imported {len(filt)} filters from {os.path.basename(path)}.\nThe Tone sliders still work on top.'
        self.say(self.st['diag'])
        self.done_msg = f'Imported {len(filt)} filters'
        self.amount.set(100)
        self.auto_on.set(True)  # traces call changed() -> redraw + apply
        self.changed()

    def export(self):
        path = filedialog.asksaveasfilename(defaultextension='.txt', initialfile='SpeakerEQ_APO.txt')
        if path:
            se.write_apo(path, self.filters())
            self.status.config(text=f'Exported {os.path.basename(path)}')

    def send_tv(self):
        """Find Speaker EQ on TVs/phones on the Wi-Fi and send the current EQ there."""
        w = tk.Toplevel(self.root, bg=BG, padx=20, pady=16)
        w.title('Send to TV')
        w.transient(self.root)
        tk.Label(w, text='Send EQ to a TV', font=font(16, 'bold'), bg=BG, fg=TEXT).pack(anchor='w')
        info = tk.Label(w, text='Searching your Wi-Fi…', font=font(10), bg=BG, fg=SUB, justify='left')
        info.pack(anchor='w', pady=(2, 8))
        lst = tk.Listbox(w, height=4, width=42, font=font(11), relief='flat', highlightthickness=1,
                         highlightcolor=BLUE, selectbackground=BLUE, activestyle='none')
        lst.pack(fill='x')
        addrs = []
        tk.Label(w, text='Address', font=font(10), bg=BG, fg=SUB).pack(anchor='w', pady=(10, 0))
        addr = tk.Entry(w, font=font(12), relief='flat', highlightthickness=1)
        addr.pack(fill='x')
        tk.Label(w, text='Code shown on the TV', font=font(10), bg=BG, fg=SUB).pack(anchor='w', pady=(8, 0))
        code = tk.Entry(w, font=font(16, 'bold'), width=6, relief='flat', highlightthickness=1)
        code.pack(anchor='w')
        result = tk.Label(w, text='', font=font(11), bg=BG, fg=TEXT, wraplength=380, justify='left')
        result.pack(anchor='w', pady=(10, 4))

        def pick(_e=None):
            if lst.curselection():
                addr.delete(0, 'end')
                addr.insert(0, addrs[lst.curselection()[0]])
        lst.bind('<<ListboxSelect>>', pick)

        def found(devs):
            if not w.winfo_exists():
                return
            for a, name in devs.items():
                addrs.append(a)
                lst.insert('end', f'{name}   ({a})')
            info.config(text='Found on your Wi-Fi:' if devs else
                        'No TV found. Open Speaker EQ on the TV, or type the address shown on its Wireless import card.')
            if devs and not addr.get():
                lst.selection_set(0)
                pick()
        box = {}  # filled by the worker; Tk may only be touched from the main thread, so poll it
        threading.Thread(target=lambda: box.update(devs=discover_tvs()), daemon=True).start()

        def wait_found():
            if w.winfo_exists():
                found(box['devs']) if 'devs' in box else w.after(200, wait_found)
        w.after(200, wait_found)

        def send():
            if not addr.get().strip() or not code.get().strip():
                result.config(text='Enter the address and the 4-digit code shown on the TV.')
                return
            with tempfile.TemporaryDirectory() as d:  # same text as Export…
                se.write_apo(os.path.join(d, 'eq.txt'), self.filters())
                text = open(os.path.join(d, 'eq.txt')).read()
            result.config(text='Sending…')
            a, c, sent = addr.get(), code.get(), {}
            threading.Thread(target=lambda: sent.update(r=send_to_tv(a, c, text)), daemon=True).start()

            def wait_sent():
                if w.winfo_exists():
                    result.config(text=sent['r']) if 'r' in sent else w.after(200, wait_sent)
            w.after(200, wait_sent)
        Pill(w, 'Send', send).pack(anchor='e', pady=(6, 0))
        code.bind('<Return>', lambda _e: send())

    def measure(self):
        how = ('Keep moving the laptop slowly around where you sit.' if self.moving.get() else
               'Keep the laptop and speaker completely still.')
        if not messagebox.askokcancel('Auto-Tune', 'The speaker will play test sweeps for about a minute.\n'
                                      f'Quiet room, normal listening volume. {how}'):
            return
        for p in se.preflight():
            if not messagebox.askyesno('Check your setup', p + '\n\nMeasure anyway?'):
                return
        self.measuring = True
        starting = self.apply(bypass=True)  # measure the bare speaker, not our own EQ
        self.btn.set_enabled(False, 'Measuring…')
        self.result = None

        def go():
            self.status.config(text='Measuring… ' + ('keep moving the laptop slowly' if self.moving.get()
                                                       else 'keep everything still'))
            threading.Thread(target=self._measure, daemon=True).start()
            self.root.after(500, self._poll)
        self.root.after(4000 if starting else 0, go)  # let EasyEffects finish starting before the sweeps

    def _measure(self):
        os.makedirs(DATA, exist_ok=True)
        moving = self.moving.get()  # still: 3 sweeps average noise; moving: 5 average the listening area
        a = argparse.Namespace(positions=5 if moving else 3, fixed=not moving, walk=True, mic_cal=self.st['mic_cal'], tilt=-0.8, max_boost=6.0,
                               filters=10, out=os.path.join(DATA, 'ParametricEQ.txt'),
                               plot=os.path.join(DATA, 'response.png'), dsp_check=True)
        buf = io.StringIO()
        try:
            with contextlib.redirect_stdout(buf):
                filt, _resid, _thd, _ok, dev = se.measure(a)
            self.result = (True, [list(map(float, f)) for f in filt], dev.tolist(), buf.getvalue())
        except SystemExit as e:  # speaker_eq exits with a message on clipping / too quiet
            self.result = (False, None, None, buf.getvalue() + f'\n{e}')
        except Exception as e:
            self.result = (False, None, None, buf.getvalue() + f'\nMeasurement failed: {e!r}')

    def _poll(self):
        if self.result is None:
            self.root.after(500, self._poll)
            return
        ok, filt, dev, text = self.result
        self.measuring = False
        self.btn.set_enabled(True)
        i = text.find('Usable range')  # findings first; the measuring log only matters on failure
        self.say((text[i:] if ok and i >= 0 else text).strip())
        if ok:
            self.st['diag'] = self.log.get('1.0', 'end').strip()  # shown again next launch
        if ok:
            self.st['auto'], self.st['dev'] = filt, dev
            self.done_msg = 'Auto-tune done, EQ applied'
            if self.auto_on.get():
                self.changed()
            else:
                self.auto_on.set(True)  # trace calls changed()
        else:
            self.status.config(text='Measurement failed, see Diagnosis')


def main():
    if '--selftest' in sys.argv:  # lets CI check the bundled Windows exe end to end (exit code)
        try:
            se.selftest()
        except Exception:
            sys.exit(1)  # no console in the windowed exe: report through the exit code only
        return
    if se.WIN:
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)  # sharp text on high-DPI screens
        except (AttributeError, OSError):
            pass  # older Windows: blurry but works
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == '__main__':
    main()
