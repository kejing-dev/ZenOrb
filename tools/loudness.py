"""Loudness evidence (B20 / ui_gates loudness, design sound.loudness). RENDERED, NOT RECORDED: kj-mbp-m5 has no audio
loopback from the emulator. Uses the app's default gains (volume 「中」 = 0.75 x SoundPlayer.LEVEL) on the shipped WAVs.

  ~/.factory-venv/bin/python tools/loudness.py OUT_DIR VERSION

loudness-<v>.wav   the event sounds of a round back to back (inhale bell, exhale bell, tick, round-done bell, focus-end
                   bell), silences trimmed: what ui_gates measures (voiced RMS: 20 ms windows above peak -30 dB)
burst-<v>.wav      the tick 5 times 80 ms apart with the one-instance rule (D51: a new one stops the previous)
"""
import os, sys, wave
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SND = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
SR = 44100
VOL = 0.75
LEVEL = {"inhale": 1.0, "exhale": 1.0, "tick": 1.0, "done": 1.0, "focus_end": 1.0}


def load(n):
    with wave.open(os.path.join(SND, n + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(float) / 32768


def save(p, x):
    with wave.open(p, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def db(v): return 20 * np.log10(v + 1e-12)


def voiced(x):
    win = int(0.02 * SR); peak = np.max(np.abs(x))
    e = np.array([np.mean(x[k:k + win] ** 2) for k in range(0, len(x) - win + 1, win)])
    v = e[e >= (peak * 0.0316) ** 2]
    return db(np.sqrt(np.mean(v))), db(peak)


def trim_quiet(x):
    """Keep the voiced part only (the gate's 「发声段」): drop the tail once it is 30 dB under this sound's peak."""
    win = int(0.02 * SR); peak = np.max(np.abs(x)); keep = 0
    for k in range(0, len(x) - win + 1, win):
        if np.sqrt(np.mean(x[k:k + win] ** 2)) >= peak * 0.0316: keep = k + win
    return x[:keep]


def main():
    out, ver = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    parts, rows = [], []
    for n in ["inhale", "exhale", "tick", "done", "focus_end"]:
        x = load(n) * VOL * LEVEL[n]
        r, p = voiced(x)
        rows.append(f"{n:10s} voiced RMS {r:6.1f} dBFS  peak {p:6.1f} dBFS")
        parts += [trim_quiet(x), np.zeros(int(0.05 * SR))]
    seq = np.concatenate(parts)
    save(os.path.join(out, f"loudness-{ver}.wav"), seq)
    r, p = voiced(seq)
    t = load("tick") * VOL
    burst = np.zeros(int(0.08 * SR) * 4 + len(t))
    for i in range(5):
        a = int(0.08 * SR) * i
        burst[a:] = 0  # one instance: the previous stops when the next starts
        burst[a:a + len(t)] = t[: len(burst) - a]
    save(os.path.join(out, f"burst-{ver}.wav"), burst)
    br, bp = voiced(burst)
    print("\n".join(rows))
    print(f"loudness-{ver}.wav voiced RMS {r:.1f} dBFS peak {p:.1f} dBFS (rendered at volume 中 = {VOL})")
    print(f"burst-{ver}.wav 5 ticks / 80 ms peak {bp:.1f} dBFS")


main()
