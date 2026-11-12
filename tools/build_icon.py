"""ZenOrb icon (design appIcon): an oat-glaze orb sitting in a walnut cradle on deep ink-green #2F3B36. Flat, no text.

Was: Aura Garden icon (design appIcon): warm charcoal #2E2A26, a pale sand disc in the middle with a thin five-petal rose
line one shade darker, a small silver dot (the ball) on a petal tip, and a very thin warm-white ring round the disc.
Flat, no text.

  ~/.factory-venv/bin/python tools/build_icon.py [--icon512 out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same shapes).
"""
import math, os, sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
BG = (0x2F, 0x3B, 0x36)        # deep ink-green (design appIcon)
OAT = (0xD9, 0xCF, 0xBA)       # oat glaze orb
OAT_SH = (0xB8, 0xA9, 0x8C)    # its lower shade
WALNUT = (0x5A, 0x3E, 0x2B)    # cradle
RING = (0xA8, 0x8A, 0x5E)      # old-brass guide ring
C = 54.0
ORB_R, ORB_Y = 19.0, 44.0
CR_TOP, CR_BOT, CR_W_TOP, CR_W_BOT = 58.0, 72.0, 24.0, 20.0


def big(fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def ell(d, k, cx, cy, rx, ry, **kw):
    d.ellipse(((cx - rx) * k, (cy - ry) * k, (cx + rx) * k, (cy + ry) * k), **kw)


def layer_bg():
    return big(lambda d, k: ell(d, k, C, C, 54, 54, fill=BG + (255,)))


def cradle_poly():
    return [(C - CR_W_TOP, CR_TOP), (C + CR_W_TOP, CR_TOP), (C + CR_W_BOT, CR_BOT), (C - CR_W_BOT, CR_BOT)]


def layer_cradle():
    def f(d, k):
        d.polygon([(x * k, y * k) for x, y in cradle_poly()], fill=WALNUT + (255,))
        ell(d, k, C, CR_TOP, CR_W_TOP, 3.2, fill=WALNUT + (255,))
        ell(d, k, C, CR_BOT, CR_W_BOT, 2.6, fill=WALNUT + (255,))
    return big(f)


def layer_orb():
    def f(d, k):
        # guide ring behind / in front of the orb (flat ellipse), orb with a two-tone lower shade
        ell(d, k, C, ORB_Y, ORB_R, ORB_R, fill=OAT_SH + (255,))
        ell(d, k, C - 2.2, ORB_Y - 2.4, ORB_R - 2.6, ORB_R - 2.6, fill=OAT + (255,))
    return big(f)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def circle_path(r):
    return "M%.2f,%.2fa%.2f,%.2f 0 1,0 %.2f,0a%.2f,%.2f 0 1,0 %.2f,0Z" % (C - r, C, r, r, 2 * r, r, r, -2 * r)


def ell_path(cx, cy, rx, ry):
    return "M%.2f,%.2fa%.2f,%.2f 0 1,0 %.2f,0a%.2f,%.2f 0 1,0 %.2f,0Z" % (cx - rx, cy, rx, ry, 2 * rx, rx, ry, -2 * rx)


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    poly = "M" + "L".join("%.2f,%.2f" % p for p in cradle_poly()) + "Z"
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- ZenOrb launcher foreground: oat-glaze orb in a walnut cradle with the guide ring (tools/build_icon.py draws the same for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(WALNUT), poly),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(WALNUT), ell_path(C, CR_TOP, CR_W_TOP, 3.2)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(WALNUT), ell_path(C, CR_BOT, CR_W_BOT, 2.6)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(OAT_SH), ell_path(C, ORB_Y, ORB_R, ORB_R)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(OAT), ell_path(C - 2.2, ORB_Y - 2.4, ORB_R - 2.6, ORB_R - 2.6)),
        '</vector>',
    ]
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_cradle(), layer_orb()]
    for i, l in enumerate(layers):
        l.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(l).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    print("ICON ok")


main()
