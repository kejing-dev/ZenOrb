package io.embodit.zenorb.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.embodit.zenorb.domain.BreathSt
import io.embodit.zenorb.domain.Curve
import io.embodit.zenorb.domain.Geo
import io.embodit.zenorb.domain.Language
import io.embodit.zenorb.domain.PatternId
import io.embodit.zenorb.domain.Phase
import io.embodit.zenorb.domain.Style
import io.embodit.zenorb.ui.Confirm
import io.embodit.zenorb.ui.Page
import io.embodit.zenorb.ui.Ui
import io.embodit.zenorb.ui.orb.Active
import io.embodit.zenorb.ui.orb.SceneHolder
import kotlin.math.sin

/**
 * QA / self-test hooks (debug builds only; design verification.debugHooks):
 * `adb shell am broadcast -a io.embodit.zenorb.DEBUG -p io.embodit.zenorb --es cmd <cmd> --es a "<arg>"`.
 * Every command goes through the same Flow calls as the panel buttons and the 3D input (drag / follow = dragBegin /
 * dragMove / dragEnd with content-metre travel, tap = orbTap), never a state shortcut. `hold` only freezes the clock
 * and pins the orb for a still (D36).
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = intent.getStringExtra("a")?.trim('"', ' ').orEmpty()
        val g = Graph.from(context)
        main.post { runCatching { run(context, g, cmd, arg) }.onFailure { Log.e(TAG, "DEBUG $cmd failed", it) } }
    }

    private fun kv(arg: String): Map<String, String> = arg.split(" ").filter { "=" in it }.associate { val (k, v) = it.split("=", limit = 2); k.lowercase() to v }

    private fun run(context: Context, g: Graph, cmd: String, arg: String) {
        val f = g.flow
        Log.i(TAG, "DEBUG $cmd $arg")
        when (cmd) {
            // states through the real calls
            "tap" -> f.orbTap()
            "start" -> f.startBreath(PatternId.parse(arg))
            "pause" -> f.pause()
            "resume" -> f.resume()
            "end" -> f.endBreath()
            "done" -> f.summaryDone()
            "focus" -> f.startFocus(arg.toIntOrNull() ?: g.settings.value.focusMin)
            "focuspause" -> f.focusPause()
            "focusresume" -> f.focusResume()
            "giveup" -> Ui.confirm = Confirm.GIVE_UP
            "giveupok" -> { f.focusGiveUp(); Ui.confirm = null }        // = [确定] on the give-up confirm
            "focusfast" -> f.focusFast = arg != "off"
            "pattern" -> PatternId.parse(arg)?.let { p -> g.settings.update { it.copy(lastPattern = p) } }
            "style" -> g.settings.update { it.copy(style = Style.valueOf(arg.uppercase())) }
            "handsfree" -> g.settings.update { it.copy(handsFree = arg != "off") }
            "lang" -> g.settings.update { it.copy(language = when (arg) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "sound" -> g.settings.update { it.copy(muted = arg == "off") }
            "volume" -> g.settings.update { it.copy(volume = arg.toInt().coerceIn(0, 2)) }
            "page" -> { Ui.confirm = null; Ui.page = if (arg == "settings") Page.SETTINGS else Page.HOME }
            "confirm" -> Ui.confirm = when (arg) { "quit" -> Confirm.QUIT; "clear" -> Confirm.CLEAR; "giveup" -> Confirm.GIVE_UP; else -> null }
            "welcome" -> f.debugWelcome()
            // drags (the same OrbListener / Flow path as a pinch-and-drag on the orb)
            "drag" -> { // a = "dy1,dy2,..." cumulative content metres, one per frame
                val dys = arg.split(",").mapNotNull { it.trim().toFloatOrNull() }
                Active.listener?.inject(dys, release = true)
            }
            "grab" -> Active.listener?.inject(listOf(arg.toFloatOrNull() ?: 0f), release = false)
            "release" -> f.dragEnd()
            "follow" -> follow(g, kv(arg))
            "stopfollow" -> { follower?.let { main.removeCallbacks(it) }; follower = null; f.dragEnd() }
            // stills
            "hold" -> { // hold [S03] phase=in f=0.5 inband=true
                val m = kv(arg)
                val phase = m["phase"]?.let { p -> when (p) { "in" -> Phase.IN; "out" -> Phase.OUT; "holdin", "hold" -> Phase.HOLD_IN; "holdout" -> Phase.HOLD_OUT; else -> null } }
                f.debugPin(phase, m["f"]?.toFloatOrNull(), m["inband"]?.toBooleanStrictOrNull())
            }
            "unhold" -> f.debugRelease()
            "seek" -> f.debugSeek(arg.toFloat())
            "testf" -> { SceneHolder.scene?.testF = arg != "off"; SceneHolder.scene?.refreshText() }
            "closepanel" -> g.window.closeControlOnly()
            "openpanel" -> g.window.open("debug")
            "closevolume" -> g.window.closeVolumeOnly()
            "bring" -> g.window.reopenVolume()
            "save" -> g.saveNow("debug")
            "fresh" -> { g.store.clear(); g.settings.update { it.copy(welcomed = false) }; f.clearHistory(); f.debugWelcome() }
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", arg == "on").apply()
            "state", "dump" -> Log.i(TAG, "DUMP breath=${f.breath} focus=${f.focusSt} pattern=${f.pattern} t=${"%.2f".format(f.t)} f=${"%.3f".format(f.f)} held=${f.held} " +
                "results=${f.judge.results} band=${f.judge.inBand} corr=${f.judge.correction} page=${Ui.page} confirm=${Ui.confirm} panel=${g.window.panelState} " +
                "volume=${g.window.volumeState} fps=${SceneHolder.scene?.fps} scale=${Active.listener?.scale} settings=${g.settings.value} today=${f.todayStats()}")
            else -> Log.w(TAG, "DEBUG unknown $cmd")
        }
    }

    /**
     * Drives a whole round through the drag path (T14 / T1): every 33 ms the "hand" moves to the target f* delayed by
     * lag (s) and scaled by amp, plus a sine wobble; mode=still / reverse for the negative cases.
     */
    private fun follow(g: Graph, m: Map<String, String>) {
        val f = g.flow
        val lag = m["lag"]?.toFloatOrNull() ?: 0.4f
        val amp = m["amp"]?.toFloatOrNull() ?: 0.8f
        val mode = m["mode"] ?: "follow"
        follower?.let { main.removeCallbacks(it) }
        if (f.breath == BreathSt.NONE || f.breath == BreathSt.SUMMARY) f.startBreath()
        var started = false
        var f0 = 0f
        val r = object : Runnable {
            override fun run() {
                if (f.breath == BreathSt.SUMMARY || f.breath == BreathSt.NONE) { f.dragEnd(); follower = null; Log.i(TAG, "FOLLOW done"); return }
                if (!started) { started = Active.listener?.let { l -> l.inject(emptyList(), release = false); true } ?: false; f0 = f.f; if (!started) { main.postDelayed(this, 100); return } }
                val target = when (mode) {
                    "still" -> 0f
                    "reverse" -> 1f - Curve.at(f.pattern, f.t).fStar
                    else -> Curve.at(f.pattern, (f.t - lag).coerceAtLeast(0f)).fStar * amp + 0.02f * sin(f.t * 7f)
                }
                f.dragMove((target - f0) * Geo.LIFT)
                main.postDelayed(this, 33)
            }
        }
        follower = r
        main.post(r)
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var follower: Runnable? = null
    }
}
