package com.simorgh.mac.data

import com.simorgh.mac.Paths
import com.simorgh.mac.model.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * The user's preferences as one JSON document in the data dir — the desktop
 * counterpart of the Android SettingsStore (same file name, same shape).
 */
class SettingsStore private constructor() {
    private val file = File(Paths.dataDir, FILE_NAME)
    private val _settings = MutableStateFlow(load(file))
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        write(file, next)
    }

    companion object {
        const val FILE_NAME = "settings.json"

        @Volatile private var instance: SettingsStore? = null
        fun get(): SettingsStore = instance ?: synchronized(this) { instance ?: SettingsStore().also { instance = it } }

        private fun load(file: File): Settings = runCatching {
            if (file.exists()) Settings.fromJson(JSONObject(file.readText())) else Settings()
        }.getOrDefault(Settings())

        private fun write(file: File, settings: Settings) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(settings.toJson().toString())
            if (!tmp.renameTo(file)) {
                file.writeText(settings.toJson().toString())
                tmp.delete()
            }
        }
    }
}

/** First-run flag: onboarding shows until the user finishes or skips it. */
object FirstRun {
    private val file = File(Paths.dataDir, "onboarded")
    var onboarded: Boolean
        get() = file.exists()
        set(value) {
            if (value) file.createNewFile() else file.delete()
        }
}
