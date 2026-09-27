package app.hfd

import android.app.Application
import app.hfd.reminders.Reminders
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HfdApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Reminders.createChannel(this)
        // Plan reminders now and whenever what they depend on changes.
        Graph.scope.launch {
            Graph.settings.state
                .map { listOf(it.reminders, it.latitude, it.longitude, it.prayerMethod) }
                .distinctUntilChanged()
                .collect { runCatching { Reminders.reschedule(this@HfdApp) } }
        }
    }
}
