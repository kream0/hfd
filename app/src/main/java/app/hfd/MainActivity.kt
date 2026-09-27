package app.hfd

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.hfd.ui.AppRoot
import app.hfd.ui.AppViewModel
import app.hfd.ui.components.sessionFor
import app.hfd.ui.theme.HfdTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            HfdTheme {
                AppRoot(appViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Graph.player.connect()
        Graph.updater.checkIfDue()
    }

    override fun onStop() {
        super.onStop()
        Graph.player.disconnect()
        Graph.progress.flush()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_INSTALL_STATUS -> Graph.updater.onInstallStatus(this, intent)
            ACTION_OPEN_PLAYER -> appViewModel.openPlaying()
            ACTION_REVIEW -> appViewModel.review()
            ACTION_OPEN_FADILA, ACTION_LISTEN_FADILA -> {
                val id = intent.getStringExtra(EXTRA_FADILA) ?: return
                val listen = intent.action == ACTION_LISTEN_FADILA
                Graph.scope.launch {
                    val content = Graph.content.content.filterNotNull().first()
                    val f = content.fadila(id) ?: return@launch
                    appViewModel.flow = null
                    appViewModel.openFadila(id)
                    if (listen) {
                        val lang = if (resources.configuration.locales[0].language == "fr") "fr" else "en"
                        Graph.player.play(sessionFor(f, f.title.pick(lang), Graph.ranges.get(f.id, f.size)))
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "app.hfd.INSTALL_STATUS"
        const val ACTION_OPEN_PLAYER = "app.hfd.OPEN_PLAYER"
        const val ACTION_OPEN_FADILA = "app.hfd.OPEN_FADILA"
        const val ACTION_LISTEN_FADILA = "app.hfd.LISTEN_FADILA"
        const val ACTION_REVIEW = "app.hfd.REVIEW"
        const val EXTRA_FADILA = "fadila"
    }
}
