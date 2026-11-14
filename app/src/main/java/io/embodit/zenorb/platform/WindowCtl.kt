package io.embodit.zenorb.platform

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.zenorb.ui.Containers

/**
 * Opens / reopens the control window and tracks both containers (design windowModel, D44 / D45). Shared Space never
 * promises "in front of you": every open is Placement.right(volume) (Main.kt) and the user moves the window with the
 * system handle. No head pose is read anywhere in this app. Exit closes both containers before the tasks are removed.
 */
class WindowCtl {
    var panelState by mutableStateOf("never")
        private set
    val panelOpen: Boolean get() = panelState != "never" && panelState != "ON_DESTROY"
    var volumeState by mutableStateOf("ON_CREATE")
        private set
    val volumeOpen: Boolean get() = volumeState != "ON_DESTROY"

    private var navigator: SpatialNavigator? = null
    private var panelNavigator: SpatialNavigator? = null
    private var reopenPending = false
    private var volumeReopenPending = false
    private val main = Handler(Looper.getMainLooper())
    private var exiting = false
    /** The control window was closed by the user (system ×), not by a reopen / exit: the volume says 「点 ⋯ 叫回控制窗」. */
    var onClosedByUser: (() -> Unit)? = null

    fun attach(nav: SpatialNavigator) { navigator = nav }
    fun detach() { navigator = null }
    fun attachPanel(nav: SpatialNavigator) { panelNavigator = nav }

    /** Open the control window beside the volume. */
    fun open(why: String) {
        Log.i(TAG, "WINDOW open why=$why state=$panelState")
        runCatching { (navigator ?: panelNavigator)?.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
            .onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }

    /** Settings [重开控制窗]: close, wait for ON_DESTROY, open again beside the orb. */
    fun reopen() {
        if (!panelOpen) { open("reopen"); return }
        reopenPending = true
        Log.i(TAG, "WINDOW reopen: close first")
        runCatching { (navigator ?: panelNavigator)?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }
    }

    /** Recall stone / settings [重开控制窗] (design v5 T10): open it beside the orb, or put an open one back there. */
    fun recall(why: String) {
        Log.i(TAG, "WINDOW recall why=$why open=$panelOpen")
        if (panelOpen) reopen() else open(why)
    }

    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
        if (event != "ON_DESTROY") return
        if (reopenPending) { reopenPending = false; main.postDelayed({ open("reopen") }, 250) }
        else if (!exiting) onClosedByUser?.invoke()
    }

    fun onVolumeLifecycle(event: String) {
        volumeState = event
        Log.i(TAG, "VOLUME lifecycle $event")
        if (event == "ON_CREATE" && volumeReopenPending) {
            volumeReopenPending = false
            // the returned orb lands where the system puts it; put the panel beside it again
            main.postDelayed({ if (panelOpen) reopen() }, 800)
        }
    }

    /** S10 [把光球放回来]: ask the system for the default container again, from the control window. */
    fun reopenVolume(): Boolean {
        val nav = panelNavigator ?: navigator ?: return false
        volumeReopenPending = true
        val ok = runCatching { nav.openWindowContainer(Containers.VOLUME, "volume", Bundle()) }
            .onFailure { Log.w(TAG, "VOLUME reopen failed: $it") }.isSuccess
        Log.i(TAG, "VOLUME reopen requested ok=$ok")
        return ok
    }

    /** Debug: the system [×] on the control window. */
    fun closeControlOnly() { runCatching { (navigator ?: panelNavigator)?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) } }

    /** Debug: the system [×] on the volume (S10). */
    fun closeVolumeOnly() { runCatching { navigator?.closeWindowContainer() }.onFailure { Log.w(TAG, "VOLUME close: $it") } }

    /** Exit (D26 / D44): close the control window and the orb volume. */
    fun closeAll() {
        exiting = true
        val nav = navigator ?: panelNavigator ?: return
        runCatching { nav.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
        runCatching { navigator?.closeWindowContainer() }.onFailure { Log.w(TAG, "EXIT close orb: $it") }
        Log.i(TAG, "EXIT closed all window containers")
    }
}
