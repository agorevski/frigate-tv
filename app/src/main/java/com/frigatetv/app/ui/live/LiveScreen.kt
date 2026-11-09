package com.frigatetv.app.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.lifecycle.viewmodel.compose.viewModel
import com.frigatetv.app.AppState
import com.frigatetv.app.Connection
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.ui.Danger
import com.frigatetv.app.ui.FocusButton
import com.frigatetv.app.ui.LoadingState
import com.frigatetv.app.ui.PulsingDot
import com.frigatetv.app.ui.Space
import com.frigatetv.app.ui.TextPrimary
import com.frigatetv.app.ui.TextSecondary
import com.frigatetv.app.ui.Warn

/** Live tab: the camera grid once connected, otherwise a connecting or failed panel. */
@Composable
fun LiveScreen(
    state: AppState,
    onSelectCamera: (String) -> Unit,
    onToggleMute: () -> Unit,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    tabFocus: FocusRequester,
) {
    val viewModel: LiveViewModel = viewModel()
    val profile = state.active ?: return
    when (val conn = state.connection) {
        Connection.Idle, Connection.Connecting -> LoadingState("Connecting to ${profile.name}…")
        is Connection.Failed -> ConnectionFailedPanel(profile, conn.reason, onReconnect, onOpenSettings)
        is Connection.Connected -> LiveGrid(
            profile = profile,
            state = state,
            onSelectCamera = onSelectCamera,
            onToggleMute = onToggleMute,
            tabFocus = tabFocus,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun ConnectionFailedPanel(
    profile: ServerProfile,
    reason: String,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(Space.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Can't reach ${profile.name}",
            color = TextPrimary,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(Space.md))
        Text(profile.baseUrl, color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Space.xs))
        Text(reason, color = Danger, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Space.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(color = Warn, active = true)
            Spacer(Modifier.width(Space.xs))
            Text("Retrying automatically.", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            FocusButton("Retry now", onReconnect, selected = true)
            FocusButton("Server profiles", onOpenSettings)
        }
    }
}
