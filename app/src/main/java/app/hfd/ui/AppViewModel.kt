package app.hfd.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import app.hfd.R

enum class Tab(@StringRes val label: Int) {
    HOME(R.string.tab_home),
    FADAIL(R.string.tab_fadail),
    SETTINGS(R.string.tab_settings),
}

/** App-level navigation state (survives configuration changes). */
class AppViewModel : ViewModel() {
    var tab by mutableStateOf(Tab.HOME)
    /** Faḍīla open in the detail view, over the current tab. */
    var fadila by mutableStateOf<String?>(null)

    fun selectTab(t: Tab) {
        fadila = null
        tab = t
    }

    fun openFadila(id: String) {
        fadila = id
    }
}
