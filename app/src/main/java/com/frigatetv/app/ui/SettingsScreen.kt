package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frigatetv.app.AppState
import com.frigatetv.app.AppViewModel
import com.frigatetv.app.Connection
import com.frigatetv.app.data.CameraInfo
import com.frigatetv.app.data.ProfilePrefs
import com.frigatetv.app.data.Scheme
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.displayName
import com.frigatetv.app.data.orderedCameras
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize

/** Editable copy of a profile; text fields are validated before becoming a [ServerProfile]. */
private data class ProfileDraft(
    val id: String?,
    val name: String,
    val host: String,
    val port: String,
    val rtspPort: String,
    val scheme: Scheme,
) {
    companion object {
        fun from(p: ServerProfile) = ProfileDraft(
            id = p.id,
            name = p.name,
            host = p.host,
            port = p.port.toString(),
            rtspPort = p.rtspPort.toString(),
            scheme = p.scheme,
        )

        fun blank() = ProfileDraft(
            id = null,
            name = "",
            host = "",
            port = "9150",
            rtspPort = "8554",
            scheme = Scheme.HTTP,
        )
    }
}

private val HOST_PATTERN = Regex("""^[A-Za-z0-9]([A-Za-z0-9.\-]*[A-Za-z0-9])?$""")

/** Returns the validated profile, or an error message describing the first problem. */
private fun ProfileDraft.validate(): Pair<ServerProfile?, String?> {
    val trimmedName = name.trim()
    val trimmedHost = host.trim()
    val port = port.trim().toIntOrNull()
    val rtsp = rtspPort.trim().toIntOrNull()
    return when {
        trimmedName.isEmpty() || trimmedName.length > 40 -> null to "Name must be 1–40 characters."
        !HOST_PATTERN.matches(trimmedHost) -> null to "Enter a host name or IPv4 address (no scheme or port)."
        port == null || port !in 1..65535 -> null to "Port must be between 1 and 65535."
        rtsp == null || rtsp !in 1..65535 -> null to "RTSP port must be between 1 and 65535."
        else -> ServerProfile(
            id = id ?: java.util.UUID.randomUUID().toString(),
            name = trimmedName,
            host = trimmedHost,
            port = port,
            scheme = scheme,
            rtspPort = rtsp,
        ) to null
    }
}

@Composable
fun SettingsScreen(state: AppState, vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<ProfileDraft?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    // BACK closes an open profile form instead of leaving the screen.
    BackHandler(enabled = draft != null) {
        draft = null
        formError = null
        testResult = null
    }
    val addFocus = remember { FocusRequester() }
    val nameFocus = remember { FocusRequester() }
    // Opening the form moves focus into it; closing returns focus to the "Add" button.
    // Without this, focus is lost when the focused control leaves the tree.
    val formOpen = draft != null
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(formOpen) {
        if (formOpen) runCatching { nameFocus.requestFocus() }
        else if (wasOpen) runCatching { addFocus.requestFocus() }
        wasOpen = formOpen
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        SectionTitle("Server profiles")
        state.profiles.forEach { p ->
            ProfileRow(
                profile = p,
                isActive = p.id == state.active?.id,
                canDelete = state.profiles.size > 1 && p.id != state.active?.id,
                onActivate = {
                    scope.launch {
                        vm.saveProfile(p, makeActive = true).onFailure { formError = it.message }
                    }
                },
                onEdit = {
                    draft = ProfileDraft.from(p)
                    formError = null
                    testResult = null
                },
                onDelete = { vm.deleteProfile(p.id) },
            )
        }
        Spacer(Modifier.height(12.dp))
        if (draft == null) {
            FocusButton("Add server profile", {
                draft = ProfileDraft.blank()
                formError = null
                testResult = null
            }, focusRequester = addFocus)
        } else {
            val current = draft ?: return@Column
            ProfileForm(
                draft = current,
                nameFocus = nameFocus,
                onChange = { draft = it },
                error = formError,
                testResult = testResult,
                testing = testing,
                onTest = {
                    val (profile, err) = current.validate()
                    formError = err
                    if (profile != null) {
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = vm.testProfile(profile)
                            testing = false
                            testResult = result.fold(
                                onSuccess = { "Connected: $it" },
                                onFailure = { "Failed: ${it.message}" },
                            )
                        }
                    }
                },
                onSave = { makeActive ->
                    val (profile, err) = current.validate()
                    formError = err
                    if (profile != null) {
                        // The form stays open until the save succeeds, so a failed connection
                        // check is shown next to the fields.
                        scope.launch {
                            vm.saveProfile(profile, makeActive).fold(
                                onSuccess = {
                                    draft = null
                                    testResult = null
                                },
                                onFailure = { formError = it.message },
                            )
                        }
                    }
                },
                onCancel = {
                    draft = null
                    formError = null
                    testResult = null
                },
            )
        }

        SectionTitle("Cameras")
        CameraList(state.cameras, state.prefs, state.detecting, vm)

        SectionTitle("Live layout")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(0 to "Automatic", 2 to "2 columns", 3 to "3 columns").forEach { (cols, label) ->
                FocusButton(label, { vm.setColumns(cols) }, selected = state.prefs.columns == cols)
            }
        }

        SectionTitle("Connection")
        ConnectionSummary(state, vm)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(text, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ProfileRow(
    profile: ServerProfile,
    isActive: Boolean,
    canDelete: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(Panel, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(profile.name, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            if (isActive) StatusPill("ACTIVE", LiveGreen)
        }
        Text("${profile.scheme.wire}://${profile.host}:${profile.port}", color = TextMuted, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isActive) FocusButton("Activate", onActivate, selected = true)
            FocusButton("Edit", onEdit)
            if (canDelete) FocusButton("Delete", onDelete)
        }
    }
}

@Composable
private fun ProfileForm(
    draft: ProfileDraft,
    nameFocus: FocusRequester,
    onChange: (ProfileDraft) -> Unit,
    error: String?,
    testResult: String?,
    testing: Boolean,
    onTest: () -> Unit,
    onSave: (makeActive: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(PanelHi, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        // IME "Next" moves focus explicitly through Name -> Host -> Port -> RTSP port -> Scheme.
        // Directional search would skip fields (Host's Down lands on the RTSP port).
        val hostFocus = remember { FocusRequester() }
        val portFocus = remember { FocusRequester() }
        val rtspFocus = remember { FocusRequester() }
        val schemeFocus = remember { FocusRequester() }
        val nextOptions = KeyboardOptions(imeAction = ImeAction.Next)
        // A focused text field consumes D-pad UP/DOWN for cursor movement, which traps focus.
        // Handling them in a preview handler (runs before the field) lets focus move between rows.
        // Compose also shows the IME whenever a field gains focus, and an open IME captures D-pad
        // keys, so the IME is hidden on focus change; OK on a field still opens it.
        val verticalNav = Modifier
            .onFocusChanged { if (it.hasFocus) keyboard?.hide() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> focusManager.moveFocus(FocusDirection.Up)
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> focusManager.moveFocus(FocusDirection.Down)
                    else -> false
                }
            }
        Text(if (draft.id == null) "New server profile" else "Edit server profile", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = nextOptions,
            keyboardActions = KeyboardActions(onNext = { runCatching { hostFocus.requestFocus() } }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(nameFocus)
                .focusProperties { down = hostFocus }
                .then(verticalNav),
        )
        OutlinedTextField(
            value = draft.host,
            onValueChange = { onChange(draft.copy(host = it)) },
            label = { Text("Host or IP address (e.g. 192.168.1.20)") },
            singleLine = true,
            keyboardOptions = nextOptions,
            keyboardActions = KeyboardActions(onNext = { runCatching { portFocus.requestFocus() } }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(hostFocus)
                .focusProperties { up = nameFocus; down = portFocus }
                .then(verticalNav),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = draft.port,
                onValueChange = { onChange(draft.copy(port = it)) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = nextOptions,
                keyboardActions = KeyboardActions(onNext = { runCatching { rtspFocus.requestFocus() } }),
                modifier = Modifier
                    .width(160.dp)
                    .focusRequester(portFocus)
                    .focusProperties { up = hostFocus; down = schemeFocus }
                    .then(verticalNav),
            )
            OutlinedTextField(
                value = draft.rtspPort,
                onValueChange = { onChange(draft.copy(rtspPort = it)) },
                label = { Text("RTSP port") },
                singleLine = true,
                keyboardOptions = nextOptions,
                keyboardActions = KeyboardActions(onNext = { runCatching { schemeFocus.requestFocus() } }),
                modifier = Modifier
                    .width(160.dp)
                    .focusRequester(rtspFocus)
                    .focusProperties { up = hostFocus; down = schemeFocus }
                    .then(verticalNav),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Scheme", color = TextMuted, fontSize = 16.sp)
            Scheme.entries.forEachIndexed { i, s ->
                FocusButton(
                    s.wire.uppercase(),
                    { onChange(draft.copy(scheme = s)) },
                    selected = draft.scheme == s,
                    focusRequester = if (i == 0) schemeFocus else null,
                )
            }
        }
        error?.let { Text(it, color = Danger, fontSize = 16.sp) }
        testResult?.let {
            Text(it, color = if (it.startsWith("Connected")) LiveGreen else Danger, fontSize = 16.sp)
        }
        if (testing) Text("Testing connection…", color = TextMuted, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusButton("Test connection", onTest, enabled = !testing)
            FocusButton("Save & activate", { onSave(true) }, selected = true)
            FocusButton("Save", { onSave(false) })
            FocusButton("Cancel", onCancel)
        }
    }
}

@Composable
private fun CameraList(
    cameras: List<CameraInfo>,
    prefs: ProfilePrefs,
    detecting: Set<String>,
    vm: AppViewModel,
) {
    val ordered = orderedCameras(cameras, prefs.cameraOrder)
    if (ordered.isEmpty()) {
        Text("No cameras discovered yet.", color = TextMuted, fontSize = 18.sp)
        return
    }
    // Camera id and move direction of the row whose focus must follow it after a move.
    var focusAfterMove by remember { mutableStateOf<Pair<String, Int>?>(null) }
    ordered.forEachIndexed { index, cam ->
        key(cam.id) {
            val favoriteRequester = remember { FocusRequester() }
            val upRequester = remember { FocusRequester() }
            val downRequester = remember { FocusRequester() }
            val canUp = index > 0
            val canDown = index < ordered.lastIndex
            // Moving a row changes its index; focus its move button, or the other one if this edge is disabled.
            LaunchedEffect(focusAfterMove, index, canUp, canDown) {
                val pending = focusAfterMove
                if (pending == null || pending.first != cam.id) return@LaunchedEffect
                val target = when {
                    pending.second < 0 && canUp -> upRequester
                    pending.second > 0 && canDown -> downRequester
                    canUp -> upRequester
                    canDown -> downRequester
                    else -> favoriteRequester
                }
                runCatching { target.requestFocus() }
                focusAfterMove = null
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = displayName(cam.id) + if (!cam.enabled) " (disabled)" else "",
                    color = if (cam.enabled) TextPrimary else TextMuted,
                    fontSize = 20.sp,
                    modifier = Modifier.width(320.dp),
                )
                FocusButton(
                    if (cam.id in prefs.favorites) "★ Favorite" else "☆ Favorite",
                    { vm.toggleFavorite(cam.id) },
                    selected = cam.id in prefs.favorites,
                    focusRequester = favoriteRequester,
                )
                FocusButton(
                    "Move up",
                    {
                        focusAfterMove = cam.id to -1
                        vm.moveCamera(cam.id, -1)
                    },
                    enabled = canUp,
                    focusRequester = upRequester,
                )
                FocusButton(
                    "Move down",
                    {
                        focusAfterMove = cam.id to 1
                        vm.moveCamera(cam.id, 1)
                    },
                    enabled = canDown,
                    focusRequester = downRequester,
                )
            }
        }
    }
}

@Composable
private fun ConnectionSummary(state: AppState, vm: AppViewModel) {
    val active = state.active
    val text = when (val c = state.connection) {
        Connection.Idle -> "Not connected"
        Connection.Connecting -> "Connecting…"
        is Connection.Connected -> "Connected to ${active?.name ?: "server"} · Frigate ${c.version} · ${state.cameras.size} cameras"
        is Connection.Failed -> "Not connected: ${c.reason}"
    }
    Text(text, color = TextPrimary, fontSize = 18.sp)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FocusButton("Reconnect", { vm.reconnect() })
    }
}
