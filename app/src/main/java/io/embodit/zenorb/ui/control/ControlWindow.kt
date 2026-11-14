package io.embodit.zenorb.ui.control

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.zenorb.R
import io.embodit.zenorb.domain.BreathSt
import io.embodit.zenorb.domain.FocusSt
import io.embodit.zenorb.domain.Language
import io.embodit.zenorb.domain.PatternId
import io.embodit.zenorb.domain.Settings
import io.embodit.zenorb.domain.Style
import io.embodit.zenorb.domain.UiState
import io.embodit.zenorb.platform.Graph
import io.embodit.zenorb.platform.TAG
import io.embodit.zenorb.ui.Confirm
import io.embodit.zenorb.ui.Page
import io.embodit.zenorb.ui.Ui
import io.embodit.zenorb.ui.theme.ZenColors
import kotlin.system.exitProcess

/**
 * The control window (Planar WindowContainer right of the volume, 420 x 488 dp; design panels.control, v0.1.1 taller
 * for 64 dp buttons with 16 dp gaps, deviation d6). One page and a
 * pushed settings page, no TabBar. The header keeps [静音] (always visible, D15 / B31) and [设置]. Every page is a centred
 * column (short pages stay within the 25 % blank rule); <= 7 tappables per screen.
 */
@Composable
fun ControlWindow() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val ui by graph.flow.ui.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val nav = LocalSpatialNavigator.current
    DisposableEffect(owner, nav) {
        graph.window.attachPanel(nav)
        val obs = LifecycleEventObserver { _, e -> graph.window.onPanelLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val pageName = when {
        !graph.window.volumeOpen -> "S10"
        !ui.loaded -> "S00"
        Ui.confirm != null -> "confirm:${Ui.confirm}"
        Ui.page == Page.SETTINGS -> "S09"
        ui.breath == BreathSt.SUMMARY -> "S04"
        ui.breath == BreathSt.READY -> "S03r"
        ui.breath == BreathSt.FOLLOW -> if (ui.lead) "S03" else "S03f"
        ui.breath == BreathSt.PAUSED -> "S05"
        ui.focus == FocusSt.DONE -> "S07"
        ui.focus == FocusSt.RUNNING -> "S06"
        ui.focus == FocusSt.PAUSED -> "S06p"
        ui.welcome -> "S01"
        else -> "S02"
    }
    LaunchedEffect(pageName) { Log.i(TAG, "PAGE $pageName") }

    BlankProbe.page = pageName + "/" + (if (settings.language == Language.EN) "en" else if (settings.language == Language.ZH) "zh" else "sys")
    Box(Modifier.fillMaxSize().onGloballyPositioned { BlankProbe.windowH = it.size.height.toFloat() }.padding(horizontal = 24.dp, vertical = if (pageName == "S09") 10.dp else 20.dp)) {
        when (pageName) {
            "S10" -> VolumeClosedPage(settings, graph)
            "S00" -> LoadingPage()
            "S09" -> SettingsPage(ui, settings, graph)
            "S04" -> SummaryPage(ui, settings, graph)
            "S03r", "S03", "S03f", "S05" -> BreathPage(ui, settings, graph)
            "S07" -> FocusDonePage(ui, settings, graph)
            "S06", "S06p" -> FocusPage(ui, settings, graph)
            "S01" -> WelcomePage(settings, graph)
            "S02" -> HomePage(ui, settings, graph)
            else -> ConfirmPage(Ui.confirm!!, ui, settings, graph)
        }
    }
}

@Composable
internal fun patternName(p: PatternId): String = stringResource(
    when (p) { PatternId.CALM -> R.string.p_calm; PatternId.BOX -> R.string.p_box; PatternId.SLEEP -> R.string.p_sleep; PatternId.BREAK -> R.string.p_break },
)

@Composable
private fun patternTag(p: PatternId): String = stringResource(
    when (p) { PatternId.CALM -> R.string.p_calm_tag; PatternId.BOX -> R.string.p_box_tag; PatternId.SLEEP -> R.string.p_sleep_tag; PatternId.BREAK -> R.string.p_break_tag },
)

/** A centred page between the header and the window bottom. */
@Composable
private fun PageColumn(header: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.onGloballyPositioned { BlankProbe.top = it.boundsInRoot().top }) { header() }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().onGloballyPositioned { BlankProbe.bottom(it.boundsInRoot().bottom) }, verticalArrangement = Arrangement.spacedBy(PAGE_GAP), content = content)
        }
    }
}

/**
 * Short-page blank (profile D44, 「空白的量法」): (content top - window top + window bottom - content bottom) / window
 * height, logged as `LAYOUT <page> blank=..%` for the ui-audit table. Content = header top .. last control bottom.
 */
internal object BlankProbe {
    var page = ""
    var windowH = 0f
    var top = 0f
    private var last = ""
    fun bottom(b: Float) {
        if (windowH <= 0f) return
        val blank = (top + (windowH - b)) / windowH * 100f
        val line = "LAYOUT $page blank=${"%.1f".format(blank)}% top=${top.toInt()} bottom=${b.toInt()} H=${windowH.toInt()}"
        if (line != last) { last = line; Log.i(TAG, line) }
    }
}

/** Header: title + [静音] (always visible, D15) + [设置]. */
@Composable
private fun Header(settings: Settings, graph: Graph, tag: String, showSettings: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt(stringResource(R.string.title), "$tag/title", style = PicoTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        ToggleButton(stringResource(if (settings.muted) R.string.btn_muted else R.string.btn_mute), "$tag/mute", on = settings.muted,
            onClick = { graph.settings.update { it.copy(muted = !it.muted) } },
            icon = if (settings.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        if (showSettings) SecondaryButton(stringResource(R.string.btn_settings), "$tag/settings", onClick = { Ui.page = Page.SETTINGS }, icon = R.drawable.ic_settings)
    }
}

/** Gap between the blocks of a page and the height of the page's main buttons (v0.1.1: window 488 dp, blank <= 25 %). */
private val PAGE_GAP = 40.dp
private val BIG_BTN = 88.dp

/** The big one-line status card (session / summary / focus done). */
@Composable
private fun StatusCard(big: String, small: String?, tag: String, bigLines: Int = 1, minLines: Int = 1) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(ZenColors.PrimaryContainer).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(big, "$tag/big", style = PicoTheme.typography.headlineMedium, color = ZenColors.Ink, maxLines = bigLines, minLines = minLines)
        if (small != null) Txt(small, "$tag/small", style = PicoTheme.typography.bodyMedium, color = ZenColors.InkSecondary, maxLines = 2)
    }
}

@Composable
private fun ButtonRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

private fun mmss(s: Int): String = "%d:%02d".format(s / 60, s % 60)

// ---------------------------------------------------------------------------------------------------- S02 home

@Composable
private fun HomePage(ui: UiState, settings: Settings, graph: Graph) {
    val sel = settings.lastPattern
    PageColumn({ Header(settings, graph, "home") }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // gate:allow tabs-segment rhythm choice for [开始呼吸] on the same page (design panels.control.home), not a page switch
            Segments(PatternId.SELECTABLE.map { it to patternName(it) }, sel, "home/pattern",
                { p -> graph.settings.update { it.copy(lastPattern = p) } }, Modifier.fillMaxWidth())
            Txt(patternTag(sel), "home/tagline", style = PicoTheme.typography.bodyMedium, color = ZenColors.InkSecondary, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
        }
        PrimaryButton(stringResource(R.string.btn_start_breath), "home/start", onClick = { graph.flow.startBreath(sel) }, modifier = Modifier.fillMaxWidth().height(BIG_BTN), icon = R.drawable.ic_play_arrow)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Segments(Settings.FOCUS_LENGTHS.map { it to stringResource(R.string.focus_min, it) }, settings.focusMin, "home/focus-len",
                { m -> graph.settings.update { it.copy(focusMin = m) } }, Modifier.weight(1f), small = true)
            SecondaryButton(stringResource(R.string.btn_start_focus), "home/focus", onClick = { graph.flow.startFocus(settings.focusMin) }, icon = R.drawable.ic_timer)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- S01 welcome

@Composable
private fun WelcomePage(settings: Settings, graph: Graph) {
    PageColumn({ Header(settings, graph, "s01") }) {
        StatusCard(stringResource(R.string.welcome), stringResource(R.string.welcome_sub), "s01", bigLines = 2)
        PrimaryButton(stringResource(R.string.btn_start), "s01/start", onClick = { graph.flow.startBreath() }, modifier = Modifier.fillMaxWidth().height(BIG_BTN), icon = R.drawable.ic_play_arrow)
    }
}

// ---------------------------------------------------------------------------------------------------- S03r / S03 / S05

@Composable
private fun BreathPage(ui: UiState, settings: Settings, graph: Graph) {
    val name = patternName(ui.pattern)
    val (big, small) = when (ui.breath) {
        BreathSt.READY -> stringResource(R.string.ready_state, name) to stringResource(R.string.ready_sub)
        BreathSt.PAUSED -> stringResource(R.string.paused_state) to stringResource(R.string.paused_sub)
        else -> stringResource(R.string.session_state, name, mmss(ui.breathLeftS)) to stringResource(if (ui.lead) R.string.session_sub else R.string.follow_sub)
    }
    PageColumn({ Header(settings, graph, "session") }) {
        StatusCard(big, small, "session")
        ButtonRow {
            if (ui.breath == BreathSt.PAUSED) PrimaryButton(stringResource(R.string.btn_resume), "session/resume", onClick = { graph.flow.resume() }, modifier = Modifier.weight(1f).height(BIG_BTN), icon = R.drawable.ic_play_arrow)
            else SecondaryButton(stringResource(R.string.btn_pause), "session/pause", onClick = { graph.flow.pause() }, modifier = Modifier.weight(1f).height(BIG_BTN), icon = R.drawable.ic_pause)
            SecondaryButton(stringResource(R.string.btn_end), "session/end", onClick = { graph.flow.endBreath() }, modifier = Modifier.weight(1f).height(BIG_BTN), icon = R.drawable.ic_stop)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- S04

@Composable
private fun SummaryPage(ui: UiState, settings: Settings, graph: Graph) {
    val s = ui.summary ?: return
    // design v5 summary: big 「跟了 18 口」 always; small = 「你拖的 12 口里合上 9 口」 when led (>= 3 scored), else the source line
    val big = stringResource(R.string.summary_big, s.breaths)
    val small = when {
        s.low -> stringResource(R.string.summary_low, s.matched, s.scored)
        s.led -> stringResource(R.string.summary_led, s.matched, s.scored)
        else -> stringResource(when (s.pattern) { PatternId.BOX -> R.string.source_box; PatternId.SLEEP -> R.string.source_sleep; else -> R.string.source_calm })
    }
    PageColumn({ Header(settings, graph, "summary") }) {
        StatusCard(big, small, "summary", bigLines = 1)
        ButtonRow {
            PrimaryButton(stringResource(R.string.btn_again), "summary/again", onClick = { graph.flow.startBreath(s.pattern) }, modifier = Modifier.weight(1f).height(BIG_BTN), icon = R.drawable.ic_replay)
            SecondaryButton(stringResource(R.string.btn_done), "summary/done", onClick = { graph.flow.summaryDone() }, modifier = Modifier.weight(1f).height(BIG_BTN), icon = R.drawable.ic_check)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- S06 / S06p / S07

@Composable
private fun FocusPage(ui: UiState, settings: Settings, graph: Graph) {
    val paused = ui.focus == FocusSt.PAUSED
    PageColumn({ Header(settings, graph, "focus") }) {
        StatusCard(
            if (paused) stringResource(R.string.focus_paused_state) else stringResource(R.string.focus_state, ui.focusLeftMin),
            stringResource(if (paused) R.string.focus_paused_sub else R.string.focus_sub), "focus",
        )
        ButtonRow {
            if (paused) PrimaryButton(stringResource(R.string.btn_resume), "focus/resume", onClick = { graph.flow.focusResume() }, modifier = Modifier.weight(1f).height(BIG_BTN), icon = R.drawable.ic_play_arrow)
            else SecondaryButton(stringResource(R.string.btn_pause), "focus/pause", onClick = { graph.flow.focusPause() }, modifier = Modifier.weight(1f).height(BIG_BTN), icon = R.drawable.ic_pause)
            SecondaryButton(stringResource(R.string.btn_drop), "focus/drop", onClick = { Ui.confirm = Confirm.GIVE_UP }, modifier = Modifier.weight(1f).height(BIG_BTN), icon = R.drawable.ic_stop)
        }
    }
}

@Composable
private fun FocusDonePage(ui: UiState, settings: Settings, graph: Graph) {
    PageColumn({ Header(settings, graph, "s07") }) {
        StatusCard(stringResource(R.string.focus_done), stringResource(R.string.focus_today, ui.today.focusDone), "s07", bigLines = 2)
        ButtonRow {
            PrimaryButton(stringResource(R.string.btn_breathe_1), "s07/breathe", onClick = { graph.flow.focusDoneBreathe() }, modifier = Modifier.weight(1f).height(BIG_BTN), icon = R.drawable.ic_play_arrow)
            SecondaryButton(stringResource(R.string.btn_not_now), "s07/later", onClick = { graph.flow.focusDoneLater() }, modifier = Modifier.weight(1f).height(BIG_BTN))
        }
    }
}

// ---------------------------------------------------------------------------------------------------- S09 settings

@Composable
private fun SettingsPage(ui: UiState, settings: Settings, graph: Graph) {
    val langs = listOf(Language.SYSTEM to stringResource(R.string.lang_system), Language.ZH to stringResource(R.string.lang_zh), Language.EN to stringResource(R.string.lang_en))
    val vols = listOf(0 to stringResource(R.string.vol_0), 1 to stringResource(R.string.vol_1), 2 to stringResource(R.string.vol_2))
    val styles = listOf(Style.GLAZE to stringResource(R.string.style_glaze), Style.BRASS to stringResource(R.string.style_brass), Style.MOSS to stringResource(R.string.style_moss))
    val d = ui.today
    val pct = if (d.syncScored > 0) d.syncMatched * 100 / d.syncScored else 0
    // longest page = the window height (488 dp): rows 52 dp (option controls >= 48 dp), buttons 64 dp, 16 dp apart, 10 dp padding
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp).onGloballyPositioned { BlankProbe.top = it.boundsInRoot().top }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryButton(stringResource(R.string.btn_back), "settings/back", onClick = { Ui.page = Page.HOME }, icon = R.drawable.ic_arrow_back)
            Spacer(Modifier.weight(1f))
            // D44: [重开控制窗] on the first screen (QA q1-1); Placement.right(volume), no 「in front of you」 promise
            SecondaryButton(stringResource(R.string.btn_reopen), "settings/reopen", onClick = { graph.window.recall("settings") }, icon = R.drawable.ic_open_in_new)
        }
        OptionRow(stringResource(R.string.settings_language), "settings/lang", rowHeight = 52.dp) {
            Segments(langs, settings.language, "settings/lang", { v -> graph.settings.update { it.copy(language = v) } }, Modifier.weight(1f), small = true, height = 52.dp)
        }
        OptionRow(stringResource(R.string.settings_volume), "settings/volume", rowHeight = 52.dp) {
            Segments(vols, settings.volume, "settings/volume", { v -> graph.settings.update { it.copy(volume = v) } }, Modifier.weight(1f), small = true, height = 52.dp)
        }
        OptionRow(stringResource(R.string.settings_style), "settings/style", rowHeight = 52.dp) {
            Segments(styles, settings.style, "settings/style", { v -> graph.settings.update { it.copy(style = v) } }, Modifier.weight(1f), small = true, height = 52.dp)
        }
        SwitchRow(stringResource(R.string.settings_ambient), "settings/ambient", settings.ambient, { v -> graph.settings.update { it.copy(ambient = v) } }, rowHeight = 52.dp)
        Txt(stringResource(R.string.today_line, (d.breathSeconds + 30) / 60, pct, d.focusDone), "settings/today", style = PicoTheme.typography.bodyMedium,
            color = ZenColors.InkSecondary, modifier = Modifier.fillMaxWidth().height(24.dp))
        ButtonRow(Modifier.onGloballyPositioned { BlankProbe.bottom(it.boundsInRoot().bottom) }) {
            SecondaryButton(stringResource(R.string.btn_clear), "settings/clear", onClick = { Ui.confirm = Confirm.CLEAR }, modifier = Modifier.weight(1f), icon = R.drawable.ic_delete)
            SecondaryButton(stringResource(R.string.btn_quit), "settings/quit", onClick = { Ui.confirm = Confirm.QUIT }, modifier = Modifier.weight(1f), danger = true, icon = R.drawable.ic_logout)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- confirm (S11 + clear + give up)

@Composable
private fun ConfirmPage(kind: Confirm, ui: UiState, settings: Settings, graph: Graph) {
    val context = LocalContext.current
    val focusing = ui.focus == FocusSt.RUNNING || ui.focus == FocusSt.PAUSED
    val text = stringResource(
        when (kind) {
            Confirm.QUIT -> if (focusing) R.string.confirm_quit_focus else R.string.confirm_quit
            Confirm.CLEAR -> R.string.confirm_clear
            Confirm.GIVE_UP -> R.string.confirm_give_up
        },
    )
    PageColumn({ Header(settings, graph, "confirm", showSettings = false) }) {
        StatusCard(text, null, "confirm/${kind.name.lowercase()}", bigLines = 2, minLines = 2)
        ButtonRow {
            when (kind) {
                Confirm.QUIT -> {
                    SecondaryButton(stringResource(R.string.btn_quit_yes), "confirm/quit", onClick = {
                        if (focusing) graph.flow.focusGiveUp()
                        Ui.confirm = null
                        exitApp(context, graph)
                    }, modifier = Modifier.weight(1f).height(BIG_BTN), danger = true, icon = R.drawable.ic_logout)
                    PrimaryButton(stringResource(R.string.btn_stay), "confirm/stay", onClick = { Ui.confirm = null }, modifier = Modifier.weight(1f).height(BIG_BTN))
                }
                Confirm.CLEAR -> {
                    SecondaryButton(stringResource(R.string.btn_ok), "confirm/clear-ok", onClick = { graph.flow.clearHistory(); graph.saveNow("clear"); Ui.confirm = null }, modifier = Modifier.weight(1f).height(BIG_BTN), danger = true, icon = R.drawable.ic_delete)
                    PrimaryButton(stringResource(R.string.btn_not_yet), "confirm/clear-no", onClick = { Ui.confirm = null }, modifier = Modifier.weight(1f).height(BIG_BTN))
                }
                Confirm.GIVE_UP -> {
                    SecondaryButton(stringResource(R.string.btn_ok), "confirm/giveup-ok", onClick = { graph.flow.focusGiveUp(); Ui.confirm = null }, modifier = Modifier.weight(1f).height(BIG_BTN), danger = true, icon = R.drawable.ic_stop)
                    PrimaryButton(stringResource(R.string.btn_not_yet), "confirm/giveup-no", onClick = { Ui.confirm = null }, modifier = Modifier.weight(1f).height(BIG_BTN))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- S00 / S10

@Composable
private fun LoadingPage() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Txt(stringResource(R.string.s00), "s00/text", style = PicoTheme.typography.titleMedium)
    }
}

/** S10: the volume was closed with the system [×]; bring-back and mute stay. */
@Composable
private fun VolumeClosedPage(settings: Settings, graph: Graph) {
    if (Ui.page == Page.SETTINGS) { SettingsPage(graph.flow.ui.value, settings, graph); return }
    PageColumn({ Header(settings, graph, "s10") }) {
        StatusCard(stringResource(R.string.s10), null, "s10")
        PrimaryButton(stringResource(R.string.btn_bring), "s10/bring", onClick = { graph.window.reopenVolume() }, modifier = Modifier.fillMaxWidth().height(BIG_BTN), icon = R.drawable.ic_open_in_new)
        // design S10: the other actions stay visible but greyed until the orb is back
        ButtonRow {
            SecondaryButton(stringResource(R.string.btn_start_breath), "s10/start", onClick = {}, modifier = Modifier.weight(1f), enabled = false, icon = R.drawable.ic_play_arrow)
            SecondaryButton(stringResource(R.string.btn_start_focus), "s10/focus", onClick = {}, modifier = Modifier.weight(1f), enabled = false, icon = R.drawable.ic_timer)
        }
    }
}

internal fun exitApp(context: Context, graph: Graph) {
    Log.i(TAG, "EXIT save + release + close windows + finishAndRemoveTask + exitProcess")
    runCatching { graph.onBackground() }
    runCatching { graph.sounds.release() }
    runCatching { graph.window.closeAll() }
    Handler(Looper.getMainLooper()).postDelayed({
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
        }
        Handler(Looper.getMainLooper()).postDelayed({ exitProcess(0) }, 500)
    }, 300)
}

