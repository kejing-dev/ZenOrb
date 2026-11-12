"""ZenOrb event sounds from CC0 recordings only (design sound.list, D51 / B20 / build.md freesound CC0 flow). Nothing
synthesised: cut / pitched (offline resample) / faded / levelled here. 44.1 kHz mono 16-bit WAV into assets/sounds.

  ~/.factory-venv/bin/python tools/make_sounds.py

Sources (~/Downloads/cc0-sounds/freesound-zen-orb, pages say Creative Commons 0; LICENSE.txt there):
  bowl_a  439235 singing bowl.wav by zambolino
  bowl_b  573805 Singing Bowl, long without reverb by hollandm
  wood_a  218460 Wood block hit by thomasjaunism
  wood_b  533093 Wood Block by Sassaby

inhale(_2)    bowl strike, 2.6 s with a 1.2 s fade (two recordings = two variants)          peak -1 dBFS
exhale(_2)    the same strikes resampled 4 semitones down (design: 「同一颂钵录音降 4 个半音」)   peak -1 dBFS
tick(_2)      wood block hit, 0.35 s (two recordings)                                         peak -2 dBFS
done          bowl strike, 4.0 s decay                                                        peak -1 dBFS
focus_end     done resampled 2 semitones up                                                   peak -1 dBFS
"""
import os, subprocess, wave
import numpy as np
from scipy.signal import butter, sosfilt, resample

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
LIB = os.path.expanduser("~/Downloads/cc0-sounds/freesound-zen-orb")
SR = 44100


def load(name):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", os.path.join(LIB, name), "-ac", "1", "-ar", str(SR), "-f", "s16le", "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, "<i2").astype(float) / 32768


def onset(x, thr=0.02):
    idx = np.nonzero(np.abs(x) > thr * np.max(np.abs(x)))[0]
    return x[max(0, idx[0] - int(0.004 * SR)):] if len(idx) else x


def cut(x, secs, fade):
    x = x[: int(secs * SR)].copy()
    n = int(fade * SR)
    if len(x) > n:
        x[-n:] *= np.cos(np.linspace(0, np.pi / 2, n)) ** 2
    x[: int(0.003 * SR)] *= np.linspace(0, 1, int(0.003 * SR))
    return x


def pitch(x, semis):
    """Resample so it plays back [semis] semitones higher (shorter) / lower (longer)."""
    k = 2 ** (semis / 12)
    return resample(x, int(len(x) / k))


def hp(x, f):
    return sosfilt(butter(2, f, "highpass", fs=SR, output="sos"), x)


def peak(x, db):
    return x * (10 ** (db / 20) / (np.max(np.abs(x)) + 1e-12))


def save(name, x):
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    print(f"{name}.wav {len(x) / SR:.2f} s peak {20 * np.log10(np.max(np.abs(x)) + 1e-12):.1f} dBFS")


def main():
    a = hp(onset(load("439235_2557312.mp3")), 60)
    b = hp(onset(load("573805_5309408.mp3")), 60)
    wa = hp(onset(load("218460_3344538.mp3")), 80)
    wb = hp(onset(load("533093_2771755.mp3")), 80)
    save("inhale", peak(cut(a, 2.6, 1.2), -1))
    save("inhale_2", peak(cut(b, 2.6, 1.2), -1))
    save("exhale", peak(cut(pitch(a, -4), 2.6, 1.2), -1))
    save("exhale_2", peak(cut(pitch(b, -4), 2.6, 1.2), -1))
    save("tick", peak(cut(wa, 0.35, 0.12), -2))
    save("tick_2", peak(cut(wb, 0.35, 0.12), -2))
    done = cut(b, 4.0, 1.6)
    save("done", peak(done, -1))
    save("focus_end", peak(cut(pitch(b, 2), 4.0, 1.6), -1))


if __name__ == "__main__":
    main()
