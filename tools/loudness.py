"""Loudness evidence (B20 / ui_gates loudness, design sound.levels / M8). Rendered, not recorded: kj-mbp-m5 has no
audio loopback from the emulator. Uses the app's default gains (volume step 1 = 0.70; SoundPlayer LEVEL / BALL_LEVEL /
RAKE_LEVEL) on the shipped WAVs.

  ~/.factory-venv/bin/python tools/loudness.py OUT_DIR VERSION

loudness-<v>.wav       the action sounds of a typical minute, back to back (rake stroke 2 s, click, three stone
                        thuds 90 ms apart, smoothing sweep), silences trimmed: what ui_gates measures (voiced RMS / peak)
loudness-ball-<v>.wav  30 s of the ball rolling at the default 2 cm/s (BALL_LEVEL 0.35 x 0.70 x sqrt(1)), whole-file RMS
burst-<v>.wav          one event 5 times 80 ms apart with the one-instance rule (D51)
"""
import os, sys, wave
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SND = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
SR = 22050
VOL = 0.70


def load(n):
    with wave.open(os.path.join(SND, n + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(float) / 32768


def save(p, x):
    with wave.open(p, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def db(v): return 20 * np.log10(v + 1e-12)


def voiced_rms(x):
    win = 882; peak = np.max(np.abs(x))
    e = [np.mean(x[k:k + win] ** 2) for k in range(0, len(x) - win + 1, win)]
    v = [q for q in e if q >= (peak * 0.0316) ** 2] or e
    return db(np.sqrt(np.mean(v))), db(peak)


out, ver = sys.argv[1], sys.argv[2]
gap = np.zeros(int(SR * 0.04))
parts = [load("rake")[:SR * 2] * 1.0 * VOL, gap, load("click") * 0.8 * VOL, gap]
th = np.zeros(int(SR * 0.6))
for i, n in enumerate(["thud", "thud_2", "thud_3"]):
    x = load(n) * VOL; o = int(SR * 0.09 * i); th[o:o + len(x)] += x
parts += [th, gap, load("smooth") * VOL]
act = np.concatenate(parts)
save(os.path.join(out, f"loudness-{ver}.wav"), act)
r, p = voiced_rms(act)
print(f"actions (voiced): RMS {r:.1f} dBFS, peak {p:.1f} dBFS")

ball = np.tile(load("ball_roll"), 8)[:SR * 30] * 0.35 * VOL
save(os.path.join(out, f"loudness-ball-{ver}.wav"), ball)
print(f"ball loop 30 s: RMS {db(np.sqrt(np.mean(ball ** 2))):.1f} dBFS, peak {db(np.max(np.abs(ball))):.1f} dBFS")

b = np.zeros(int(SR * 0.8)); x = load("thud") * VOL
for i in range(5):
    o = int(SR * 0.08 * i); b[o:] = 0 if i else b[o:]; b[o:o + len(x)] = x[:len(b) - o]
save(os.path.join(out, f"burst-{ver}.wav"), b)
print(f"burst x5 (one instance): peak {db(np.max(np.abs(b))):.1f} dBFS")
