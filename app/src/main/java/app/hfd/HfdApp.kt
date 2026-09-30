package app.hfd

import android.app.Application
import app.hfd.data.AppLocale
import app.hfd.reminders.Reminders
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HfdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        // The app's language: French unless chosen otherwise (here, or in Android's settings).
        AppLocale.adopt(this, Graph.settings.current.language)?.let { chosen -> Graph.settings.update { it.copy(language = chosen) } }
        AppLocale.apply(this, Graph.settings.current.language)
        Reminders.createChannel(this)
        // Plan reminders now and whenever what they depend on changes.
        Graph.scope.launch {
            Graph.settings.state
                .map { it.reminders }
                .distinctUntilChanged()
                .collect { runCatching { Reminders.reschedule(this@HfdApp) } }
        }
    }
}
