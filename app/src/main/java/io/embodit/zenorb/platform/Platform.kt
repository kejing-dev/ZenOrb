package io.embodit.zenorb.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.zenorb.data.SaveStore
import io.embodit.zenorb.data.SettingsStore
import io.embodit.zenorb.domain.Flow
import io.embodit.zenorb.domain.Saved
import io.embodit.zenorb.domain.Settings
import io.embodit.zenorb.domain.Sfx
import io.embodit.zenorb.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

const val TAG = "ZenOrb"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.zenorb.ui.orb.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** Process-wide objects shared by the volume and the control window (native-window-move.md §3.4). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = SaveStore(context)
    val window = WindowCtl()
    val flow = Flow(
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onSfx = { sounds.play(it) },
        onFocusDone = { Handler(Looper.getMainLooper()).post { if (!window.panelOpen) window.open("focus-done") } },
        log = { Log.i(TAG, it) },
    )
    @Volatile var loaded = false
    private var saveJob: Job? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        window.onClosedByUser = { flow.panelClosed() }
        scope.launch {
            settings.settings.collect { s ->
                sounds.muted = s.muted
                sounds.volumeLevel = s.volume
                sounds.ambientOn = s.ambient
                flow.republish()
            }
        }
        scope.launch(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            if (context.getSharedPreferences("debug", Context.MODE_PRIVATE).getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 12 s"); delay(12_000) }
            val saved = store.load()
            flow.restore(saved ?: Saved())
            loaded = true
            Log.i(TAG, "LOAD ${if (saved != null) "restored" else "fresh"} in ${SystemClock.elapsedRealtime() - t0} ms")
        }
        // design dataModel.storage: 1 s debounce after a change, immediately on background
        scope.launch {
            var last: Pair<Any?, Any?>? = null
            flow.ui.collect { u ->
                if (!loaded) return@collect
                val key = u.today to u.focus
                if (key != last) { last = key; saveSoon(1000) }
            }
        }
        tickClock()
    }

    /** 1 s clock on the main thread: the focus block ends on time even when the volume renders no frames (N8). */
    private fun tickClock() {
        main.postDelayed({ flow.clock(); tickClock() }, 1000)
    }

    private fun saveSoon(ms: Long) {
        saveJob?.cancel()
        saveJob = scope.launch { delay(ms); saveNow("event") }
    }

    fun saveNow(why: String) {
        if (!loaded) return
        val snap = flow.snapshot()
        scope.launch(Dispatchers.IO) { store.save(snap); Log.i(TAG, "SAVE $why days=${snap.days.size} focus=${snap.focus != null}") }
    }

    fun onForeground() { sounds.foreground = true }

    fun onBackground() {
        sounds.foreground = false
        flow.onBackground()
        saveNow("background")
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Event sounds (design sound.list, user 2026-11-13 「声音要再 peace 宁静一点，有点禅意一点」): CC0 singing-bowl
 * recordings only, slow attack (>= 250 ms), long natural tail, nothing above 4 kHz, in / out a major third apart, 6 dB
 * under v0.1.0; no wood tick. Variant rotation and +-3 % pitch (D51); one instance per event (a new one stops the
 * previous). Optional, off by default: a very quiet creek bed (settings 「环境声」, B24 exception the user asked for).
 */
class SoundPlayer(private val context: Context) {
    @Volatile var muted = false
        set(v) { field = v; updateAmbient() }
    @Volatile var volumeLevel = 1
        set(v) { field = v; updateAmbient() }
    @Volatile var ambientOn = false
        set(v) { field = v; updateAmbient() }
    @Volatile var foreground = true
        set(v) { field = v; updateAmbient() }

    private val pool = SoundPool.Builder().setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(7)
    private val playing = HashMap<Sfx, Int>()
    private val lastVariant = HashMap<Sfx, Int>()
    private var bed: android.media.MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (base in listOf("inhale", "exhale", "tick", "done", "focus_end")) {
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav")) {
                runCatching { found += context.assets.openFd("sounds/$name").use { fd -> pool.load(fd, 1) } }
            }
            if (found.isNotEmpty()) ids[base] = found else Log.w(TAG, "no sound $base")
        }
    }

    fun gain(): Float = Settings.VOLUME_PCT[volumeLevel.coerceIn(0, 2)] / 100f

    fun play(s: Sfx) {
        if (muted) { Log.i(TAG, "SFX $s (muted)"); return }
        val name = when (s) { Sfx.INHALE -> "inhale"; Sfx.EXHALE -> "exhale"; Sfx.TICK -> "tick"; Sfx.DONE -> "done"; Sfx.FOCUS_END -> "focus_end" }
        val variants = ids[name] ?: return
        var v = random.nextInt(variants.size)
        if (v == lastVariant[s] && variants.size > 1) v = (v + 1) % variants.size
        lastVariant[s] = v
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        val jitter = JITTER.getValue(s)
        val rate = 1f - jitter + random.nextFloat() * 2 * jitter
        synchronized(playing) {
            playing.remove(s)?.let { pool.stop(it) }
            // phase bells: one at a time across in / out too
            if (s == Sfx.INHALE) playing.remove(Sfx.EXHALE)?.let { pool.stop(it) }
            if (s == Sfx.EXHALE) playing.remove(Sfx.INHALE)?.let { pool.stop(it) }
            val g = gain() * LEVEL.getValue(s)
            val stream = pool.play(id, g, g, 1, 0, rate)
            if (stream != 0) playing[s] = stream
        }
        Log.i(TAG, "SFX $s v=$v rate=${"%.3f".format(rate)}")
    }

    /** The creek bed: plays while enabled, not muted and the app is in front; released when off (open -> close pairs, D41). */
    private fun updateAmbient() {
        main.post {
            val want = ambientOn && !muted && foreground
            val g = gain() * AMBIENT_LEVEL
            if (want && bed == null) {
                bed = runCatching {
                    android.media.MediaPlayer().apply {
                        context.assets.openFd("sounds/ambient_creek.ogg").use { fd -> setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        isLooping = true
                        setVolume(g, g)
                        prepare(); start()
                    }
                }.onFailure { Log.w(TAG, "AMBIENT failed: $it") }.getOrNull()
                Log.i(TAG, "AMBIENT on gain=${"%.3f".format(g)} ok=${bed != null}")
            } else if (!want && bed != null) {
                runCatching { bed?.stop(); bed?.release() }; bed = null
                Log.i(TAG, "AMBIENT off")
            } else bed?.setVolume(g, g)
        }
    }

    fun release() = runCatching { ambientOn = false; main.post { runCatching { bed?.release() }; bed = null }; pool.release() }

    companion object {
        /** Playback level per event on top of the file (files peak -1 dBFS): -6 dB under v0.1.0 for the bells (user 2026-11-13). */
        val LEVEL = mapOf(Sfx.INHALE to 0.5f, Sfx.EXHALE to 0.5f, Sfx.TICK to 0.32f, Sfx.DONE to 0.5f, Sfx.FOCUS_END to 0.56f)
        val JITTER = mapOf(Sfx.INHALE to 0.03f, Sfx.EXHALE to 0.03f, Sfx.TICK to 0.03f, Sfx.DONE to 0f, Sfx.FOCUS_END to 0f)
        /** The creek is a bed, not a sound: about 26 dB under the bells' peak. */
        const val AMBIENT_LEVEL = 0.06f
    }
}
