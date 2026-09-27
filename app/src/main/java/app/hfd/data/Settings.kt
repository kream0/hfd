package app.hfd.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val autoUpdate: Boolean = true,
)

/** App settings in SharedPreferences (`hfd_settings.xml`, included in Auto Backup). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            autoUpdate = prefs.getBoolean("autoUpdate", d.autoUpdate),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("autoUpdate", s.autoUpdate)
            .apply()
    }

    companion object {
        const val PREFS = "hfd_settings"
    }
}
