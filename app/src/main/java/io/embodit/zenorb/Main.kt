package io.embodit.zenorb

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.zenorb.ui.Containers
import io.embodit.zenorb.ui.control.ControlWindow
import io.embodit.zenorb.ui.orb.OrbScreen
import io.embodit.zenorb.ui.theme.ZenTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the orb volume (default container,
 * 0.32 x 0.30 x 0.26 m from the manifest) and the control window (Planar 420 x 424 dp) to its right. Every open uses
 * Placement.right(volume) (D44: Shared Space never promises "in front of you").
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            ZenTheme { OrbScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 420.dp, height = 424.dp),
            placement = { containers.firstOrNull { it.id == Containers.VOLUME }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            ZenTheme {
                Box(Modifier.windowConstraints(minWidth = 420.dp, minHeight = 424.dp, maxWidth = 640.dp, maxHeight = 424.dp)) { ControlWindow() }
            }
        }
    }
