package io.embodit.zenorb.data

import android.content.Context
import android.util.Log
import io.embodit.zenorb.domain.Day
import io.embodit.zenorb.domain.FocusActive
import io.embodit.zenorb.domain.Language
import io.embodit.zenorb.domain.PatternId
import io.embodit.zenorb.domain.Saved
import io.embodit.zenorb.domain.Settings
import io.embodit.zenorb.domain.Style
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** files/zenorb.json (design dataModel.storage): atomic write (temp, read-back, rename), previous copy kept as .bak. */
class SaveStore(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "zenorb.json")
    private val backup = File(dir, "zenorb.bak")

    fun load(): Saved? = listOf(file, backup).firstNotNullOfOrNull { f ->
        runCatching { if (f.exists()) SaveCodec.decode(f.readText()) else null }
            .onFailure { Log.w("ZenOrb", "SAVE unreadable ${f.name}: $it") }.getOrNull()
    }

    @Synchronized
    fun save(v: Saved) {
        runCatching {
            val text = SaveCodec.encode(v)
            val tmp = File(dir, "zenorb.tmp")
            tmp.writeText(text)
            SaveCodec.decode(tmp.readText())
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("ZenOrb", "SAVE failed", it) }
    }

    fun clear() { file.delete(); backup.delete() }
}

object SaveCodec {
    fun encode(s: Saved): String = JSONObject().apply {
        put("v", 1)
        put("days", JSONArray().apply {
            s.days.forEach { d ->
                put(JSONObject().put("date", d.date).put("breathSeconds", d.breathSeconds).put("breaths", d.breaths)
                    .put("syncScored", d.syncScored).put("syncMatched", d.syncMatched).put("focusDone", d.focusDone))
            }
        })
        s.focus?.let { f ->
            put("focus", JSONObject().put("startMs", f.startMs).put("lengthMs", f.lengthMs).put("pausedMs", f.pausedMs).apply { f.pausedAt?.let { put("pausedAt", it) } })
        }
    }.toString()

    fun decode(text: String): Saved {
        val o = JSONObject(text)
        val days = o.optJSONArray("days")?.let { a ->
            (0 until a.length()).map { i ->
                val d = a.getJSONObject(i)
                Day(d.getString("date"), d.optInt("breathSeconds"), d.optInt("breaths"), d.optInt("syncScored"), d.optInt("syncMatched"), d.optInt("focusDone"))
            }
        } ?: emptyList()
        val focus = o.optJSONObject("focus")?.let { f ->
            FocusActive(f.getLong("startMs"), f.getLong("lengthMs"), f.optLong("pausedMs"), if (f.has("pausedAt")) f.getLong("pausedAt") else null)
        }
        return Saved(days, focus)
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so both windows recompose on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    @Synchronized
    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putInt("volume", s.volume)
            .putBoolean("muted", s.muted)
            .putString("style", s.style.name)
            .putBoolean("ambient", s.ambient)
            .putString("lastPattern", s.lastPattern.name)
            .putInt("focusMin", s.focusMin)
            .putBoolean("welcomed", s.welcomed)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            volume = prefs.getInt("volume", 1).coerceIn(0, 2),
            muted = prefs.getBoolean("muted", false),
            style = Style.valueOf(prefs.getString("style", Style.GLAZE.name)!!),
            ambient = prefs.getBoolean("ambient", false),
            lastPattern = PatternId.parse(prefs.getString("lastPattern", null))?.takeIf { it in PatternId.SELECTABLE } ?: PatternId.CALM,
            focusMin = prefs.getInt("focusMin", 25).takeIf { it in Settings.FOCUS_LENGTHS } ?: 25,
            welcomed = prefs.getBoolean("welcomed", false),
        )
    }.getOrElse { Settings() }
}
