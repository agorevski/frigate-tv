package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frigatetv.app.AppState
import com.frigatetv.app.AppViewModel
import com.frigatetv.app.Connection
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape

private enum class Tab(val label: String) {
    LIVE("Live"),
    EVENTS("Events"),
    RECORDINGS("Recordings"),
    SETTINGS("Settings"),
}

private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun FrigateTvApp(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.LIVE) }
    val tabRequesters = remember { Tab.entries.associateWith { FocusRequester() } }

    if (tab != Tab.LIVE) {
        BackHandler { tab = Tab.LIVE }
    }

    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(6_000)
            vm.clearNotice()
        }
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Header(state)
        TabBar(tab, tabRequesters) { tab = it }
        state.notice?.let {
            Text(
                it,
                color = TextPrimary,
                fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().background(PanelHi).padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (!state.foreground) {
                // Backgrounded: render nothing so every stream and decoder is released.
                Box(Modifier.fillMaxSize().background(Color.Black))
            } else {
                when (tab) {
                    Tab.LIVE -> LiveScreen(
                        state = state,
                        onSelectCamera = vm::selectCamera,
                        onToggleMute = vm::toggleMute,
                        onReconnect = vm::reconnect,
                        onOpenSettings = { tab = Tab.SETTINGS },
                        tabFocus = tabRequesters.getValue(Tab.LIVE),
                    )
                    Tab.EVENTS -> EventsScreen(state)
                    Tab.RECORDINGS -> RecordingsScreen(state)
                    Tab.SETTINGS -> SettingsScreen(state, vm)
                }
            }
        }
    }
}

@Composable
private fun Header(state: AppState) {
    val clock by produceState(initialValue = LocalTime.now().format(CLOCK_FORMAT)) {
        while (true) {
            value = LocalTime.now().format(CLOCK_FORMAT)
            delay(15_000)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Panel)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("FrigateTV", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        Text(state.active?.name ?: "No server", color = TextMuted, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))
        val (dot, label) = when (val c = state.connection) {
            Connection.Idle, Connection.Connecting -> Warn to "Connecting"
            is Connection.Connected -> LiveGreen to "Connected"
            is Connection.Failed -> Danger to "Offline"
        }
        Box(Modifier.size(12.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, color = TextMuted, fontSize = 18.sp)
        Spacer(Modifier.width(24.dp))
        Text(clock, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TabBar(selected: Tab, requesters: Map<Tab, FocusRequester>, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tab.entries.forEach { tab ->
            FocusButton(tab.label, { onSelect(tab) }, selected = tab == selected, focusRequester = requesters.getValue(tab))
        }
    }
}
