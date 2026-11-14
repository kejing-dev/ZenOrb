"""ZenOrb sounds from CC0 recordings only (design sound.list, D51 / B20 / B30, build.md freesound CC0 flow). v0.1.1 after
the user's headset look at v0.1.0 (2026-11-13): 「声音要再 peace 宁静一点，有点禅意一点」 -> singing bowls only, slow
attack (>= 250 ms), long natural tail, in / out a major third apart, nothing above 4 kHz, no wood tick, no synthesis.
Chain per sound: onset -> offline resample (pitch) -> 80 Hz high-pass -> 4 kHz low-pass (4th order, applied twice = 8th)
-> raised-cosine attack -> cut with a long cosine tail fade -> peak -1 dBFS. 44.1 kHz mono 16-bit WAV into assets/sounds;
playback levels (-6 dB vs v0.1.0) are in SoundPlayer.LEVEL.

  ~/.factory-venv/bin/python tools/make_sounds.py

Sources (~/Downloads/cc0-sounds/freesound-zen-orb, pages say Creative Commons 0; LICENSE.txt there):
  strike  707797 Tibetan_Singing_bowl_1 by TheGreenEyedGhost   (one clean strike, 946 Hz, ~8 s natural decay)
  sing    439235 singing bowl.wav by zambolino                  (rubbed rim, 634 Hz, swelling sustained tone)
  creek   728092 Creek_loop_02 by andrew1280                     (optional ambient bed, off by default)

inhale     strike -5 semitones (709 Hz), attack 550 ms, 5.0 s, tail fade 2.0 s
inhale_2   sing   +1.94 semitones (709 Hz), attack 400 ms, 4.5 s, tail fade 2.2 s
exhale     strike -9 semitones (563 Hz, a major third under inhale), attack 600 ms, 5.5 s, tail fade 2.2 s
exhale_2   sing   -2.06 semitones (563 Hz), attack 400 ms, 5.0 s, tail fade 2.4 s
tick       (a matched breath / the recall stone) strike 0 semitones, low-passed 3 kHz, attack 450 ms, 2.2 s, fade 1.2 s
done       strike -12 semitones (473 Hz), attack 650 ms, 7.0 s, tail fade 3.0 s
focus_end  strike -10 semitones (531 Hz), attack 650 ms, 7.0 s, tail fade 3.0 s
ambient_creek.ogg  creek, 4 kHz low-pass, 20 s seamless loop (1.5 s cross-fade), RMS -20 dBFS (played at 0.06 x volume)
"""
import os, subprocess, wave
import numpy as np
from scipy.signal import butter, sosfiltfilt, resample

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
LIB = os.path.expanduser("~/Downloads/cc0-sounds/freesound-zen-orb")
SR = 44100


def load(name):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", os.path.join(LIB, name), "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, "<i2").astype(float) / 32768


def onset(x, thr=0.05):
    idx = np.nonzero(np.abs(x) > thr * np.max(np.abs(x)))[0]
    return x[max(0, idx[0] - int(0.004 * SR)):] if len(idx) else x


def pitch(x, semis):
    """Resample so it plays back [semis] semitones higher (shorter) / lower (longer)."""
    k = 2 ** (semis / 12)
    return resample(x, int(len(x) / k))


def band(x, lo=80, hi=4000):
    x = sosfiltfilt(butter(2, lo, "highpass", fs=SR, output="sos"), x)
    return sosfiltfilt(butter(4, hi, "lowpass", fs=SR, output="sos"), x)


def shape(x, secs, attack, fade):
    x = x[: int(secs * SR)].copy()
    a = int(attack * SR)
    x[:a] *= 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, a))
    n = int(fade * SR)
    x[-n:] *= np.cos(np.linspace(0, np.pi / 2, n)) ** 2
    return x


def peak(x, db):
    return x * (10 ** (db / 20) / (np.max(np.abs(x)) + 1e-12))


def save(name, x):
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    sp = np.abs(np.fft.rfft(x)); fr = np.fft.rfftfreq(len(x), 1 / SR)
    hi = 10 * np.log10(np.sum(sp[fr > 4000] ** 2) / np.sum(sp ** 2) + 1e-12)
    w = int(0.01 * SR); env = np.array([np.sqrt(np.mean(x[i:i + w] ** 2)) for i in range(0, len(x) - w, w)])
    t10 = np.argmax(env > 0.1 * env.max()) * 0.01; t90 = np.argmax(env > 0.9 * env.max()) * 0.01
    print(f"{name}.wav {len(x) / SR:.2f} s peak {20 * np.log10(np.max(np.abs(x)) + 1e-12):.1f} dBFS  rise 10-90 % {1000 * (t90 - t10):.0f} ms  >4 kHz energy {hi:.0f} dB  f0 {fr[np.argmax(sp)]:.0f} Hz")


def loop_ogg(x, secs, xfade, name):
    n = int(secs * SR); m = int(xfade * SR)
    body = x[: n + m].copy()
    w = np.linspace(0, 1, m)
    body[:m] = body[:m] * w + body[n:n + m] * (1 - w)
    body = body[:n]
    body *= 10 ** (-20 / 20) / np.sqrt(np.mean(body ** 2))
    tmp = os.path.join(OUT, name + ".tmp.wav")
    with wave.open(tmp, "wb") as wv:
        wv.setnchannels(1); wv.setsampwidth(2); wv.setframerate(SR)
        wv.writeframes((np.clip(body, -1, 1) * 32767).astype("<i2").tobytes())
    subprocess.run(["ffmpeg", "-v", "quiet", "-y", "-i", tmp, "-c:a", "libvorbis", "-q:a", "3", os.path.join(OUT, name + ".ogg")], check=True)
    os.remove(tmp)
    print(f"{name}.ogg {secs:.1f} s loop, RMS -20 dBFS, peak {20 * np.log10(np.max(np.abs(body))):.1f} dBFS")


def main():
    strike = onset(load("707797_14713637.mp3"))
    sing = load("439235_2557312.mp3")[int(9.0 * SR): int(19.0 * SR)]     # the steady part of the rubbed rim
    # the strike's first 40 ms (the stick click) is skipped; the rise is then shaped (10-90 % >= 250 ms, printed)
    strike = strike[int(0.04 * SR):]
    save("inhale", peak(shape(band(pitch(strike, -5)), 5.0, 0.55, 2.0), -1))
    save("inhale_2", peak(shape(band(pitch(sing, 1.94)), 4.5, 0.40, 2.2), -1))
    save("exhale", peak(shape(band(pitch(strike, -9)), 5.5, 0.60, 2.2), -1))
    save("exhale_2", peak(shape(band(pitch(sing, -2.06)), 5.0, 0.40, 2.4), -1))
    save("tick", peak(shape(band(strike, hi=3000), 2.2, 0.45, 1.2), -1))
    save("done", peak(shape(band(pitch(strike, -12)), 7.0, 0.65, 3.0), -1))
    save("focus_end", peak(shape(band(pitch(strike, -10)), 7.0, 0.65, 3.0), -1))
    for old in ("tick_2.wav",):
        p = os.path.join(OUT, old)
        if os.path.exists(p): os.remove(p)
    loop_ogg(band(load("728092_430632.mp3")), 20.0, 1.5, "ambient_creek")


if __name__ == "__main__":
    main()
