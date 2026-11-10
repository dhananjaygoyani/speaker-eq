#!/usr/bin/env python3
"""Auto-EQ a speaker with a microphone.

noise floor + sine sweeps -> impulse responses -> response, SNR, distortion, level-dependent DSP
-> diagnosis -> parametric EQ.  Output is an Equalizer APO / AutoEQ "ParametricEQ.txt" file
(importable in EasyEffects, Equalizer APO, Wavelet, Peace, etc.).
Run with --selftest to check the pipeline offline.
"""
import argparse, os, signal, subprocess, sys, tempfile, time
import numpy as np
from scipy.io import wavfile
from scipy.optimize import least_squares
from scipy.signal import butter, find_peaks, freqz, lfilter

FS = 48000
WIN = sys.platform == 'win32'
F1, F2, T = 20, 20000, 6.0
GRID = np.geomspace(20, 20000, 400)  # 40 points per octave
MIN_SNR = 15    # dB; below this the measurement is noise, don't EQ it
MAX_THD = 0.03  # 3 %; above this the speaker is already straining, never boost there

# (lo, hi, name, if too loud, if too quiet)
BANDS = [(20, 60, 'sub-bass', 'rumbly', 'no deep bass'),
         (60, 250, 'bass', 'boomy', 'thin'),
         (250, 500, 'low-mids', 'muddy/boxy', 'hollow'),
         (500, 2000, 'mids', 'honky/nasal', 'recessed vocals'),
         (2000, 6000, 'presence', 'harsh/shouty', 'dull/veiled'),
         (6000, 20000, 'treble', 'sibilant/bright', 'dark/no air')]


def ms(t):
    return int(t * FS / 1000)


def sweep():
    """Exponential sine sweep (Farina 2000): harmonics land before the main impulse, so one sweep
    gives both the linear response and distortion."""
    t = np.arange(int(T * FS)) / FS
    R = np.log(F2 / F1)
    s = np.sin(2 * np.pi * F1 * T / R * (np.exp(t * R / T) - 1))
    n = ms(50)
    s[:n] *= np.hanning(2 * n)[:n]
    s[-n:] *= np.hanning(2 * n)[n:]
    return 0.5 * s  # -6 dBFS


def play_record(sig):
    """Play through the default output while recording the default input (PipeWire; PortAudio on Windows)."""
    if WIN:
        import sounddevice as sd
        out = np.concatenate([np.zeros(ms(800)), sig, np.zeros(ms(1300))])  # same lead-in/tail as below
        y = sd.playrec(out.astype(np.float32), samplerate=FS, channels=1, blocking=True)
        return np.asarray(y, dtype=float)[ms(300):, 0]  # drop the mic start-up click
    with tempfile.TemporaryDirectory() as d:
        src, dst = os.path.join(d, 'sweep.wav'), os.path.join(d, 'rec.wav')
        pad = np.zeros(ms(300))
        wavfile.write(src, FS, (np.concatenate([pad, sig, pad]) * 32767).astype(np.int16))
        rec = subprocess.Popen(['pw-record', '--rate', str(FS), '--channels', '1', '--format', 's16', dst])
        time.sleep(0.5)
        subprocess.run(['pw-play', src], check=True)
        time.sleep(1.0)  # Bluetooth adds 100-300 ms latency; keep recording past it
        rec.send_signal(signal.SIGINT)
        rec.wait(5)
        _, y = wavfile.read(dst)
    y = y.astype(float)[ms(300):] / 32768  # drop the mic start-up click; playback starts 500 ms in
    return y.mean(1) if y.ndim > 1 else y


def smooth(f, power, grid):
    """Frequency-dependent fractional-octave power average (Trueplay / psychoacoustic style):
    1/24 oct below 100 Hz (keep bass detail), 1/6 at 1 kHz, 1/3 above 10 kHz (ignore comb dips)."""
    n = np.interp(np.log(grid), np.log([100, 1000, 10000]), [24, 6, 3])
    c = np.concatenate([[0], np.cumsum(power)])
    lo = np.minimum(np.searchsorted(f, grid * 2 ** (-1 / (2 * n))), len(f) - 1)
    hi = np.maximum(np.searchsorted(f, grid * 2 ** (1 / (2 * n))), lo + 1)
    return (c[hi] - c[lo]) / (hi - lo)


def spectrum(h, start, length, grid):
    seg = np.roll(h, -start)[:length].copy()
    t = length // 5
    seg[-t:] *= np.hanning(2 * t)[t:]
    m = 1 << 16
    return smooth(np.fft.rfftfreq(m, 1 / FS), np.abs(np.fft.rfft(seg, m)) ** 2, grid)


def response(x, y):
    """Deconvolve recording y by sweep x. Returns (power on GRID, THD ratio on GRID)."""
    n = 1 << int(np.ceil(np.log2(len(x) + len(y))))
    X, Y = np.fft.rfft(x, n), np.fft.rfft(y, n)
    h = np.fft.irfft(Y * np.conj(X) / (np.abs(X) ** 2 + 1e-6 * np.max(np.abs(X) ** 2)), n)
    p = np.argmax(np.abs(h))  # latency (incl. Bluetooth) is found here automatically
    power = spectrum(h, p - ms(5), ms(205), GRID)
    # harmonic k sits T*ln(k)/ln(F2/F1) s before the main peak; its spectrum at k*f is distortion at f
    short = spectrum(h, p - ms(5), ms(55), GRID)
    harm = 0
    for k in (2, 3):
        d = int(T * np.log(k) / np.log(F2 / F1) * FS)
        hk = spectrum(h, p - d - ms(5), ms(55), GRID * k)
        harm = harm + np.where(GRID * k < F2, hk, 0)
    # 1/3-oct averaged ratio: a raw ratio explodes inside room-cancellation notches (fundamental ~ 0)
    return power, np.sqrt(oct3(harm) / (oct3(short) + 1e-30))


def oct3(v, n=13):
    """Moving average on GRID, edge-padded: 13 points ~ 1/3 octave, 21 ~ 1/2 octave."""
    return np.convolve(np.pad(v, n // 2, mode='edge'), np.ones(n) / n, mode='valid')


def load_cal(path):
    """Mic calibration file (UMIK-1 / REW style: 'freq dB' lines, headers ignored)."""
    rows = []
    for line in open(path):
        parts = line.replace(',', ' ').split()
        try:
            rows.append((float(parts[0]), float(parts[1])))
        except (ValueError, IndexError):
            pass
    f, db = np.array(rows).T
    return np.interp(np.log(GRID), np.log(f), db)


def biquad(f0, g, q, kind='PK'):
    """RBJ-cookbook biquad (b, a): PK peaking, LSC low shelf, HSC high shelf (Equalizer APO types)."""
    A, w = 10 ** (g / 40), 2 * np.pi * f0 / FS
    al, c = np.sin(w) / (2 * q), np.cos(w)
    if kind == 'PK':
        return [1 + al * A, -2 * c, 1 - al * A], [1 + al / A, -2 * c, 1 - al / A]
    s, r = (1 if kind == 'LSC' else -1), 2 * np.sqrt(A) * al
    return ([A * ((A + 1) - s * (A - 1) * c + r), s * 2 * A * ((A - 1) - s * (A + 1) * c),
             A * ((A + 1) - s * (A - 1) * c - r)],
            [(A + 1) + s * (A - 1) * c + r, -s * 2 * ((A - 1) + s * (A + 1) * c), (A + 1) + s * (A - 1) * c - r])


def peq_db(params):
    """Summed dB response of filters [(f, gain, Q[, kind]), ...] on GRID."""
    out = np.zeros_like(GRID)
    for f0, g, q, *kind in params:
        _, h = freqz(*biquad(f0, g, q, *kind), worN=GRID, fs=FS)
        out += 20 * np.log10(np.abs(h))
    return out


def read_apo(text):
    """Filters [(f, gain, Q, kind)] from an Equalizer APO / AutoEQ file (Speaker EQ's own Export, AutoEQ, ...).
    Preamp lines are ignored: the app sets its own level. Untrusted input: only sane values are kept."""
    import re
    out = []
    for kind, f, g, q in re.findall(r'Filter\s*\d*\s*:\s*ON\s+(PK|LSC|HSC)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB'
                                    r'\s+Q\s+([\d.]+)', text, re.IGNORECASE):
        f, g, q = float(f), float(g), float(q)
        if 10 <= f <= 24000 and q > 0 and -30 <= g <= 30:
            out.append((f, g, q, kind.upper()))
    return out


def write_apo(path, filters):
    """Equalizer APO / AutoEQ text, with a preamp so the EQ never clips."""
    preamp = -np.ceil(max(0, peq_db(filters).max()) * 10) / 10 + 0.0  # + 0.0 turns -0.0 into 0.0
    with open(path, 'w') as fh:
        fh.write(f'Preamp: {preamp:.1f} dB\n')
        for i, (f, g, q, *kind) in enumerate(filters, 1):
            fh.write(f'Filter {i}: ON {(kind or ["PK"])[0]} Fc {f:.0f} Hz Gain {g:.1f} dB Q {q:.2f}\n')
    return preamp


def usable_range(db, ref):
    """Where the speaker actually produces sound. EQ outside it just adds distortion."""
    s = np.convolve(db, np.ones(21) / 21, mode='same')  # ~1/2 oct, so one room dip isn't a 'cutoff'
    i = np.searchsorted(GRID, 300)
    below = np.nonzero(s[:i] < ref - 6)[0]
    f_low = GRID[below[-1] + 1] if below.size else GRID[0]
    i = np.searchsorted(GRID, 3000)
    above = np.nonzero(s[i:] < ref - 10)[0]
    f_high = GRID[i + above[0] - 1] if above.size else GRID[-1]
    return f_low, min(f_high, 16000)


def fit(desired, inr, cap, max_boost, n_max):
    """Ideal correction curve -> PEQ: greedy peak picking, then joint least-squares refinement
    (same idea as Trueplay's FIR->16-band fit and AutoEQ's optimizer)."""
    p = []
    for _ in range(n_max):
        r = np.where(inr, desired - peq_db(p), 0)
        i = np.argmax(np.abs(r))
        if abs(r[i]) < 1:
            break
        s, j, k = np.sign(r[i]), i, i
        while j > 0 and s * r[j - 1] > abs(r[i]) / 2: j -= 1
        while k < len(r) - 1 and s * r[k + 1] > abs(r[i]) / 2: k += 1
        bw = max(np.log2(GRID[k] / GRID[j]), 1 / 6)
        p.append((GRID[i], r[i], np.sqrt(2 ** bw) / (2 ** bw - 1)))
    if not p:
        return []
    fr = GRID[inr]
    lo = np.tile([np.log(fr[0]), -12, 0.5], len(p))
    hi = np.tile([np.log(fr[-1]), max_boost, 6], len(p))
    x0 = np.clip(np.array([(np.log(f), g, q) for f, g, q in p]).ravel(), lo + 1e-6, hi - 1e-6)
    w = np.where(inr, 1.0, 0.3)  # outside the range: keep EQ near 0 dB
    unpack = lambda x: [(np.exp(x[i]), x[i + 1], x[i + 2]) for i in range(0, len(x), 3)]
    def resid(x):
        eq = peq_db(unpack(x))  # 2nd term: per-frequency boost cap (e.g. 0 dB where it already distorts)
        # last term: small gain penalty, so it never stacks opposing filters (+6/-11 dB at one frequency)
        return np.concatenate([w * (eq - desired), 3 * np.maximum(0, eq - cap) * inr, 0.5 * x[1::3]])
    x = least_squares(resid, x0, bounds=(lo, hi)).x
    return [f for f in unpack(x) if abs(f[1]) >= 0.3]


def band_of(f):
    return next(b for b in BANDS if b[0] <= f < b[1] or b is BANDS[-1])


def diagnose(dev, inr, f_low, f_high, thd, ok, dsp_diff):
    print(f'\nUsable range: {f_low:.0f} Hz - {f_high:.0f} Hz')
    if f_low > 80:
        print(f'  Bass rolls off below {f_low:.0f} Hz. That is the driver/box size; EQ will not boost below it.')
    if (~ok & (GRID < 300)).any():
        print(f'  Room noise masks the sweep below {GRID[~ok & (GRID < 300)].max():.0f} Hz; not EQed there '
              f'(quieter room or louder volume helps).')
    for lo, hi, name, more, less in BANDS:
        m = inr & (GRID >= lo) & (GRID < hi)
        if m.sum() < 5:
            continue
        d = dev[m].mean()
        if abs(d) >= 2:
            print(f'  {name:9s} {d:+5.1f} dB vs target -> {more if d > 0 else less}')
    d, f = dev[inr], GRID[inr]  # only inside the range, so the rolloff edge isn't reported as a dip
    for sign, word in ((1, 'peak'), (-1, 'dip')):
        for i in find_peaks(sign * d, prominence=3)[0]:
            if sign * d[i] < 3:
                continue  # a local minimum that is still above target isn't a dip worth reporting
            b = band_of(f[i])
            note = b[3] if sign > 0 else 'dips only partly fixable; often room/mic position'
            print(f'  {word} {d[i]:+5.1f} dB at {f[i]:6.0f} Hz ({b[2]}: {note})')
    bad = inr & ok & (thd > MAX_THD) & (GRID < 10000)  # below the usable range distortion is irrelevant
    if bad.any():
        i = np.argmax(np.where(bad, thd, 0))
        print(f'  Distortion: {thd[i] * 100:.0f}% at {GRID[i]:.0f} Hz (over {MAX_THD * 100:.0f}% in parts of {GRID[bad].min():.0f}-{GRID[bad].max():.0f} Hz)'
              f'. No boost there; turn volume down if it sounds rough.')
    if dsp_diff is not None and np.abs(dsp_diff).max() >= 3:
        i = np.argmax(np.abs(dsp_diff))
        print(f'  Speaker DSP changes tone with level ({dsp_diff[i]:+.1f} dB at {GRID[i]:.0f} Hz between loud and '
              f'quiet): dynamic bass/limiter. This EQ is right only at the volume you measured.')


def clean_silence(y):
    """Silence should be quiet and centred; a railing laptop mic (too much gain) fails this."""
    return np.abs(y).max() < 0.5 and abs(y.mean()) < 0.05


class Clipped(Exception):
    pass


def measure(args):
    """Real-hardware run: pick a clean mic gain, measure, and if the sweep clips, lower the gain and retry.
    The user's mic volume is restored afterwards."""
    if WIN:  # shortcut: no mic-volume control on Windows (would need pycaw/COM); the user lowers it instead
        if not clean_silence(play_record(np.zeros(FS))):
            sys.exit('Microphone records noise/garbage: lower the microphone level in Windows Sound settings.')
        try:
            return run(play_record, args)
        except Clipped:
            sys.exit('Recording clipped: lower the microphone level in Windows Sound settings, or the speaker '
                     'volume, and try again.')
    get = lambda: float(subprocess.run(['wpctl', 'get-volume', '@DEFAULT_AUDIO_SOURCE@'], capture_output=True,
                                       text=True, timeout=5).stdout.split()[1])
    setv = lambda v: subprocess.run(['wpctl', 'set-volume', '@DEFAULT_AUDIO_SOURCE@', f'{v:.2f}'], timeout=5)
    orig = v = get()
    try:
        # many laptop mics (e.g. AMD HDA + 30 dB boost) rail even in silence at high gain
        while not clean_silence(play_record(np.zeros(FS))):
            if v <= 0.05:
                sys.exit('Microphone records noise/garbage even at 5% volume: check the input device in Settings.')
            v = max(0.05, v * 0.6)
            setv(v)
        while True:
            if v != orig:
                print(f'Mic input volume set to {v:.0%} for this measurement (yours: {orig:.0%}, restored after).')
            try:
                return run(play_record, args)
            except Clipped:
                if v <= 0.05:
                    sys.exit('Recording clipped even at 5% mic volume: turn the speaker down or move the mic away.')
                v = max(0.05, v * 0.6)
                setv(v)
                print('Recording clipped: lowering mic volume and measuring again...')
    finally:
        setv(orig)


def run(play_rec, args):
    x = sweep()
    print('Measuring room noise: stay quiet...')
    y0 = play_rec(np.zeros_like(x))
    if np.abs(y0).max() > 0.98:
        sys.exit('Microphone is saturated even in silence: lower the input (mic) volume to ~30%.')
    noise, _ = response(x, y0)
    powers, thds = [], []
    for k in range(args.positions):
        if k and not args.walk:
            input(f'Move the mic ~10-20 cm and press Enter for measurement {k + 1}/{args.positions}... ')
        y = play_rec(x)
        peak = np.abs(y).max()
        if peak > 0.98:
            raise Clipped
        if peak < 0.01:
            sys.exit('Recording too quiet: raise the volume, check the speaker/mic are the default devices.')
        p, t = response(x, y)
        powers.append(p)
        thds.append(t)
    P = np.mean(powers, 0)  # spatial power average (Trueplay/Dirac/Audyssey all average positions)
    thd = np.mean(thds, 0)
    ok = 10 * np.log10(P / (noise + 1e-30)) > MIN_SNR
    db = 10 * np.log10(P + 1e-30)
    if args.mic_cal:
        db -= load_cal(args.mic_cal)

    tilt = args.tilt * np.log2(GRID / 1000)
    mid = (GRID >= 300) & (GRID <= 3000)
    ref = (db - tilt)[mid].mean()
    f_low, f_high = usable_range(db, ref)
    inr = (GRID >= f_low) & (GRID <= f_high) & ok
    target = ref + tilt
    dev = db - target

    dsp_diff = None
    if args.dsp_check:  # same sweep 12 dB quieter: a fixed speaker scales evenly, a DSP one changes shape
        q, _ = response(x, play_rec(0.25 * x))
        dq = 10 * np.log10(q + 1e-30)
        both = inr & (10 * np.log10(q / (noise + 1e-30)) > MIN_SNR)
        dsp_diff = np.where(both, oct3((db - ref) - (dq - (dq - tilt)[mid].mean())), 0)  # mic cal cancels out

    dev_s = oct3(dev)  # decisions use 1/3-oct detail: narrower wiggles are room/position, not the speaker
    if getattr(args, 'fixed', False):
        # one mic spot: below ~500 Hz the room dominates and changes with position, so use 1/2-oct smoothing
        # and correct 70% of it (Trueplay doesn't fully cut room peaks either); blend in over 300-600 Hz
        a = np.clip(np.log2(GRID / 300), 0, 1)
        dev_s = a * dev_s + (1 - a) * 0.7 * oct3(dev, 21)
    diagnose(dev_s, inr, f_low, f_high, thd, ok, dsp_diff)
    # cut freely, boost little: boosting nulls/rolloff/distorting regions wastes headroom
    cap = np.where(ok & (thd > MAX_THD), 0, args.max_boost)
    desired = np.where(inr, np.clip(-dev_s, -12, cap), 0)
    if not args.mic_cal:  # uncalibrated phone/laptop mics are unreliable up top; don't trust big treble moves
        desired = np.where(GRID > 5000, np.clip(desired, -3, 3), desired)
        print('  No mic calibration: corrections above 5 kHz limited to +/-3 dB.')
    filters = fit(desired, inr, cap, args.max_boost, args.filters)
    eq = peq_db(filters)
    resid = (dev + eq)[inr]
    print(f'\nDeviation from target: {np.sqrt(np.mean(dev[inr] ** 2)):.1f} dB RMS before, '
          f'{np.sqrt(np.mean(resid ** 2)):.1f} dB RMS after (predicted)')

    filters = sorted(filters)
    write_apo(args.out, filters)
    print(f'Wrote {len(filters)} filters to {args.out}')
    print(open(args.out).read())

    if args.plot:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        fig, (a1, a2) = plt.subplots(2, 1, figsize=(10, 7), sharex=True, height_ratios=[3, 1])
        a1.semilogx(GRID, db - ref, label='measured')
        a1.semilogx(GRID, target - ref, 'k--', label='target')
        a1.semilogx(GRID, db - ref + eq, label='with EQ (predicted)')
        a1.semilogx(GRID, eq, label='EQ curve')
        a1.semilogx(GRID, 10 * np.log10(noise + 1e-30) - ref, ':', color='gray', label='noise floor')
        a1.axvspan(f_low, f_high, color='g', alpha=0.05)
        a1.set_ylim(-40, 15); a1.set_ylabel('dB'); a1.legend(); a1.grid(which='both', alpha=0.3)
        a2.semilogx(GRID, np.where(ok, thd * 100, np.nan)); a2.axhline(MAX_THD * 100, color='r', ls='--')
        a2.set_ylabel('THD %'); a2.set_ylim(0, 10); a2.set_xlabel('Hz'); a2.grid(which='both', alpha=0.3)
        a2.set_xlim(20, 20000)
        fig.savefig(args.plot, dpi=110)
        print(f'Plot saved to {args.plot}')
    return filters, resid, thd, ok, dev


def selftest():
    """Simulated small speaker: 120 Hz high-pass, +6 dB @ 800 Hz, -5 dB @ 3 kHz, latency, noise.
    Second pass adds cubic distortion, which the THD measurement must catch."""
    true = [(800, 6, 2), (3000, -5, 1.5)]
    rng = np.random.default_rng(0)

    def make(nl):
        def fake(x):
            y = lfilter(*butter(2, 120, 'high', fs=FS), x)
            for f0, g, q in true:
                y = lfilter(*biquad(f0, g, q), y)
            y = 0.3 * y + nl * y ** 3
            y = np.concatenate([np.zeros(ms(250)), y, np.zeros(FS)])
            return y + 1e-4 * rng.standard_normal(len(y))
        return fake

    out = os.path.join(tempfile.gettempdir(), 'speaker_eq_selftest.txt')
    a = argparse.Namespace(positions=1, walk=True, mic_cal=None, tilt=0.0, max_boost=6.0, filters=10,
                           out=out, plot=None, dsp_check=True)
    filters, resid, thd, ok, _ = run(make(0), a)
    assert np.sqrt(np.mean(resid ** 2)) < 1.0, 'residual too large'
    assert any(600 < f < 1000 and g < -3 for f, g, q in filters), 'missed the 800 Hz peak'
    assert any(2000 < f < 4500 and g > 2 for f, g, q in filters), 'missed the 3 kHz dip'
    mids = ok & (GRID > 300) & (GRID < 3000)
    assert np.median(thd[mids]) < 0.01, f'linear speaker shows THD {np.median(thd[mids]):.3f}'
    _, _, thd2, ok2, _ = run(make(0.2), a)
    mids = ok2 & (GRID > 300) & (GRID < 3000)
    assert np.median(thd2[mids]) > 0.02, f'missed distortion, THD {np.median(thd2[mids]):.3f}'
    lo, hi = peq_db([(100, 6, 0.71, 'LSC')]), peq_db([(8000, 6, 0.71, 'HSC')])
    assert abs(lo[0] - 6) < 0.3 and abs(lo[-1]) < 0.3 and abs(hi[0]) < 0.3 and abs(hi[-1] - 6) < 0.5, 'shelf filters'
    print('SELFTEST OK')


def preflight():
    """Problems that would make a measurement fail or measure the wrong device. [] means ready."""
    import shutil
    if WIN:
        try:
            import sounddevice as sd
            sd.query_devices(kind='output'), sd.query_devices(kind='input')
        except Exception as e:  # PortAudio raises its own error types
            return [f'No usable speaker or microphone found ({e}).']
        return []
    missing = [t for t in ('pw-play', 'pw-record', 'wpctl') if not shutil.which(t)]
    if missing:
        return [f'Missing {", ".join(missing)} (install pipewire-bin / wireplumber).']
    try:
        text = subprocess.run(['wpctl', 'inspect', '@DEFAULT_AUDIO_SINK@'], capture_output=True,
                              text=True, timeout=5).stdout
    except subprocess.TimeoutExpired:
        return ['PipeWire is not responding.']
    name, desc = parse_sink(text)
    if not name:
        return ['No audio output device found.']
    if not name.startswith(('bluez', 'easyeffects')):  # easyeffects_sink wraps the real device
        return [f'Default output is "{desc}", not a Bluetooth speaker. Connect the speaker and make it '
                f'the default output, or continue to measure this device.']
    return []


def parse_sink(text):
    """(node.name, node.description) from `wpctl inspect` output."""
    get = lambda k: next((l.split('=', 1)[1].strip().strip('"') for l in text.splitlines()
                          if l.strip().lstrip('* ').startswith(k + ' ')), '')
    return get('node.name'), get('node.description') or get('node.name')


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--positions', type=int, default=5, help='mic positions to average (default 5)')
    ap.add_argument('--walk', action='store_true', help='no prompts between sweeps; keep moving the mic slowly')
    ap.add_argument('--fixed', action='store_true', help='mic and speaker stay still: gentler, position-safe bass')
    ap.add_argument('--mic-cal', help='microphone calibration file (freq dB per line)')
    ap.add_argument('--tilt', type=float, default=-0.8, help='target slope dB/octave (default -0.8, in-room)')
    ap.add_argument('--max-boost', type=float, default=6.0, help='largest boost per filter in dB (default 6)')
    ap.add_argument('--filters', type=int, default=10, help='max number of PEQ filters (default 10)')
    ap.add_argument('--no-dsp-check', dest='dsp_check', action='store_false',
                    help='skip the quiet sweep that detects level-dependent speaker DSP')
    ap.add_argument('--out', default='ParametricEQ.txt')
    ap.add_argument('--plot', default='response.png', help='PNG path, "" to skip')
    ap.add_argument('--selftest', action='store_true')
    a = ap.parse_args()
    if a.selftest:
        return selftest()
    for p in preflight():
        print('Warning:', p)
    measure(a)


if __name__ == '__main__':
    main()
