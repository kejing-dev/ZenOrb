package io.embodit.zenorb.ui.orb

import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.zenorb.domain.Sfx
import io.embodit.zenorb.domain.TapRule
import io.embodit.zenorb.platform.Graph
import io.embodit.zenorb.platform.TAG
import io.embodit.zenorb.ui.orb.input.BoardInputConfig
import io.embodit.zenorb.ui.orb.input.BoardInputListener
import io.embodit.zenorb.ui.orb.input.BoardInputLog
import io.embodit.zenorb.ui.orb.input.InputSource
import io.embodit.zenorb.ui.orb.input.Offset3M
import io.embodit.zenorb.ui.orb.input.TargetResolver
import io.embodit.zenorb.ui.orb.input.boardInput
import kotlin.math.hypot

/** Runs the volume's per-frame motion inside the ECS (never in Compose). */
class OrbFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
    }
}

internal object SceneHolder {
    @Volatile var scene: OrbScene? = null
}

/**
 * The orb volume (default container). Opens the control window beside it, shows the orb and routes taps / drags on the
 * orb to the flow through the factory board-input template (hand ray, controller ray and eye-hand alike, D50).
 */
@Composable
fun OrbScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scope = rememberCoroutineScope()
    val scene = remember { OrbScene(context.applicationContext, graph, scope) }
    val listener = remember(graph, scene) { OrbListener(graph, scene) }
    val converter = com.pico.spatial.ui.platform.LocalPhysicalLengthConverter.current
    scene.metersPerPx = with(converter) { converter.dpToLength(1f.toDp(), com.pico.spatial.ui.platform.LengthUnit.Meters) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.open("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> graph.onForeground()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    Log.i(TAG, "LIFECYCLE resume panel=${graph.window.panelState}")
                    // (the platform gives no callback when the launcher icon is tapped again; the recall stone is the way back, T10)
                    if (graph.window.panelState == "ON_DESTROY") graph.window.open("resume")
                }
                androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> graph.window.onVolumeLifecycle("ON_DESTROY")
                androidx.lifecycle.Lifecycle.Event.ON_CREATE -> graph.window.onVolumeLifecycle("ON_CREATE")
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        Active.listener = listener
        registerSystem<OrbFrameSystem>()
        onDispose {
            unregisterSystem<OrbFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            if (Active.listener === listener) Active.listener = null
            scene.dispose()
        }
    }

    LaunchedEffect(settings) { scene.applySettings(settings) }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .boardInput(remember { TargetResolver.byName(Names::target) }, listener, BoardInputConfig(dragStartM = TapRule.DRAG_M, tapMaxMs = TapRule.TAP_MS, logTag = TAG)),
        initial = { content, _ ->
            listener.content = content
            content.addEntity(scene.root)
            scene.build()
        },
    )
}

/** For the debug broadcast (debug builds): the live listener. */
internal object Active {
    @Volatile var listener: OrbListener? = null
}

/**
 * Tap / drag on the orb -> flow (design followSpec.drag): only the vertical travel counts. The press converts the
 * view's px to content metres with the view's own conversion, so a system-scaled volume (0.8x .. 2x, N2) maps the same:
 * dY_content = dY_physical x (content m per physical m).
 */
internal class OrbListener(private val graph: Graph, private val scene: OrbScene) : BoardInputListener<String> {
    @Volatile var content: SpatialViewContent? = null
    /** Content metres per physical metre at the press (1 / world scale). */
    var scale = 1f; private set
    private var dragging = false

    override fun onPress(target: String?, source: InputSource, position: Offset3D) {
        val c = content ?: return
        runCatching {
            val p = c.convertPosition(Vector3(position.x, position.y, position.z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
            val q = c.convertPosition(Vector3(position.x + 1000f, position.y, position.z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
            val perPxEntity = hypot(q.x - p.x, hypot(q.y - p.y, q.z - p.z)) / 1000f
            val perPxPhysical = scene.metersPerPx
            scale = if (perPxPhysical > 0f) (perPxEntity / perPxPhysical).coerceIn(0.3f, 3f) else 1f
            BoardInputLog.write(TAG, "INPUT press src=${source.label} target=$target scale=${"%.2f".format(scale)}")
        }.onFailure { Log.w(TAG, "INPUT press convert failed: $it") }
    }

    override fun onTap(target: String?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} target=$target")
        when (target) {
            Names.ORB -> graph.flow.orbTap()
            Names.RECALL -> recall("stone")
        }
    }

    /** The recall stone 「⋯」 (design v5 T10): reopen the control window beside the orb (or put it back there). */
    fun recall(why: String) {
        scene.pressStone()
        graph.sounds.play(Sfx.TICK)
        graph.window.recall(why)
    }

    // gate:allow shared-drag 用户 11-12 定留 Shared Space，点球开始/暂停为主路径
    override fun onDragStart(target: String, source: InputSource): Boolean {
        if (target != Names.ORB) return false
        dragging = graph.flow.dragBegin()
        BoardInputLog.write(TAG, "INPUT drag start src=${source.label} target=$target taken=$dragging")
        return dragging
    }

    override fun onDragMove(totalM: Offset3M) {
        if (dragging) graph.flow.dragMove(totalM.y * scale)
    }

    override fun onDragEnd() { if (dragging) graph.flow.dragEnd(); dragging = false }

    override fun onDragCancel() { if (dragging) graph.flow.dragEnd(); dragging = false }

    override fun onHover(target: String?, source: InputSource) { scene.stoneHovered = target == Names.RECALL }

    /** Debug: a drag through the same flow calls ([dys] = cumulative vertical travel in content metres, one per frame). */
    fun inject(dys: List<Float>, release: Boolean = true) {
        if (!graph.flow.dragBegin()) return
        for (d in dys) graph.flow.dragMove(d)
        if (release) graph.flow.dragEnd()
    }
}
