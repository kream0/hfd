package app.hfd.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.hfd.R
import app.hfd.ui.components.EmptyState
import app.hfd.ui.components.ScreenHeader

@Composable
fun HomeScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(stringResource(R.string.app_name).uppercase())
        EmptyState(stringResource(R.string.home_soon_title), stringResource(R.string.home_soon_body))
    }
}
