package io.embodit.zenorb.ui.orb

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.zenorb.domain.Flow
import io.embodit.zenorb.domain.Geo
import io.embodit.zenorb.domain.Hint
import io.embodit.zenorb.domain.Language
import io.embodit.zenorb.domain.Settings
import io.embodit.zenorb.domain.Style
import io.embodit.zenorb.platform.Graph
import io.embodit.zenorb.platform.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!
internal fun Entity.at(x: Float, y: Float, z: Float) = transform().setPosition(Vector3(x, y, z))
internal fun Entity.walk(f: (Entity) -> Unit) { f(this); for (c in getChildren()) c.walk(f) }

internal fun rgb4(rgb: Int, k: Float = 1f) = Color4((rgb shr 16 and 255) / 255f * k, (rgb shr 8 and 255) / 255f * k, (rgb and 255) / 255f * k, 1f)

/** Hit holder names (one place; resolved by name for the board-input template and the hit-replay unit test). */
object Names {
    const val ORB = "orb"
    /** The recall stone 「⋯」 left front of the cradle: a tap reopens the control window (design v5 C8). */
    const val RECALL = "recall"
    fun target(name: String): String? = name.takeIf { it == ORB || it == RECALL }
}

/**
 * Every entity of the volume (design entities3d):
 * ```
 * root (volume centre)
 * └ content (volume floor centre, y -0.15)
 *   ├ shadow (opaque dark disc r 0.085), cradle:<STYLE> (GLB, one enabled)
 *   ├ "orb" holder (sphere collider r 0.060 + Interactable + HoverEffect; moves / scales with f)
 *   │   └ orbModel:<STYLE> (GLB, one enabled)
 *   ├ ringPivot (scaled to the guide radius) → ringThin / ringThick (true torus, no collider)
 *   ├ arc:<STYLE> → 36 ten-degree segments on the cradle rim (focus remaining)
 *   ├ "recall" holder (0.077 box collider + Interactable + HoverEffect) → pebble + 3 dots 「⋯」 (style material)
 *   ├ tag (text quad right of the orb), caption (text quad in front of the cradle, leaned back 20 deg), hover (above the stone)
 *   └ keyLight
 * ```
 * Compose never drives motion: [onFrame] runs from the ECS system, ticks the flow and copies its OrbView here.
 */
internal class OrbScene(private val context: Context, private val graph: Graph, private val scope: CoroutineScope) {
    private val flow: Flow = graph.flow
    val root: Entity = Entity().apply { setName("root") }
    val content: Entity = Entity().apply { setName("content") }
    private val all = ArrayList<Resource>()
    private fun <T : Resource> T.keep(): T { toGlobal(); synchronized(all) { all += this }; return this }

    private var orbHolder: Entity? = null
    private val orbModels = HashMap<Style, Entity>()
    private val orbMats = HashMap<Style, List<PhysicallyBasedMaterial>>()
    private val cradles = HashMap<Style, Entity>()
    private val arcs = HashMap<Style, List<Entity>>()
    private var ringPivot: Entity? = null
    private var ringThin: Entity? = null
    private var ringThick: Entity? = null
    private var tagEnt: Entity? = null
    private var capEnt: Entity? = null
    private var tagMat: UnlitMaterial? = null
    private var capMat: UnlitMaterial? = null
    private var hoverEnt: Entity? = null
    private var hoverMat: UnlitMaterial? = null
    private var stoneHolder: Entity? = null
    private var stoneMat: PhysicallyBasedMaterial? = null
    private var dotMat: PhysicallyBasedMaterial? = null
    /** Pointer / gaze over the recall stone (set by the input listener; debug `hoverstone`). */
    @Volatile var stoneHovered = false
    private var hoverOnT = 0f
    private var hoverOffT = 0f
    private var hoverShown = false
    private var stoneLit = false
    @Volatile private var stonePressT = -1f
    private var shadowMat: PhysicallyBasedMaterial? = null
    private var arcMat: PhysicallyBasedMaterial? = null
    private var arcFadedMat: PhysicallyBasedMaterial? = null
    private var ringMat: PhysicallyBasedMaterial? = null
    private var ringBandMat: PhysicallyBasedMaterial? = null
    private var shownStyle: Style? = null
    private var shownArcN = -1
    private var shownArcFaded = false
    private var shownTint = -1f
    @Volatile var built = false; private set
    /** Physical metres per view px (drag scale, set by OrbScreen). */
    @Volatile var metersPerPx = 0f
    private var frames = 0
    private var fpsT0 = SystemClock.elapsedRealtime()
    @Volatile var fps = 0f; private set
    /** Debug `testf`: the F-up test pattern on both text quads (D10). */
    @Volatile var testF = false

    // text textures: cache by "text|w", created off the main thread
    private val texCache = HashMap<String, TextureResource>()
    private val texBusy = HashSet<String>()
    private var tagShown: String? = null
    private var capShown: String? = null
    private var hoverTextShown: String? = null
    private var shownEmit = -1f

    init {
        root.addChild(content)
        content.at(0f, -Geo.BOX_Y / 2, 0f)
    }

    suspend fun build() {
        val t0 = SystemClock.elapsedRealtime()
        val io = Dispatchers.IO
        val s0 = graph.settings.value
        coroutineScope {
            val meshes = async(io) {
                listOf(
                    Meshes.disc(Geo.SHADOW_R, 0.0004f).create("shadow").keep(),
                    Meshes.torus(R0, 0.0025f).create("ringThin").keep(),
                    Meshes.torus(R0, 0.0035f).create("ringThick").keep(),
                    Meshes.quad(Geo.TAG_W, Geo.TAG_H, leftAnchored = true).create("tagQuad").keep(),
                    Meshes.quad(Geo.CAP_W, Geo.CAP_H, leftAnchored = false).create("capQuad").keep(),
                    Meshes.quad(Geo.HOVER_W, Geo.HOVER_H, leftAnchored = false).create("hoverQuad").keep(),
                    Meshes.pebble(Geo.STONE_W, Geo.STONE_H, Geo.STONE_D).create("pebble").keep(),
                    Meshes.dome(0.0025f, 0.0012f).create("dot").keep(),
                )
            }
            val arcMeshes = Style.entries.associateWith { st ->
                async(io) { (0 until 36).map { i -> Meshes.torus(st.rimR, 0.0018f, (i * 10.0 * PI / 180).toFloat(), (10.0 * PI / 180).toFloat(), 6, 8).create("arc$i").keep() } }
            }
            val shape = async(io) { (PhysicsMaterialResource().keep()) to ShapeResource.createSphere(Geo.HIT_R).keep() }
            val stoneShape = async(io) { ShapeResource.createBox(Vector3(Geo.STONE_HIT, Geo.STONE_HIT, Geo.STONE_HIT)).keep() }
            val mats = async(io) {
                shadowMat = PhysicallyBasedMaterial.create().apply { setBaseColor(rgb4(s0.style.shadow)); setRoughness(1f); setMetallic(0f) }.keep()
                ringMat = PhysicallyBasedMaterial.create().apply { setBaseColor(rgb4(s0.style.ring)); setRoughness(0.5f); setMetallic(0.2f) }.keep()
                ringBandMat = PhysicallyBasedMaterial.create().apply { setBaseColor(rgb4(s0.style.ring, 1.08f)); setRoughness(0.3f); setMetallic(0.45f) }.keep()
                arcMat = PhysicallyBasedMaterial.create().apply { setBaseColor(rgb4(s0.style.accent)); setRoughness(0.4f); setMetallic(0.7f) }.keep()
                arcFadedMat = PhysicallyBasedMaterial.create().apply { setBaseColor(rgb4(s0.style.accent, 0.45f)); setRoughness(0.8f); setMetallic(0.2f) }.keep()
                fun text() = UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                    setBaseColor(Color4(1f, 1f, 1f, 1f)); setCullingMode(MaterialCullingMode.NONE); setApplyToneMapping(false)
                }.keep()
                tagMat = text(); capMat = text(); hoverMat = text()
                stoneMat = PhysicallyBasedMaterial.create().apply { setBaseColor(rgb4(s0.style.stone)); setRoughness(0.72f); setMetallic(0f) }.keep()
                dotMat = PhysicallyBasedMaterial.create().apply { setBaseColor(rgb4(s0.style.accent, 1.15f)); setRoughness(0.55f); setMetallic(0.3f) }.keep()
            }
            val glbs = Style.entries.flatMap { st ->
                listOf(
                    async { "cradle" to st to runCatching { Entity.loadSuspend("asset://models/cradle_${st.name.lowercase()}.glb") }.onFailure { Log.e(TAG, "MODEL cradle $st", it) }.getOrNull() },
                    async { "orb" to st to runCatching { Entity.loadSuspend("asset://models/orb_${st.name.lowercase()}.glb") }.onFailure { Log.e(TAG, "MODEL orb $st", it) }.getOrNull() },
                )
            }
            awaitAll(meshes, shape, mats, stoneShape)
            val ms = meshes.await()
            val shadowM = ms[0]; val thinM = ms[1]; val thickM = ms[2]; val tagQ = ms[3]; val capQ = ms[4]
            val hoverQ = ms[5]; val pebbleM = ms[6]; val dotM = ms[7]
            val (physics, sphere) = shape.await()

            ModelEntity(shadowM, shadowMat!!).apply { setName("shadow"); content.addChild(this) }
            val holder = Entity().apply {
                setName(Names.ORB)
                components.set(CollisionComponent(collisionShape = listOf(sphere), physicsMaterial = physics))
                components.set(InteractableComponent())
                components.set(HoverEffectComponent())
                content.addChild(this)
            }
            orbHolder = holder
            for (d in glbs) {
                val (kind, e) = d.await()
                val (k, st) = kind
                e ?: continue
                e.enabled = false
                if (k == "cradle") { e.setName("cradle:${st.name}"); content.addChild(e); cradles[st] = e }
                else {
                    e.setName("orbModel:${st.name}"); holder.addChild(e); orbModels[st] = e
                    val list = ArrayList<PhysicallyBasedMaterial>()
                    e.walk { n -> n.components[ModelComponent::class.java]?.materials?.forEach { m -> (m as? PhysicallyBasedMaterial)?.let { list += it } } }
                    orbMats[st] = list
                    Log.i(TAG, "PROBE orb $st GLB materials: ${list.size} PBR (warm tint ${if (list.isEmpty()) "unavailable" else "on"})")
                }
            }
            val pivot = Entity().apply { setName("ringPivot"); enabled = false }
            content.addChild(pivot)
            ringThin = ModelEntity(thinM, ringMat!!).apply { setName("ringThin") }.also { pivot.addChild(it) }
            ringThick = ModelEntity(thickM, ringBandMat!!).apply { setName("ringThick"); enabled = false }.also { pivot.addChild(it) }
            ringPivot = pivot
            for ((st, d) in arcMeshes) {
                val segs = d.await().mapIndexed { i, m -> ModelEntity(m, arcMat!!).apply { setName("arc:${st.name}:$i"); enabled = false } }
                Entity().apply { setName("arc:${st.name}"); segs.forEach { addChild(it) }; content.addChild(this) }
                arcs[st] = segs
            }
            tagEnt = ModelEntity(tagQ, tagMat!!).apply { setName("tag"); enabled = false; content.addChild(this) }
            capEnt = ModelEntity(capQ, capMat!!).apply {
                setName("caption"); enabled = false; content.addChild(this)
                at(0f, Geo.CAP_Y, Geo.CAP_Z)
                transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), (-Geo.CAP_TILT_DEG * PI / 180).toFloat()))
            }
            // recall stone: holder carries the collider (box centred 0.0385 up, so it sits on the table) + system hover
            val stone = Entity().apply {
                setName(Names.RECALL)
                components.set(CollisionComponent(collisionShape = listOf(stoneShape.await().offsetByTranslation(Vector3(0f, Geo.STONE_HIT / 2, 0f)).keep()), physicsMaterial = physics))
                components.set(InteractableComponent())
                components.set(HoverEffectComponent())
                content.addChild(this)
                at(Geo.STONE_X, 0f, Geo.STONE_Z)
            }
            ModelEntity(pebbleM, stoneMat!!).apply { setName("pebble"); stone.addChild(this) }
            for (k in -1..1) ModelEntity(dotM, dotMat!!).apply { setName("dot$k"); stone.addChild(this); at(k * 0.009f, Geo.STONE_H - 0.0008f, 0.002f) }
            stoneHolder = stone
            hoverEnt = ModelEntity(hoverQ, hoverMat!!).apply { setName("hover"); enabled = false; content.addChild(this); at(Geo.HOVER_X, Geo.HOVER_Y, Geo.STONE_Z) }
            buildLight()
        }
        applyStyle(s0.style)
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms cradles=${cradles.keys} orbs=${orbModels.keys}")
        prewarm()
    }

    /** Key light (design lighting.keyLight: warm ~4500 K from the upper left front 45 deg). Intensity is lux-scale (build.md 2026-11-12). */
    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            light.components.set(DirectionalLightComponent(Color4(1f, 0xE6 / 255f, 0xCC / 255f, 1f), LIGHT_LUX, true))
            // shines along local -Z: yaw -45 deg (from the left), pitch -45 deg (from above), coming from the front-left
            val q = Quat(Vector3(0f, 1f, 0f), (-135 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-45 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
            Log.i(TAG, "PROBE light: DirectionalLightComponent($LIGHT_LUX, shadow=true) added")
        }.onFailure { Log.w(TAG, "PROBE light unavailable: $it") }
    }

    /** Pre-renders every hint string of the current language off the main thread (first-touch frame stays light). */
    private fun prewarm() {
        val zh = isZh(graph.settings.value)
        scope.launch(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            for (h in Hint.all() + listOf(Hint.CapCount(1, 18), Hint.Tag(io.embodit.zenorb.domain.Phase.IN, 4), Hint.HoverRecall)) {
                texture(h.text(zh), kindOf(h))
            }
            Log.i(TAG, "PREWARM hint textures ${texCache.size} in ${SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    /** Text quad kinds: 't' tag beside the orb, 'c' caption in front, 'h' hover word over the recall stone. */
    private fun kindOf(h: Hint): Char = when (h) { is Hint.Tag, Hint.TagPaused -> 't'; Hint.HoverRecall -> 'h'; else -> 'c' }

    private fun key(text: String, kind: Char) = "$text|$kind|$testF"

    /** Cached texture for [text]; null while it is being made (created on IO). */
    private fun texture(text: String, kind: Char): TextureResource? {
        val k = key(text, kind)
        synchronized(texCache) { texCache[k]?.let { return it }; if (!texBusy.add(k)) return null }
        val make = {
            val s = if (testF) "F↑ $text" else text
            val bmp = when (kind) {
                't' -> HintTex.draw(s, Geo.TAG_W, Geo.TAG_H, true)
                'h' -> HintTex.draw(s, Geo.HOVER_W, Geo.HOVER_H, false)
                else -> HintTex.draw(s, Geo.CAP_W, Geo.CAP_H, false)
            }
            val t = runCatching { TextureResource.create(bmp).keep() }.onFailure { Log.w(TAG, "TEX $text: $it") }.getOrNull()
            synchronized(texCache) { texBusy.remove(k); if (t != null) texCache[k] = t }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { scope.launch(Dispatchers.IO) { make() }; return null }
        make()
        return synchronized(texCache) { texCache[k] }
    }

    fun isZh(s: Settings): Boolean = when (s.language) {
        Language.ZH -> true
        Language.EN -> false
        Language.SYSTEM -> Locale.getDefault().language == "zh"
    }

    fun applySettings(s: Settings) {
        if (built && s.style != shownStyle) applyStyle(s.style)
        if (built) prewarm()
    }

    private fun applyStyle(st: Style) {
        for ((k, e) in cradles) e.enabled = k == st
        for ((k, e) in orbModels) e.enabled = k == st
        shadowMat?.setBaseColor(rgb4(st.shadow))
        ringMat?.setBaseColor(rgb4(st.ring))
        ringBandMat?.setBaseColor(rgb4(st.ring, 1.08f))
        arcMat?.setBaseColor(rgb4(st.accent))
        arcFadedMat?.setBaseColor(rgb4(st.accent, 0.45f))
        stoneMat?.setBaseColor(rgb4(st.stone, if (stoneLit) 1.15f else 1f))
        dotMat?.setBaseColor(rgb4(st.accent, 1.15f))
        for ((k, segs) in arcs) if (k != st) segs.forEach { it.enabled = false }
        shownStyle = st; shownArcN = -1; shownTint = -1f; shownEmit = -1f
        Log.i(TAG, "STYLE $st")
    }

    // ------------------------------------------------------------------ per frame

    fun onFrame(dt: Float) {
        if (!built) return
        frames++
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - fpsT0 >= 5000) { fps = frames * 1000f / (nowMs - fpsT0); Log.i(TAG, "PERF fps=${"%.1f".format(fps)} breath=${flow.breath} focus=${flow.focusSt}"); frames = 0; fpsT0 = nowMs }
        if (graph.loaded) flow.tick(dt)
        val st = shownStyle ?: return
        val zh = isZh(graph.settings.value)
        var tagText: String?; var capText: String?
        synchronized(flow.lock) {
            val v = flow.view
            val s = Geo.scale(v.f)
            val y = Geo.orbY(v.f) + v.bump
            orbHolder?.let { h -> h.at(0f, y, 0f); h.transform().setScaleVector(Vector3(s, s, s)) }
            // the orb's own breath light (user 2026-11-13): brighter / warmer glow in, dimmer out, still in the holds;
            // warm tint in band (design feedback: base colour 12 % toward the style's warm colour); 20 % dim while focusing
            val tint = ((v.warm * 100).toInt() * 1_000_000 + (v.dim * 100).toInt() * 1000 + (v.glow * 200).toInt()).toFloat()
            if (tint != shownTint) {
                shownTint = tint
                val k = (1f - 0.2f * v.dim) * io.embodit.zenorb.domain.OrbMotion.brightness(v.glow)
                val w = 0.12f * v.warm
                fun ch(sh: Int) = (st.warm shr sh and 255) / 255f / 0.85f
                val c = Color4((1 - w + w * ch(16)) * k, (1 - w + w * ch(8)) * k, (1 - w + w * ch(0)) * k, 1f)
                orbMats[st]?.forEach { m -> runCatching { m.setBaseColor(c) } }
                val e = io.embodit.zenorb.domain.OrbMotion.emission(v.glow, v.inRound) * (1f - 0.5f * v.dim)
                if (kotlin.math.abs(e - shownEmit) > 0.002f) {
                    shownEmit = e
                    val em = rgb4(st.warm, e)
                    orbMats[st]?.forEach { m -> runCatching { m.setEmissiveColor(em) } }
                }
            }
            // guide ring
            ringPivot?.let { p ->
                p.enabled = v.ringOn
                if (v.ringOn) {
                    val k = Geo.ringR(v.fStar) / R0
                    p.at(0f, Geo.orbY(v.fStar), 0f)
                    p.transform().setScaleVector(Vector3(k, 1f, k))
                    ringThin?.enabled = !v.inBand
                    ringThick?.enabled = v.inBand
                }
            }
            // focus arc
            val n = v.arc?.let { ceil(it * 36).toInt() } ?: 0
            if (n != shownArcN || v.arcFaded != shownArcFaded) {
                val m = if (v.arcFaded) arcFadedMat else arcMat
                arcs[st]?.forEachIndexed { i, e ->
                    e.enabled = i < n
                    if (v.arcFaded != shownArcFaded) m?.let { mm -> e.components[ModelComponent::class.java]?.materials?.set(0, mm) }
                }
                shownArcN = n; shownArcFaded = v.arcFaded
            }
            tagText = v.tag?.text(zh)
            capText = v.caption?.text(zh)
            tagEnt?.at(Geo.radius(v.f) + Geo.TAG_GAP, Geo.orbY(v.f), 0f)
        }
        updateText(tagText, 't')
        updateText(capText, 'c')
        stoneFrame(dt, zh)
    }

    /** Recall stone: hover word after 0.15 s, gone 0.3 s after leaving, stone 15 % lighter; a press sinks it 2 mm (120 ms). */
    private fun stoneFrame(dt: Float, zh: Boolean) {
        val st = shownStyle ?: return
        if (stoneHovered) { hoverOnT += dt; hoverOffT = 0f } else { hoverOffT += dt; hoverOnT = 0f }
        val want = if (hoverShown) hoverOffT < 0.3f else hoverOnT >= 0.15f
        if (want != hoverShown) { hoverShown = want; Log.i(TAG, "HOVER recall ${if (want) "on" else "off"}") }
        if (want != stoneLit) { stoneLit = want; stoneMat?.setBaseColor(rgb4(st.stone, if (want) 1.15f else 1f)) }
        updateText(if (hoverShown) Hint.HoverRecall.text(zh) else null, 'h')
        val p = stonePressT
        if (p >= 0f) {
            val q = p + dt
            stonePressT = if (q > 0.12f) -1f else q
            stoneHolder?.at(Geo.STONE_X, if (q > 0.12f) 0f else -0.002f * kotlin.math.sin(PI.toFloat() * q / 0.12f), Geo.STONE_Z)
        }
    }

    fun pressStone() { stonePressT = 0f }

    private fun updateText(text: String?, kind: Char) {
        val e = (when (kind) { 't' -> tagEnt; 'h' -> hoverEnt; else -> capEnt }) ?: return
        val shown = when (kind) { 't' -> tagShown; 'h' -> hoverTextShown; else -> capShown }
        fun mark(v: String?) { when (kind) { 't' -> tagShown = v; 'h' -> hoverTextShown = v; else -> capShown = v } }
        if (text == null || text.isEmpty()) { if (e.enabled) e.enabled = false; mark(null); return }
        if (text == shown && e.enabled) return
        val tex = texture(text, kind) ?: return      // keep the previous words for the one or two frames it takes
        (when (kind) { 't' -> tagMat; 'h' -> hoverMat; else -> capMat })?.setBaseColorTexture(tex)
        e.enabled = true
        mark(text)
        Log.i(TAG, "HINT ${when (kind) { 't' -> "tag"; 'h' -> "hover"; else -> "caption" }} \"$text\"")
    }

    fun refreshText() { tagShown = null; capShown = null; hoverTextShown = null }

    fun dispose() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        /** Guide ring mesh radius; the pivot scales it to r(f*) + 0.012. */
        const val R0 = 0.062f
        const val LIGHT_LUX = 1500f
    }
}
