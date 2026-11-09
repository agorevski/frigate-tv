package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frigatetv.app.AppState
import com.frigatetv.app.AppViewModel
import com.frigatetv.app.Connection
import com.frigatetv.app.data.CameraInfo
import com.frigatetv.app.data.Scheme
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.displayName
import com.frigatetv.app.data.orderedCameras
import kotlinx.coroutines.launch

/**
 * Editable copy of a profile. Each field is its own state, so a keystroke recomposes only the
 * form, never the profile list or the rest of the screen.
 */
@Stable
private class ProfileDraft(
    val id: String?,
    name: String,
    host: String,
    port: String,
    rtspPort: String,
    scheme: Scheme,
) {
    var name by mutableStateOf(name)
    var host by mutableStateOf(host)
    var port by mutableStateOf(port)
    var rtspPort by mutableStateOf(rtspPort)
    var scheme by mutableStateOf(scheme)
    var authEnabled by mutableStateOf(false)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var trustSelfSigned by mutableStateOf(false)

    /** Cleared when trust is switched on again, so the next connection re-pins the certificate. */
    var pinnedCertSha256: String? by mutableStateOf(null)

    companion object {
        fun from(p: ServerProfile) = ProfileDraft(
            id = p.id,
            name = p.name,
            host = p.host,
            port = p.port.toString(),
            rtspPort = p.rtspPort.toString(),
            scheme = p.scheme,
        ).apply {
            authEnabled = p.authEnabled
            username = p.username.orEmpty()
            password = p.password.orEmpty()
            trustSelfSigned = p.trustSelfSigned
            pinnedCertSha256 = p.pinnedCertSha256
        }

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

/** Width of the side-by-side port fields; both share it so their edges line up. */
private val FieldWidth = 200.dp

/** Content stops growing past this width so lines stay readable on large screens. */
private val MaxContentWidth = 1040.dp

/** Below this width (phones), rows stack their actions under the label. */
private const val COMPACT_WIDTH_DP = 600

/** Every list row shares one minimum height, so the cards keep an even rhythm. */
private val RowMinHeight = 64.dp

/** Width of the label column in option rows, so their pills line up across cards. */
private val OptionLabelWidth = 140.dp

private const val PIN_PENDING_HINT =
    "The certificate is pinned when you test or save this profile. Compare its SHA-256 with the server's."

/** The first problem with the login fields, or null when login is off or the fields are filled in. */
private fun ProfileDraft.credentialError(): String? = when {
    !authEnabled -> null
    username.trim().isEmpty() -> "Enter the Frigate username."
    password.isEmpty() -> "Enter the Frigate password."
    else -> null
}

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
        credentialError() != null -> null to credentialError()
        else -> ServerProfile(
            id = id ?: java.util.UUID.randomUUID().toString(),
            name = trimmedName,
            host = trimmedHost,
            port = port,
            scheme = scheme,
            rtspPort = rtsp,
            authEnabled = authEnabled,
            username = username.trim().takeIf { authEnabled },
            password = password.takeIf { authEnabled },
            trustSelfSigned = trustSelfSigned && scheme == Scheme.HTTPS,
            pinnedCertSha256 = pinnedCertSha256,
        ) to null
    }
}

@Composable
fun SettingsScreen(state: AppState, vm: AppViewModel) {
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP
    // Each section receives only the fields it shows. AppState is re-emitted on every detection
    // poll, and narrow inputs let unchanged sections skip recomposition.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = Space.lg, top = Space.md, end = Space.lg, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            ProfilesSection(
                profiles = state.profiles,
                activeId = state.active?.id,
                compact = compact,
                vm = vm,
            )
            CamerasSection(
                cameras = state.cameras,
                cameraOrder = state.prefs.cameraOrder,
                favorites = state.prefs.favorites,
                connecting = state.connection == Connection.Connecting,
                compact = compact,
                vm = vm,
            )
            SettingsGroup(
                title = "Live grid columns",
                subtitle = "How many camera tiles fit across the Live grid.",
            ) {
                ColumnsOption(columns = state.prefs.columns, vm = vm)
            }
            SettingsGroup("Current connection") {
                ConnectionSummary(
                    connection = state.connection,
                    activeName = state.active?.name,
                    cameraCount = state.cameras.size,
                    compact = compact,
                    vm = vm,
                )
            }
        }
    }
}

/** A specific headline above one grouped [SurfaceCard]. */
@Composable
private fun SettingsGroup(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        SectionHeader(title, subtitle = subtitle)
        SurfaceCard(Modifier.fillMaxWidth(), content = content)
    }
}

/** Hairline between rows of a card. */
@Composable
private fun RowDivider() = HorizontalDivider(color = PanelHi)

/** A labelled row of segmented pills; exactly one pill is selected. Narrow screens put the label above the pills. */
@Composable
private fun OptionRow(label: String, content: @Composable RowScope.() -> Unit) {
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_WIDTH_DP
    val labelText: @Composable (Modifier) -> Unit = { m ->
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyLarge, modifier = m)
    }
    // Pills scroll sideways rather than wrapping their labels when space runs out.
    val pills: @Composable (Modifier) -> Unit = { m ->
        Row(
            m.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            content = content,
        )
    }
    if (compact) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            labelText(Modifier)
            pills(Modifier.fillMaxWidth())
        }
    } else {
        Row(
            Modifier.fillMaxWidth().heightIn(min = RowMinHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            labelText(Modifier.width(OptionLabelWidth))
            pills(Modifier.weight(1f))
        }
    }
}

// ---------------------------------------------------------------- Server profiles

@Composable
private fun ProfilesSection(
    profiles: List<ServerProfile>,
    activeId: String?,
    compact: Boolean,
    vm: AppViewModel,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<ProfileDraft?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    // Activation runs a connection check first; the row shows progress and any failure inline.
    var activatingId by remember { mutableStateOf<String?>(null) }
    var rowError by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun closeForm() {
        draft = null
        formError = null
        testResult = null
    }

    fun openForm(d: ProfileDraft) {
        draft = d
        formError = null
        testResult = null
    }

    // BACK closes an open profile form instead of leaving the screen.
    BackHandler(enabled = draft != null) { closeForm() }
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

    SettingsGroup(
        title = "Server profiles",
        subtitle = "Switching the active profile reloads every camera.",
    ) {
        if (profiles.isEmpty()) {
            Text(
                "No server profiles yet. Add one to connect to Frigate.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        profiles.forEachIndexed { index, p ->
            key(p.id) {
                if (index > 0) RowDivider()
                ProfileRow(
                    profile = p,
                    isActive = p.id == activeId,
                    canDelete = profiles.size > 1 && p.id != activeId,
                    activating = activatingId == p.id,
                    error = rowError?.takeIf { it.first == p.id }?.second,
                    compact = compact,
                    onActivate = {
                        if (activatingId == null) {
                            activatingId = p.id
                            rowError = null
                            scope.launch {
                                vm.saveProfile(p, makeActive = true).onFailure {
                                    rowError = p.id to (it.message ?: "Could not connect to ${p.name}.")
                                }
                                activatingId = null
                            }
                        }
                    },
                    onEdit = { openForm(ProfileDraft.from(p)) },
                    onDelete = {
                        if (rowError?.first == p.id) rowError = null
                        vm.deleteProfile(p.id)
                        // The focused row is about to leave the tree; park focus on a control that stays.
                        runCatching { addFocus.requestFocus() }
                    },
                )
            }
        }
        val current = draft
        if (current == null) {
            if (profiles.isNotEmpty()) Spacer(Modifier.height(Space.xxs))
            FocusButton("Add server profile", { openForm(ProfileDraft.blank()) }, focusRequester = addFocus)
        } else {
            RowDivider()
            ProfileForm(
                draft = current,
                nameFocus = nameFocus,
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
                                onSuccess = { test ->
                                    "Connected: ${test.summary}" +
                                        (test.certSha256?.let { "\nCertificate SHA-256: $it" }.orEmpty())
                                },
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
                onCancel = { closeForm() },
            )
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ServerProfile,
    isActive: Boolean,
    canDelete: Boolean,
    activating: Boolean,
    error: String?,
    compact: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val editFocus = remember { FocusRequester() }
    // Activating removes the focused "Activate" button; hand focus to "Edit" on the same row.
    var focusEditWhenActive by remember { mutableStateOf(false) }
    LaunchedEffect(isActive) {
        if (isActive && focusEditWhenActive) runCatching { editFocus.requestFocus() }
        focusEditWhenActive = false
    }
    // The row is its own semantics group so UI tests (and TalkBack) can scope its buttons to it.
    val rowModifier = Modifier
        .fillMaxWidth()
        .semantics { isTraversalGroup = true }
        .heightIn(min = RowMinHeight)
    val info: @Composable (Modifier) -> Unit = { m ->
        ProfileInfo(profile, isActive, activating, error, m)
    }
    val actions: @Composable () -> Unit = {
        // The ACTIVE pill is the only active-state indicator; the active row has no Activate button.
        if (!isActive) {
            FocusButton(
                "Activate",
                {
                    focusEditWhenActive = true
                    onActivate()
                },
                enabled = !activating,
            )
        }
        FocusButton("Edit", onEdit, focusRequester = editFocus)
        if (canDelete) {
            // Extra gap keeps the destructive action apart from the safe ones.
            Spacer(Modifier.width(Space.sm))
            FocusButton("Delete", onDelete, destructive = true)
        }
    }
    if (compact) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            info(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) { actions() }
        }
    } else {
        Row(
            rowModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            info(Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
private fun ProfileInfo(
    profile: ServerProfile,
    isActive: Boolean,
    activating: Boolean,
    error: String?,
    modifier: Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (isActive) LiveGreen else PanelHi, CircleShape),
        )
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(
                    profile.name,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isActive) StatusPill("ACTIVE", LiveGreen)
            }
            if (activating) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    PulsingDot(Warn, active = true)
                    Text("Connecting…", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    "${profile.scheme.wire}://${profile.host}:${profile.port}" +
                        if (profile.authEnabled) " · login" else "",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            error?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ProfileForm(
    draft: ProfileDraft,
    nameFocus: FocusRequester,
    error: String?,
    testResult: String?,
    testing: Boolean,
    onTest: () -> Unit,
    onSave: (makeActive: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        // IME "Next" moves focus explicitly through Name -> Host -> Port -> RTSP port -> Scheme.
        // Directional search would skip fields (Host's Down lands on the RTSP port).
        val hostFocus = remember { FocusRequester() }
        val portFocus = remember { FocusRequester() }
        val rtspFocus = remember { FocusRequester() }
        val schemeFocus = remember { FocusRequester() }
        val usernameFocus = remember { FocusRequester() }
        val passwordFocus = remember { FocusRequester() }
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
        val colors = fieldColors()
        val shape = RoundedCornerShape(Radius.md)
        val numeric = MaterialTheme.typography.bodyLarge.merge(NumericStyle)
        Text(
            if (draft.id == null) "New server profile" else "Edit server profile",
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft.name = it },
            label = { Text("Name") },
            singleLine = true,
            colors = colors,
            shape = shape,
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
            onValueChange = { draft.host = it },
            label = { Text("Host or IP address (e.g. 192.168.1.20)") },
            singleLine = true,
            colors = colors,
            shape = shape,
            keyboardOptions = nextOptions,
            keyboardActions = KeyboardActions(onNext = { runCatching { portFocus.requestFocus() } }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(hostFocus)
                .focusProperties { up = nameFocus; down = portFocus }
                .then(verticalNav),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            OutlinedTextField(
                value = draft.port,
                onValueChange = { draft.port = it },
                label = { Text("Port") },
                singleLine = true,
                colors = colors,
                shape = shape,
                textStyle = numeric,
                keyboardOptions = nextOptions,
                keyboardActions = KeyboardActions(onNext = { runCatching { rtspFocus.requestFocus() } }),
                modifier = Modifier
                    .width(FieldWidth)
                    .focusRequester(portFocus)
                    .focusProperties { up = hostFocus; down = schemeFocus }
                    .then(verticalNav),
            )
            OutlinedTextField(
                value = draft.rtspPort,
                onValueChange = { draft.rtspPort = it },
                label = { Text("RTSP port") },
                singleLine = true,
                colors = colors,
                shape = shape,
                textStyle = numeric,
                keyboardOptions = nextOptions,
                keyboardActions = KeyboardActions(onNext = { runCatching { schemeFocus.requestFocus() } }),
                modifier = Modifier
                    .width(FieldWidth)
                    .focusRequester(rtspFocus)
                    .focusProperties { up = hostFocus; down = schemeFocus }
                    .then(verticalNav),
            )
        }
        OptionRow("Scheme") {
            Scheme.entries.forEachIndexed { i, s ->
                FocusButton(
                    s.wire.uppercase(),
                    { draft.scheme = s },
                    selected = draft.scheme == s,
                    focusRequester = if (i == 0) schemeFocus else null,
                )
            }
        }
        OptionRow("Login") {
            FocusButton("Off", { draft.authEnabled = false }, selected = !draft.authEnabled)
            FocusButton("Required", { draft.authEnabled = true }, selected = draft.authEnabled)
        }
        if (draft.authEnabled) {
            OutlinedTextField(
                value = draft.username,
                onValueChange = { draft.username = it },
                label = { Text("Frigate username") },
                singleLine = true,
                colors = colors,
                shape = shape,
                keyboardOptions = nextOptions,
                keyboardActions = KeyboardActions(onNext = { runCatching { passwordFocus.requestFocus() } }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(usernameFocus)
                    .focusProperties { down = passwordFocus }
                    .then(verticalNav),
            )
            OutlinedTextField(
                value = draft.password,
                onValueChange = { draft.password = it },
                label = { Text("Frigate password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = colors,
                shape = shape,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(passwordFocus)
                    .focusProperties { up = usernameFocus }
                    .then(verticalNav),
            )
        }
        if (draft.scheme == Scheme.HTTPS) {
            OptionRow("Certificate") {
                FocusButton("System CA", { draft.trustSelfSigned = false }, selected = !draft.trustSelfSigned)
                FocusButton(
                    "Trust self-signed",
                    {
                        if (!draft.trustSelfSigned) draft.pinnedCertSha256 = null
                        draft.trustSelfSigned = true
                    },
                    selected = draft.trustSelfSigned,
                )
            }
            if (draft.trustSelfSigned) {
                Text(
                    draft.pinnedCertSha256?.let { "Pinned certificate SHA-256: $it" } ?: PIN_PENDING_HINT,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        FormFeedback(error = error, testResult = testResult, testing = testing)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            FocusButton("Test connection", onTest, enabled = !testing)
            FocusButton("Save & activate", { onSave(true) }, selected = true)
            FocusButton("Save", { onSave(false) })
            FocusButton("Cancel", onCancel)
        }
    }
}

/** Dark text fields: inset on [Ink], quiet border at rest, [Accent] border and label on focus. */
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = Ink,
    unfocusedContainerColor = Ink,
    focusedBorderColor = Accent,
    unfocusedBorderColor = PanelHi,
    focusedLabelColor = Accent,
    unfocusedLabelColor = TextSecondary,
    cursorColor = Accent,
)

/** Validation errors, the running connection test, and its result. Each state is shown where the user is looking. */
@Composable
private fun FormFeedback(error: String?, testResult: String?, testing: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        error?.let { Text(it, color = Danger, style = MaterialTheme.typography.bodyLarge) }
        if (testing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                PulsingDot(Accent, active = true)
                Text("Testing connection…", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
            }
        }
        AnimatedVisibility(visible = testResult != null && !testing, enter = fadeIn(tween(Motion.EnterMs))) {
            val connected = testResult?.startsWith("Connected") == true
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                PulsingDot(if (connected) LiveGreen else Danger, active = false)
                Text(
                    testResult.orEmpty(),
                    color = if (connected) LiveGreen else Danger,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Cameras

@Composable
private fun CamerasSection(
    cameras: List<CameraInfo>,
    cameraOrder: List<String>,
    favorites: Set<String>,
    connecting: Boolean,
    compact: Boolean,
    vm: AppViewModel,
) {
    SettingsGroup(
        title = "Camera order and favorites",
        subtitle = "The Live grid follows this order. Favorites are marked on their tiles.",
    ) {
        val ordered = remember(cameras, cameraOrder) { orderedCameras(cameras, cameraOrder) }
        if (ordered.isEmpty()) {
            if (connecting) {
                Box(Modifier.fillMaxWidth().height(Space.xxl * 2), contentAlignment = Alignment.Center) {
                    LoadingState("Loading cameras…")
                }
            } else {
                Text("No cameras discovered yet.", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
            }
            return@SettingsGroup
        }
        // Camera id and move direction of the row whose focus must follow it after a move.
        var focusAfterMove by remember { mutableStateOf<Pair<String, Int>?>(null) }
        ordered.forEachIndexed { index, cam ->
            key(cam.id) {
                if (index > 0) RowDivider()
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
                val favorite = cam.id in favorites
                val label: @Composable (Modifier) -> Unit = { m ->
                    CameraLabel(position = index + 1, cam = cam, favorite = favorite, modifier = m)
                }
                val actions: @Composable () -> Unit = {
                    FocusButton(
                        if (favorite) "Favorited" else "Favorite",
                        { vm.toggleFavorite(cam.id) },
                        selected = favorite,
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
                if (compact) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        label(Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) { actions() }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = RowMinHeight),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        label(Modifier.weight(1f))
                        actions()
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraLabel(position: Int, cam: CameraInfo, favorite: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            position.toString(),
            color = if (favorite) Accent else TextSecondary,
            style = MaterialTheme.typography.titleMedium.merge(NumericStyle),
            modifier = Modifier.width(Space.xl),
        )
        Text(
            displayName(cam.id),
            color = if (cam.enabled) TextPrimary else TextSecondary,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!cam.enabled) Text("(disabled)", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- Layout and connection

@Composable
private fun ColumnsOption(columns: Int, vm: AppViewModel) {
    OptionRow("Columns") {
        ColumnChoices.forEach { (cols, label) ->
            FocusButton(label, { vm.setColumns(cols) }, selected = columns == cols)
        }
    }
}

private val ColumnChoices = listOf(0 to "Automatic", 2 to "2 columns", 3 to "3 columns")

@Composable
private fun ConnectionSummary(
    connection: Connection,
    activeName: String?,
    cameraCount: Int,
    compact: Boolean,
    vm: AppViewModel,
) {
    val server = activeName ?: "server"
    val (title, detail, dotColor) = when (connection) {
        Connection.Idle -> Triple("Not connected", null, TextSecondary)
        Connection.Connecting -> Triple("Connecting to $server…", null, Warn)
        is Connection.Connected ->
            Triple("Connected to $server", "Frigate ${connection.version} · $cameraCount cameras", LiveGreen)
        is Connection.Failed -> Triple("Not connected", connection.reason, Danger)
    }
    val status: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            PulsingDot(dotColor, active = connection == Connection.Connecting)
            Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                if (detail != null) {
                    Text(
                        detail,
                        color = if (connection is Connection.Failed) Danger else TextSecondary,
                        style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
                    )
                }
            }
        }
    }
    if (compact) {
        status(Modifier.fillMaxWidth())
        FocusButton("Reconnect", { vm.reconnect() })
    } else {
        Row(
            Modifier.fillMaxWidth().heightIn(min = RowMinHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            status(Modifier.weight(1f))
            FocusButton("Reconnect", { vm.reconnect() })
        }
    }
}

