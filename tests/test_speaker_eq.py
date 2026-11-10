import json, os, sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import speaker_eq as se


def test_pipeline_selftest():
    se.selftest()  # simulated speaker: peaks/dips found, distortion detected, shelves correct


def test_parse_sink():
    text = ('id 70, type PipeWire:Interface:Node\n'
            '  * node.description = "JBL Flip 5"\n'
            '  * node.name = "bluez_output.AA_BB_CC_DD_EE_FF.1"\n')
    assert se.parse_sink(text) == ('bluez_output.AA_BB_CC_DD_EE_FF.1', 'JBL Flip 5')
    assert se.parse_sink('') == ('', '')


def test_apo_export(tmp_path):
    p = tmp_path / 'eq.txt'
    pre = se.write_apo(p, [(100, 4.0, 0.71, 'LSC'), (800, -3.0, 2.0)])
    lines = p.read_text().splitlines()
    assert pre <= -3.9 and lines[1].startswith('Filter 1: ON LSC Fc 100 Hz Gain 4.0') and ' PK ' in lines[2]


@pytest.mark.skipif(not os.environ.get('DISPLAY'), reason='needs a display')
def test_gui_combines_auto_and_sliders(tmp_path, monkeypatch):
    import tkinter as tk
    import speaker_eq_gui as g
    monkeypatch.setattr(g, 'STATE', str(tmp_path / 's.json'))
    monkeypatch.setattr(g, 'OLD_STATE', str(tmp_path / 'none.json'))  # ignore the developer's real settings
    root = tk.Tk()
    app = g.App(root)
    app.apply = lambda bypass=None: None  # don't touch the real EasyEffects
    app.st['auto'] = [[800, -6.0, 2.0]]
    app.amount.set(50)
    app.vars['Bass'].set(4)
    f = app.filters()
    assert f[0] == (800, -3.0, 2.0, 'PK') and (100, 4.0, 0.71, 'LSC') in f
    app.auto_on.set(False)  # auto-tune OFF: only the tone sliders remain
    assert all(x[3] != 'PK' or x[0] != 800 for x in app.filters()) and (100, 4.0, 0.71, 'LSC') in app.filters()
    app.auto_on.set(True)
    f = app.filters()
    band = g.ee_preset(f, -4.0, False)['output']['equalizer#0']['left']['band1']
    assert band == {**band, 'type': 'Lo-shelf', 'mode': 'APO (DR)', 'gain': 4.0}
    app.save_state()
    assert json.load(open(g.STATE))['sliders']['Bass'] == 4.0
    root.destroy()


@pytest.mark.skipif(not os.environ.get('DISPLAY'), reason='needs a display')
def test_gui_mouse_events(tmp_path, monkeypatch):
    """Real Tk mouse events on the custom Switch and Slider widgets."""
    import tkinter as tk
    import speaker_eq_gui as g
    monkeypatch.setattr(g, 'STATE', str(tmp_path / 's.json'))
    monkeypatch.setattr(g, 'OLD_STATE', str(tmp_path / 'none.json'))  # ignore the developer's real settings
    root = tk.Tk()
    app = g.App(root)
    app.apply = lambda bypass=None: None
    root.geometry('1120x780')
    root.update()

    def find(cls, w=root):
        out = [w] if isinstance(w, cls) else []
        for c in w.winfo_children():
            out += find(cls, c)
        return out

    auto_switch = find(g.Switch)[0]
    auto_switch.event_generate('<Button-1>', x=10, y=10)
    assert app.auto_on.get() is False and app.amount_slider.enabled is False  # strength greys out
    auto_switch.event_generate('<Button-1>', x=10, y=10)
    assert app.auto_on.get() is True

    bass = find(g.Slider)[1]  # [0] is Strength
    w = bass.winfo_width()
    bass.event_generate('<Button-1>', x=w - 5, y=15)  # click at the far right
    assert app.vars['Bass'].get() == 12
    bass.event_generate('<B1-Motion>', x=w // 2, y=15)  # drag to the middle
    assert abs(app.vars['Bass'].get()) <= 0.5
    bass.event_generate('<Button-4>', x=w // 2, y=15)  # scroll up one step
    assert app.vars['Bass'].get() == round(app.vars['Bass'].get() * 2) / 2
    for _ in range(2):  # double-click resets (Tk can't synthesize <Double-...>, so send two quick clicks)
        bass.event_generate('<ButtonPress-1>', x=w - 5, y=15, when='now')
        bass.event_generate('<ButtonRelease-1>', x=w - 5, y=15, when='now')
    assert app.vars['Bass'].get() == 0
    root.destroy()


def test_fixed_mode_is_gentler_in_bass(tmp_path):
    """Still mic: a room-style bass peak gets a smaller cut than in moving mode; mids stay fully corrected."""
    import argparse
    import numpy as np
    from scipy.signal import lfilter
    rng = np.random.default_rng(0)

    def fake(x):
        y = x
        for f0, g, q in [(150, 8, 3), (2000, 6, 2)]:  # room-mode-like bass peak + a speaker mid peak
            y = lfilter(*se.biquad(f0, g, q), y)
        y = np.concatenate([np.zeros(se.ms(250)), 0.3 * y, np.zeros(se.FS)])
        return y + 1e-4 * rng.standard_normal(len(y))

    eq = {}
    for fixed in (False, True):
        a = argparse.Namespace(positions=1, walk=True, mic_cal=None, tilt=0.0, max_boost=6.0, filters=10,
                               out=str(tmp_path / 'eq.txt'), plot=None, dsp_check=False, fixed=fixed)
        eq[fixed] = se.peq_db(se.run(fake, a)[0])
    at = lambda f: np.searchsorted(se.GRID, f)
    assert eq[False][at(150)] < -5, 'moving mode should cut the 150 Hz peak hard'
    assert eq[True][at(150)] > eq[False][at(150)] + 1.5, 'still mode should cut the room peak less'
    assert abs(eq[True][at(2000)] - eq[False][at(2000)]) < 1.0, 'mids should be corrected the same'


def test_windows_equalizer_apo_files(tmp_path):
    """Equalizer APO output: needs the one-time include, then writes SpeakerEQ.txt; bypass = flat."""
    import speaker_eq_gui as g
    (tmp_path / 'config.txt').write_text('Preamp: -6 dB\nInclude: example.txt\n')
    filt = [(800, -4.0, 2.0, 'PK'), (100, 3.0, 0.71, 'LSC')]
    assert g.write_apo_live(str(tmp_path), filt, False) is False  # setup (admin) still needed
    with open(tmp_path / 'config.txt', 'a') as fh:
        fh.write('\nInclude: SpeakerEQ.txt\n')  # what setup_apo adds
    assert g.write_apo_live(str(tmp_path), filt, False) is True
    lines = (tmp_path / 'SpeakerEQ.txt').read_text().splitlines()
    assert lines[0].startswith('Preamp: -') and 'ON PK Fc 800 Hz Gain -4.0' in lines[1] and ' LSC ' in lines[2]
    assert g.write_apo_live(str(tmp_path), filt, True) is True
    assert (tmp_path / 'SpeakerEQ.txt').read_text().strip() == 'Preamp: 0.0 dB'


def test_windows_measurement_path(tmp_path, monkeypatch):
    """Windows audio path (sounddevice.playrec) end to end, with a fake sound card + simulated speaker."""
    import argparse, types
    import numpy as np
    from scipy.signal import lfilter
    rng = np.random.default_rng(0)

    def playrec(out, samplerate, channels, blocking):
        assert out.dtype == np.float32 and samplerate == se.FS and channels == 1 and blocking
        y = lfilter(*se.biquad(1000, 6, 2), out.astype(float))  # speaker with a +6 dB peak at 1 kHz
        y = np.concatenate([np.zeros(se.ms(150)), 0.3 * y])[:len(out)]  # 150 ms Bluetooth latency
        y[:se.ms(50)] = 1.0  # mic start-up click, must be discarded
        return (y + 1e-4 * rng.standard_normal(len(y)))[:, None]

    monkeypatch.setattr(se, 'WIN', True)
    monkeypatch.setitem(sys.modules, 'sounddevice', types.SimpleNamespace(
        playrec=playrec, query_devices=lambda kind: {'name': 'fake'}))
    assert se.preflight() == []
    a = argparse.Namespace(positions=1, walk=True, mic_cal=None, tilt=0.0, max_boost=6.0, filters=10,
                           out=str(tmp_path / 'eq.txt'), plot=None, dsp_check=True, fixed=True)
    eq = se.peq_db(se.measure(a)[0])
    at = lambda f: eq[np.searchsorted(se.GRID, f)]
    assert at(1000) - at(300) < -4 and at(1000) - at(3000) < -4, 'should cut the 1 kHz peak'


def test_mdns_reply_parsing():
    """Instance name from an mDNS PTR reply (with a compression pointer); junk and pointer loops are ignored."""
    import struct
    import speaker_eq_gui as g
    q = b''.join(bytes([len(x)]) + x for x in b'_speakereq._tcp.local'.split(b'.')) + b'\0'
    inst = bytes([len(b'Speaker EQ on AFTSSS')]) + b'Speaker EQ on AFTSSS' + b'\xc0\x0c'  # ...+ pointer to the query name
    reply = (struct.pack('>6H', 0, 0x8400, 1, 1, 0, 0) + q + struct.pack('>HH', 12, 1) +
             b'\xc0\x0c' + struct.pack('>HHIH', 12, 1, 120, len(inst)) + inst)
    assert g.mdns_instance(reply) == 'Speaker EQ on AFTSSS'
    assert g.mdns_instance(b'\x00' * 5) is None
    loop = struct.pack('>6H', 0, 0x8400, 1, 0, 0, 0) + b'\xc0\x0c'  # name pointing at itself
    assert g.mdns_instance(loop) is None


def test_send_to_tv(tmp_path):
    """Send to TV posts the EQ text with the code; unreachable devices give a readable message."""
    import http.server, threading
    import speaker_eq_gui as g
    got = {}

    class H(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            got['path'] = self.path
            got['body'] = self.rfile.read(int(self.headers['Content-Length'])).decode()
            self.send_response(200 if 'code=1234' in self.path else 403)
            self.end_headers()
            self.wfile.write(b'Sent: 1 filters applied.' if 'code=1234' in self.path else b'Wrong code')

        def log_message(self, *a):
            pass

    srv = http.server.HTTPServer(('127.0.0.1', 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    addr = f'127.0.0.1:{srv.server_port}'
    eq = 'Filter 1: ON PK Fc 190 Hz Gain -8.0 dB Q 2.50\n'
    assert g.send_to_tv(addr, '1234', eq) == 'Sent: 1 filters applied.'
    assert got == {'path': '/eq?code=1234', 'body': eq}
    assert g.send_to_tv(addr, '0000', eq) == 'Wrong code'  # error body is shown to the user
    srv.shutdown()
    assert 'Could not reach' in g.send_to_tv('127.0.0.1:1', '1234', eq)
