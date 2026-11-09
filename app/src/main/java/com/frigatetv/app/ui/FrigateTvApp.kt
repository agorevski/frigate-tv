package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frigatetv.app.AppViewModel
import com.frigatetv.app.Connection
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private enum class Tab(val label: String) {
    LIVE("Live"),
    EVENTS("Events"),
    RECORDINGS("Recordings"),
    SETTINGS("Settings"),
}

private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

// The bar fades from Panel into the page so it reads as a lit edge rather than a hard box.
private val TopBarBrush = Brush.verticalGradient(listOf(Panel, Ink))
private val PillShape = RoundedCornerShape(percent = 50)
private val SelectedPill = Accent.copy(alpha = 0.16f)

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

    // Phones draw edge-to-edge (targetSdk 35); keep content clear of the status and navigation bars. Zero on TV.
    Column(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(
            serverName = state.active?.name ?: "No server",
            connection = state.connection,
            selected = tab,
            requesters = tabRequesters,
            onSelect = { tab = it },
        )
        NoticeBar(state.notice, Modifier.fillMaxWidth())
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

/** One slim bar: identity on the left, tabs next to it, status and clock on the right. */
@Composable
private fun TopBar(
    serverName: String,
    connection: Connection,
    selected: Tab,
    requesters: Map<Tab, FocusRequester>,
    onSelect: (Tab) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(TopBarBrush)) {
        // Narrow (phone portrait) screens drop the server name and status label so the tabs fit.
        val compact = maxWidth < 600.dp
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) Space.sm else Space.lg, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark()
            if (!compact) {
                Spacer(Modifier.width(Space.sm))
                // The server name is the only emphasized identity text; status and clock stay quiet.
                Text(
                    serverName,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
            Spacer(Modifier.width(if (compact) Space.xs else Space.lg))
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { tab ->
                    TabPill(tab.label, tab == selected, requesters.getValue(tab)) { onSelect(tab) }
                }
            }
            Spacer(Modifier.width(if (compact) Space.xs else Space.md))
            ConnectionStatus(connection, showLabel = !compact)
            Spacer(Modifier.width(if (compact) Space.sm else Space.lg))
            Clock()
        }
    }
}

/** Small accent ring echoing the launcher icon's lens. Drawn once; never animates. */
@Composable
private fun BrandMark() {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 3.dp.toPx()
        drawCircle(Accent, radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
        drawCircle(Accent, radius = size.minDimension / 8f)
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when {
            selected -> SelectedPill
            focused -> PanelHi
            else -> Color.Transparent
        },
        tween(Motion.FocusMs),
        label = "tabBg",
    )
    FocusSurface(
        onClick = onClick,
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .semantics { this.selected = selected },
        background = bg,
        shape = PillShape,
        contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs),
        focusRequester = requester,
        onFocus = { focused = it },
    ) {
        Text(
            label,
            color = when {
                selected -> Accent
                focused -> TextPrimary
                else -> TextSecondary
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ConnectionStatus(connection: Connection, showLabel: Boolean = true) {
    val (dot, label, connecting) = when (connection) {
        Connection.Idle, Connection.Connecting -> Triple(Warn, "Connecting", true)
        is Connection.Connected -> Triple(LiveGreen, "Connected", false)
        is Connection.Failed -> Triple(Danger, "Offline", false)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulsingDot(dot, active = connecting)
        if (showLabel) {
            Spacer(Modifier.width(Space.xs))
            Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Owns the clock state so a tick recomposes only this text, not the app shell. */
@Composable
private fun Clock() {
    val clock by produceState(initialValue = LocalTime.now().format(CLOCK_FORMAT)) {
        while (true) {
            val now = LocalTime.now()
            value = now.format(CLOCK_FORMAT)
            // Wake right after the minute rolls over instead of polling.
            delay((60 - now.second) * 1_000L - now.nano / 1_000_000 + 50)
        }
    }
    Text(clock, color = TextPrimary, style = MaterialTheme.typography.titleMedium.merge(NumericStyle))
}
