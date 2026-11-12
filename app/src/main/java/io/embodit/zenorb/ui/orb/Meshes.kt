package io.embodit.zenorb.ui.orb

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Plain vertex / index lists, then one MeshModel. Triangles are wound to face their vertex normals. */
internal class MeshData(cap: Int = 64) {
    val p = ArrayList<Float>(cap * 3)
    val n = ArrayList<Float>(cap * 3)
    val uv = ArrayList<Float>(cap * 2)
    val idx = ArrayList<Int>(cap * 3)
    val count: Int get() = p.size / 3

    fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float = 0f, w: Float = 0f): Int {
        p += x; p += y; p += z; n += nx; n += ny; n += nz; uv += u; uv += w
        return count - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        val ax = p[a * 3]; val ay = p[a * 3 + 1]; val az = p[a * 3 + 2]
        val ux = p[b * 3] - ax; val uy = p[b * 3 + 1] - ay; val uz = p[b * 3 + 2] - az
        val vx = p[c * 3] - ax; val vy = p[c * 3 + 1] - ay; val vz = p[c * 3 + 2] - az
        val fx = uy * vz - uz * vy; val fy = uz * vx - ux * vz; val fz = ux * vy - uy * vx
        val mx = n[a * 3] + n[b * 3] + n[c * 3]; val my = n[a * 3 + 1] + n[b * 3 + 1] + n[c * 3 + 1]; val mz = n[a * 3 + 2] + n[b * 3 + 2] + n[c * 3 + 2]
        if (fx * mx + fy * my + fz * mz >= 0f) { idx += a; idx += b; idx += c } else { idx += a; idx += c; idx += b }
    }

    fun bounds(): Pair<FloatArray, FloatArray> {
        val lo = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val hi = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until count) for (k in 0..2) { lo[k] = min(lo[k], p[i * 3 + k]); hi[k] = max(hi[k], p[i * 3 + k]) }
        return lo to hi
    }

    fun model(): Pair<MeshModel, BoundingBox> {
        val pos = ArrayList<Vector3>(count); val nor = ArrayList<Vector3>(count); val tex = ArrayList<Vector2>(count)
        for (i in 0 until count) {
            pos += Vector3(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
            nor += Vector3(n[i * 3], n[i * 3 + 1], n[i * 3 + 2])
            tex += Vector2(uv[i * 2], uv[i * 2 + 1])
        }
        val (lo, hi) = bounds()
        val bounds = BoundingBox(
            center = Vector3((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2),
            halfExtent = Vector3(max((hi[0] - lo[0]) / 2, 1e-3f), max((hi[1] - lo[1]) / 2, 1e-3f), max((hi[2] - lo[2]) / 2, 1e-3f)),
        )
        return MeshModel(positions = pos, triangleIndices = ArrayList(idx), normals = nor, uv0 = tex) to bounds
    }

    fun create(name: String): MeshResource { val (m, b) = model(); return MeshResource.createWithMeshModel(model = m, bounds = b, name = name) }
}

internal object Meshes {
    /**
     * A horizontal torus (the guide ring / focus arc): major radius [R] in the XZ plane, tube radius [r], from angle
     * [a0] over [sweep] radians (2 PI = closed). Angle 0 = +Z (the front, toward the user), increasing counter-clockwise
     * seen from above (toward +X). True circular section (>= 12 sides, smooth normals; B12 ring-from-boxes).
     */
    fun torus(R: Float, r: Float, a0: Float = 0f, sweep: Float = (2 * PI).toFloat(), seg: Int = 128, sides: Int = 12): MeshData {
        val m = MeshData((seg + 1) * (sides + 1))
        for (i in 0..seg) {
            val a = a0 + sweep * i / seg
            val ca = cos(a); val sa = sin(a)
            for (j in 0..sides) {
                val b = (2 * PI * j / sides).toFloat()
                val cb = cos(b); val sb = sin(b)
                val rr = R + r * cb
                m.v(rr * sa, r * sb, rr * ca, cb * sa, sb, cb * ca, i.toFloat() / seg, j.toFloat() / sides)
            }
        }
        val k = sides + 1
        for (i in 0 until seg) for (j in 0 until sides) {
            val a = i * k + j; val b = a + k
            m.tri(a, b, a + 1); m.tri(a + 1, b, b + 1)
        }
        return m
    }

    /** Flat disc on y = [y] facing up (the opaque contact shadow under the cradle, design entities3d.contactShadow). */
    fun disc(radius: Float, y: Float, seg: Int = 96): MeshData {
        val m = MeshData(seg + 2)
        val c = m.v(0f, y, 0f, 0f, 1f, 0f, 0.5f, 0.5f)
        val ring = (0..seg).map { s -> val a = 2 * PI * s / seg; m.v((radius * cos(a)).toFloat(), y, (radius * sin(a)).toFloat(), 0f, 1f, 0f, 0.5f, 0.5f) }
        for (s in 0 until seg) m.tri(c, ring[s], ring[s + 1])
        return m
    }

    /** A text quad facing +Z, origin at its left-middle ([leftAnchored]) or centre. Image up = +Y (v = 1 at the top). */
    fun quad(w: Float, h: Float, leftAnchored: Boolean): MeshData {
        val m = MeshData(4)
        val x0 = if (leftAnchored) 0f else -w / 2
        val a = m.v(x0, -h / 2, 0f, 0f, 0f, 1f, 0f, 0f)
        val b = m.v(x0 + w, -h / 2, 0f, 0f, 0f, 1f, 1f, 0f)
        val c = m.v(x0 + w, h / 2, 0f, 0f, 0f, 1f, 1f, 1f)
        val d = m.v(x0, h / 2, 0f, 0f, 0f, 1f, 0f, 1f)
        m.tri(a, b, c); m.tri(a, c, d)
        return m
    }
}
