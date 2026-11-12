"""ZenOrb models: the three gate-B styles (design styles.recommended: glaze default, brass + moss switchable) exported as
GLB from the design station's parametric script (apps/zen-orb/design/zen-orb_3d.js, three.js r128) ported to numpy.

  ~/.factory-venv/bin/python tools/build_models.py

Writes app/src/main/assets/models/cradle_<style>.glb + orb_<style>.glb and app/src/main/assets/textures/orb_<style>.png.
Units: metres. Content-root frame (design spatialLayout): origin = volume floor centre, +Y up, +Z toward the user.
The cradle sits on y = 0 and holds the orb centre at y = 0.070. The orb GLB is centred on its own origin (r = 0.050,
moss up to +3.4 mm displacement); the app moves / scales the orb node (D25 calibration: origin = centre, up = +Y).
All materials OPAQUE (B20 / design budget); the orb's material is replaced at runtime by one built from
textures/orb_<style>.png so the in-band warm tint can be applied (design followSpec.feedback).
"""
import io, math, os
import numpy as np
import trimesh
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODELS = os.path.join(ROOT, "app", "src", "main", "assets", "models")
TEX = os.path.join(ROOT, "app", "src", "main", "assets", "textures")


# ---------------------------------------------------------------- noise (same maths as zen-orb_3d.js, vectorised)
def _hash(x, y, z):
    x = x.astype(np.int64); y = y.astype(np.int64); z = z.astype(np.int64)
    h = (x * 374761393 + y * 668265263 + z * 1442695041) & 0xFFFFFFFF
    h = h.astype(np.int64)
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h = (h ^ (h >> 16)) & 0xFFFFFFFF
    return h / 4294967295.0


def vnoise(x, y, z):
    xi, yi, zi = np.floor(x), np.floor(y), np.floor(z)
    xf, yf, zf = x - xi, y - yi, z - zi
    s = lambda t: t * t * (3 - 2 * t)
    u, v, w = s(xf), s(yf), s(zf)
    c = lambda i, j, k: _hash(xi + i, yi + j, zi + k)
    L = lambda a, b, t: a + (b - a) * t
    return L(L(L(c(0, 0, 0), c(1, 0, 0), u), L(c(0, 1, 0), c(1, 1, 0), u), v),
             L(L(c(0, 0, 1), c(1, 0, 1), u), L(c(0, 1, 1), c(1, 1, 1), u), v), w)


def fbm(x, y, z, o=4):
    a, f, s = 0.5, 1.0, 0.0
    for _ in range(o):
        s = s + a * vnoise(x * f, y * f, z * f)
        a *= 0.5; f *= 2.03
    return s


def vor(x, y, z):
    xi, yi, zi = np.floor(x), np.floor(y), np.floor(z)
    d1 = np.full(x.shape, 9.0); d2 = np.full(x.shape, 9.0)
    for i in (-1, 0, 1):
        for j in (-1, 0, 1):
            for k in (-1, 0, 1):
                a, b, c = xi + i, yi + j, zi + k
                px = a + _hash(a, b, c); py = b + _hash(b, c, a + 7); pz = c + _hash(c, a, b + 13)
                d = (x - px) ** 2 + (y - py) ** 2 + (z - pz) ** 2
                nd2 = np.where(d < d1, d1, np.minimum(d2, d))
                d1 = np.minimum(d1, d); d2 = nd2
    return np.sqrt(d2) - np.sqrt(d1)


def rgb(h):
    return np.array([(h >> 16) & 255, (h >> 8) & 255, h & 255], float)


def mix(a, b, t):
    t = np.asarray(t)[..., None]
    return a + (b - a) * t


# ---------------------------------------------------------------- textures
def sphere_dirs(W, H):
    j, i = np.mgrid[0:H, 0:W]
    th = (j + 0.5) / H * math.pi
    ph = (i + 0.5) / W * math.pi * 2
    return -np.cos(ph) * np.sin(th), np.cos(th), np.sin(ph) * np.sin(th)


def img(a):
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGB")


def tex_glaze(W=1024, H=512):
    x, y, z = sphere_dirs(W, H)
    base, pool, clay, iron = rgb(0xCDC2AC), rgb(0xB3A486), rgb(0xA87A55), rgb(0x5B4130)
    w = fbm(x * 4, y * 4, z * 4, 3) * 0.6
    edge = vor(x * 7 + w, y * 7, z * 7 - w) + vor(x * 15, y * 15 + w, z * 15) * 0.5
    n = fbm(x * 3 + 5, y * 3, z * 3, 4)
    c = mix(base, pool, np.maximum(0, n - 0.4) * 1.6)
    thin = np.maximum(0, (-y - 0.6)) * 2.0 + np.maximum(0, fbm(x * 2, y * 2 + 7, z * 2, 3) - 0.7) * 2.2
    c = mix(c, clay, np.minimum(0.5, thin))
    crack = np.where(edge < 0.035, (1 - edge / 0.035) * 0.7, 0)
    c = mix(c, c * np.array([0.86, 0.84, 0.80]), crack)
    sp = fbm(x * 70, y * 70, z * 70, 2)
    c = mix(c, iron, np.where(sp > 0.76, np.minimum(0.85, (sp - 0.76) * 6), 0))
    return img(c)


def tex_basalt(W=1024, H=512):
    x, y, z = sphere_dirs(W, H)
    a, b = rgb(0x2E2F2E), rgb(0x3A3A37)
    n = fbm(x * 5, y * 5, z * 5, 5)
    c = mix(a, b, n)
    s = _hash(np.floor((x + 1) * 420), np.floor((y + 1) * 420), np.floor((z + 1) * 420))
    c = mix(c, np.array([175., 170., 160.]), np.where(s > 0.992, 0.8, 0))
    return img(c)


def moss_field(x, y, z):
    """Mossy mask 0..1 and displacement (m) on the unit sphere direction (zen-orb_3d.js orbGeometry('moss'))."""
    n = fbm(x * 2.2 + 3, y * 2.2, z * 2.2, 4)
    mossy = np.clip(((y * 0.55 + 0.25 * x) + n * 0.9 - 0.55) * 4, 0, 1)
    fine = fbm(x * 40, y * 40, z * 40, 2)
    disp = 0.0012 * (fbm(x * 5, y * 5, z * 5, 3) - 0.5) + mossy * (0.0018 + 0.0016 * fine)
    return mossy, fine, disp


def tex_moss(W=1024, H=512):
    x, y, z = sphere_dirs(W, H)
    mossy, fine, _ = moss_field(x, y, z)
    stone, stone2, mos, mos2 = rgb(0x7E817B), rgb(0x5E625D), rgb(0x4C5536), rgb(0x5F6844)
    sc = mix(stone, stone2, fbm(x * 6, y * 6, z * 6, 3))
    mc = mix(mos, mos2, fine)
    return img(mix(sc, mc, mossy))


def flat(W, H, fn):
    j, i = np.mgrid[0:H, 0:W]
    return img(fn(i / W, j / H))


def wood(dark, light, ring_k):
    d, l = rgb(dark), rgb(light)

    def f(u, v):
        n = fbm(u * 5, v * 2, np.full(u.shape, 1.7), 4)
        r = np.sin((u * ring_k * 9 + v * 1.2 + n * 3.0) * math.pi * 2) * 0.5 + 0.5
        fine = fbm(u * 500, v * 3, np.full(u.shape, 3.1), 2)
        return mix(d, l, r ** 3 * 0.28 + fine * 0.35 + n * 0.25)
    return flat(1024, 256, f)


def stone(a, b, speck):
    A, B, S = rgb(a), rgb(b), rgb(speck)

    def f(u, v):
        n = fbm(u * 10, v * 5, np.full(u.shape, 0.3), 5)
        c = mix(A, B, n)
        s = _hash(np.floor(u * 700), np.floor(v * 350), np.full(u.shape, 9.0))
        return mix(c, S, np.where(s > 0.985, 0.7, 0))
    return flat(512, 256, f)


# ---------------------------------------------------------------- meshes
def lathe(profile, seg=128, u_turns=1.0):
    """three.js LatheGeometry: profile (r, y) revolved around +Y; uv u = turn, v = profile index fraction."""
    P = len(profile)
    verts, uvs, faces = [], [], []
    for s in range(seg + 1):
        a = 2 * math.pi * s / seg
        c, sn = math.cos(a), math.sin(a)
        for k, (r, y) in enumerate(profile):
            verts.append((r * sn, y, r * c))
            uvs.append((s / seg * u_turns, k / (P - 1)))
    for s in range(seg):
        for k in range(P - 1):
            a = s * P + k; b = a + P
            faces.append((a, b, a + 1)); faces.append((a + 1, b, b + 1))
    m = trimesh.Trimesh(np.array(verts), np.array(faces), process=False)
    m.visual = trimesh.visual.TextureVisuals(uv=np.array(uvs))
    return fix_out(m)


def fix_out(m):
    """Orient faces so normals point away from the Y axis / up on caps (lathe winding varies with profile direction)."""
    m.fix_normals()
    return m


def torus(R, r, tube_seg=12, seg=128, y=0.0, scale_z=1.0):
    verts, faces = [], []
    for i in range(seg + 1):
        a = 2 * math.pi * i / seg
        for j in range(tube_seg + 1):
            b = 2 * math.pi * j / tube_seg
            rr = R + r * math.cos(b)
            verts.append((rr * math.cos(a), y + r * math.sin(b) * scale_z, rr * math.sin(a)))
    n = tube_seg + 1
    for i in range(seg):
        for j in range(tube_seg):
            a = i * n + j; b = a + n
            faces.append((a, b, a + 1)); faces.append((a + 1, b, b + 1))
    m = trimesh.Trimesh(np.array(verts), np.array(faces), process=False)
    m.fix_normals()
    return m


def cylinder(r0, r1, h, seg=16):
    m = trimesh.creation.cylinder(radius=1.0, height=h, sections=seg)
    v = m.vertices.copy()
    t = (v[:, 2] + h / 2) / h
    rad = r1 * (1 - t) + r0 * t
    v[:, 0] *= rad; v[:, 1] *= rad
    m.vertices = v[:, [0, 2, 1]]
    m.fix_normals()
    return m


def uv_sphere(radius, seg=96, rings=72, disp=None):
    verts, uvs, faces = [], [], []
    for r in range(rings + 1):
        th = math.pi * r / rings
        for s in range(seg + 1):
            ph = 2 * math.pi * s / seg
            # direction matching sphere_dirs(): x = -cos(ph) sin(th), y = cos(th), z = sin(ph) sin(th)
            x = -math.cos(ph) * math.sin(th); y = math.cos(th); z = math.sin(ph) * math.sin(th)
            verts.append((x, y, z)); uvs.append((s / seg, 1 - r / rings))
    d = np.array(verts)
    rad = np.full(len(d), radius)
    if disp is not None:
        rad = rad + disp(d[:, 0], d[:, 1], d[:, 2])
    v = d * rad[:, None]
    for r in range(rings):
        for s in range(seg):
            a = r * (seg + 1) + s; b = a + seg + 1
            if r != 0: faces.append((a, b, a + 1))
            if r != rings - 1: faces.append((a + 1, b, b + 1))
    m = trimesh.Trimesh(v, np.array(faces), process=False)
    m.fix_normals()
    m.visual = trimesh.visual.TextureVisuals(uv=np.array(uvs))
    return m


def mat(name, color=(255, 255, 255), tex=None, rough=0.6, metal=0.0):
    return trimesh.visual.material.PBRMaterial(
        name=name, baseColorFactor=list(color) + [255], baseColorTexture=tex,
        metallicFactor=metal, roughnessFactor=rough, alphaMode="OPAQUE", doubleSided=False)


def textured(m, material):
    uv = m.visual.uv if isinstance(m.visual, trimesh.visual.TextureVisuals) and m.visual.uv is not None else np.zeros((len(m.vertices), 2))
    m.visual = trimesh.visual.TextureVisuals(uv=uv, material=material)
    return m


def cup_profile(R, H, ri, bead):
    p = [[0, 0], [R - 0.006, 0], [R - 0.002, 0.002], [R, 0.006]]
    if bead:
        p += [[R, H * 0.45], [R + 0.0025, H * 0.52], [R, H * 0.6]]
    p += [[R - 0.001, H - 0.004], [R - 0.004, H], [ri + 0.004, H], [ri, H - 0.002]]
    for i in range(1, 17):
        a = i / 16
        p.append([ri * (1 - a), H - 0.002 - 0.010 * math.sin(a * math.pi / 2)])
    return p


# ---------------------------------------------------------------- styles
def cradle(style):
    parts = []
    if style == "glaze":
        parts.append(textured(lathe(cup_profile(0.065, 0.032, 0.040, True)), mat("walnut", tex=wood(0x3B2A1F, 0x6E5039, 9), rough=0.55)))
    elif style == "brass":
        brass = mat("brass", color=(0xB0, 0x8D, 0x57), rough=0.32, metal=1.0)
        disc = textured(lathe([[0, 0], [0.068, 0], [0.070, 0.002], [0.070, 0.010], [0.068, 0.012], [0, 0.012]]),
                        mat("basalt_disc", tex=stone(0x252624, 0x343532, 0x8C8A84), rough=0.7))
        parts.append(disc)
        parts.append(textured(torus(0.036, 0.0022, 16, 128, y=0.0337), brass))
        for k in range(3):
            a = k / 3 * math.pi * 2 + 0.3
            leg = cylinder(0.0016, 0.0019, 0.022, 16)
            # tilt the leg outward slightly (three.js rotation z = cos a * 0.18, x = -sin a * 0.18)
            rot = trimesh.transformations.euler_matrix(-math.sin(a) * 0.18, 0, math.cos(a) * 0.18, "sxyz")
            leg.apply_transform(rot)
            leg.apply_translation([math.cos(a) * 0.040, 0.0225, math.sin(a) * 0.040])
            parts.append(textured(leg, brass))
    elif style == "moss":
        p = [[0, 0], [0.074, 0], [0.077, 0.004], [0.078, 0.016], [0.074, 0.020], [0.060, 0.020], [0.046, 0.018], [0.040, 0.022]]
        for i in range(1, 13):
            a = i / 12
            p.append([0.040 * (1 - a), 0.022 - 0.005 * math.sin(a * math.pi / 2)])
        parts.append(textured(lathe(p), mat("slate", tex=stone(0x3A3D40, 0x4A4D4F, 0x6A6D6E), rough=0.85)))
        rng = np.random.default_rng(77)
        gravel = []
        for i in range(70):
            a = rng.random() * math.pi * 2; r = 0.043 + rng.random() * 0.018
            s = trimesh.creation.icosphere(subdivisions=1, radius=0.0022 + rng.random() * 0.0018)
            s.apply_scale([1, 0.6, 1])
            s.apply_transform(trimesh.transformations.euler_matrix(*(rng.random(3) * 3)))
            s.apply_translation([math.cos(a) * r, 0.020 + rng.random() * 0.001, math.sin(a) * r])
            gravel.append(s)
        g = trimesh.util.concatenate(gravel)
        parts.append(textured(g, mat("gravel", color=(0x5A, 0x58, 0x52), rough=0.95)))
    return parts


def orb(style):
    if style == "moss":
        return uv_sphere(0.05, 128, 96, disp=lambda x, y, z: moss_field(x, y, z)[2])
    return uv_sphere(0.05, 96, 72)


ORB_TEX = {"glaze": tex_glaze, "brass": tex_basalt, "moss": tex_moss}
ORB_ROUGH = {"glaze": 0.38, "brass": 0.55, "moss": 0.92}


def export(meshes, path):
    scene = trimesh.Scene()
    for i, m in enumerate(meshes):
        scene.add_geometry(m, node_name=f"part{i}", geom_name=f"part{i}")
    data = scene.export(file_type="glb")
    open(path, "wb").write(data)
    tris = sum(len(m.faces) for m in meshes)
    lo = np.min([m.bounds[0] for m in meshes], axis=0); hi = np.max([m.bounds[1] for m in meshes], axis=0)
    print(f"{os.path.basename(path)}: {tris} tris, {len(data) / 1024:.0f} KB, bounds {np.round(lo, 4)} .. {np.round(hi, 4)}")


def main():
    os.makedirs(MODELS, exist_ok=True); os.makedirs(TEX, exist_ok=True)
    for s in ("glaze", "brass", "moss"):
        export(cradle(s), os.path.join(MODELS, f"cradle_{s}.glb"))
        t = ORB_TEX[s]()
        t.save(os.path.join(TEX, f"orb_{s}.png"))
        o = textured(orb(s), mat(f"orb_{s}", tex=t, rough=ORB_ROUGH[s]))
        export([o], os.path.join(MODELS, f"orb_{s}.glb"))


if __name__ == "__main__":
    main()
