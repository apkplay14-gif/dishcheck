package ua.starlink.reader.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ua.starlink.reader.R
import ua.starlink.reader.DuplicatePrompt
import ua.starlink.reader.UiState
import ua.starlink.reader.data.CaptureSettings
import ua.starlink.reader.data.EditableField
import ua.starlink.reader.data.QrStep
import ua.starlink.reader.data.Reading
import ua.starlink.reader.net.LinkState
import ua.starlink.reader.util.Sharing

sealed interface Screen {
    data object Home : Screen
    data object History : Screen
    data class Detail(val uid: String) : Screen
}

/** Дії, які екрани делегують назовні (Activity / ViewModel). */
class AppActions(
    val onStartCapture: () -> Unit,
    val onRefreshLink: () -> Unit,
    val onOpenWifiSettings: () -> Unit,
    val onScanSingle: (uid: String, step: QrStep) -> Unit,
    val onFieldChange: (uid: String, field: EditableField, value: String) -> Unit,
    val onSettingsChange: (CaptureSettings) -> Unit,
    val onRetryNetwork: (uid: String) -> Unit,
    val onDelete: (uid: String) -> Unit,
    val onDeleteMany: (uids: List<String>) -> Unit,
    val onMergeDuplicate: () -> Unit,
    val onKeepDuplicate: () -> Unit,
    val onShare: (text: String) -> Unit,
    val onAllowRawProbe: (Boolean) -> Unit,
    val onDismissError: () -> Unit,
)

/** Одна позиція меню: номер, іконка, назва, підказка. */
private data class MenuItem(
    val number: Int,
    val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val hintRes: Int,
)

private val MENU_KIT =
    MenuItem(1, R.drawable.ic_box, R.string.menu_kit, R.string.hint_qr)
private val MENU_DISH_SN =
    MenuItem(2, R.drawable.ic_dish, R.string.menu_dish_serial, R.string.hint_qr)
private val MENU_MODEM_SN =
    MenuItem(3, R.drawable.ic_router, R.string.menu_modem_serial, R.string.hint_qr)
private val MENU_STARLINK_ID =
    MenuItem(4, R.drawable.ic_signal, R.string.menu_starlink_id, R.string.hint_from_dish)
private val MENU_ROUTER_ID =
    MenuItem(5, R.drawable.ic_id, R.string.menu_router_id, R.string.hint_from_router)
private val MENU_MAC =
    MenuItem(6, R.drawable.ic_mac, R.string.menu_mac, R.string.hint_from_router)

@Composable
fun AppRoot(state: UiState, actions: AppActions) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    when (val current = screen) {
        Screen.Home -> HomeScreen(
            state = state,
            actions = actions,
            onOpenHistory = { screen = Screen.History },
        )

        Screen.History -> HistoryScreen(
            state = state,
            actions = actions,
            onBack = { screen = Screen.Home },
            onOpen = { screen = Screen.Detail(it) },
        )

        is Screen.Detail -> {
            val reading = state.history.firstOrNull { it.uid == current.uid }
            if (reading != null) {
                DetailScreen(
                    reading = reading,
                    actions = actions,
                    onBack = { screen = Screen.History },
                )
            } else {
                // Запис видалили з екрана деталей — показуємо список.
                HistoryScreen(
                    state = state,
                    actions = actions,
                    onBack = { screen = Screen.Home },
                    onOpen = { screen = Screen.Detail(it) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ головний екран

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(state: UiState, actions: AppActions, onOpenHistory: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brand.Ink)) {
        OrbitBackdrop(Modifier.fillMaxWidth().height(340.dp))

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { WordMark() },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    actions = {
                        TextButton(onClick = onOpenHistory) {
                            RowIcon(R.drawable.ic_history, Brand.TextPrimary, size = 18)
                            Spacer(Modifier.width(6.dp))
                            Text(state.history.size.toString(), color = Brand.TextPrimary)
                        }
                    },
                )
            },
            bottomBar = { AdBanner() },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LinkStatusCard(state, actions)
                CaptureMenuCard(state.settings, actions.onSettingsChange)
                StartButton(state, actions)

                state.error?.let { message -> ErrorCard(message, actions.onDismissError) }

                state.current?.let { reading ->
                    ReadingCard(reading = reading, actions = actions, showDelete = false)
                }

                AdvancedSection(state, actions)
                Spacer(Modifier.height(16.dp))
            }
        }

        state.duplicate?.let { prompt ->
            DuplicateDialog(
                prompt = prompt,
                onMerge = actions.onMergeDuplicate,
                onKeepNew = actions.onKeepDuplicate,
            )
        }
    }
}

/**
 * Той самий Starlink уже є в історії. Пропонуємо два виходи; закриття діалогу
 * рівносильне «створити новий», бо це нічого не втрачає.
 */
@Composable
private fun DuplicateDialog(
    prompt: DuplicatePrompt,
    onMerge: () -> Unit,
    onKeepNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepNew,
        containerColor = Brand.SurfaceRaised,
        title = { Text(stringResource(R.string.duplicate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(prompt.starlinkId, style = MonoValue, color = Brand.TextPrimary)
                Text(
                    stringResource(
                        R.string.duplicate_existing,
                        Sharing.formatDate(prompt.existingTimestamp),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.duplicate_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.TextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onMerge) { Text(stringResource(R.string.action_update_existing)) }
        },
        dismissButton = {
            TextButton(onClick = onKeepNew) { Text(stringResource(R.string.action_create_new)) }
        },
    )
}

@Composable
private fun StartButton(state: UiState, actions: AppActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = actions.onStartCapture,
            enabled = !state.reading && state.settings.anyEnabled,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = Brand.Accent,
                contentColor = Color(0xFF04070E),
            ),
        ) {
            if (state.reading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF04070E),
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.state_polling), style = MaterialTheme.typography.titleMedium)
            } else {
                RowIcon(R.drawable.ic_qr, Color(0xFF04070E), size = 22)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.btn_start_capture),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (!state.settings.anyEnabled) {
            Text(
                stringResource(R.string.warn_select_one),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.Alert,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    BrandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Brand.Alert)
                )
                SectionLabel(stringResource(R.string.error_label))
            }
            Text(message, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_understood)) }
        }
    }
}

@Composable
private fun LinkStatusCard(state: UiState, actions: AppActions) {
    data class Look(val color: Color, val pill: String, val title: String, val hint: String)

    val look = when (state.link) {
        LinkState.NO_WIFI -> Look(
            Brand.Alert,
            stringResource(R.string.link_no_wifi_pill),
            stringResource(R.string.link_no_wifi_title),
            stringResource(R.string.link_no_wifi_hint),
        )

        LinkState.WIFI_NO_DISH -> Look(
            Brand.Waiting,
            stringResource(R.string.link_silent_pill),
            stringResource(R.string.link_silent_title),
            stringResource(R.string.link_silent_hint),
        )

        LinkState.READY -> Look(
            Brand.Online,
            stringResource(R.string.link_ready_pill),
            stringResource(R.string.link_ready_title),
            stringResource(R.string.link_ready_hint),
        )
    }

    BrandCard(accent = state.link == LinkState.READY) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RowIcon(R.drawable.ic_signal, look.color, size = 24)
                Column(Modifier.weight(1f)) {
                    Text(look.title, style = MaterialTheme.typography.titleMedium)
                }
                if (state.checkingLink) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = look.color,
                    )
                } else {
                    StatusPill(look.color, look.pill)
                }
            }

            Text(
                look.hint,
                style = MaterialTheme.typography.bodySmall,
                color = Brand.TextMuted,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.link == LinkState.NO_WIFI) {
                    OutlinedButton(
                        onClick = actions.onOpenWifiSettings,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(stringResource(R.string.action_wifi_settings))
                    }
                }
                TextButton(onClick = actions.onRefreshLink) {
                    RowIcon(R.drawable.ic_refresh, Brand.Accent, size = 16)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_check))
                }
            }
        }
    }
}

/** Головне меню: що саме знімати з комплекту, у порядку зчитування. */
@Composable
private fun CaptureMenuCard(settings: CaptureSettings, onChange: (CaptureSettings) -> Unit) {
    var expanded by remember { mutableStateOf(true) }

    BrandCard {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SectionLabel(stringResource(R.string.capture_menu_title))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.capture_menu_selected, settings.enabledCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    stringResource(
                        if (expanded) R.string.action_collapse else R.string.action_expand
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Accent,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                MenuRow(MENU_KIT, settings.kitNumber) { onChange(settings.copy(kitNumber = it)) }
                MenuRow(MENU_DISH_SN, settings.dishSerial) { onChange(settings.copy(dishSerial = it)) }
                MenuRow(MENU_MODEM_SN, settings.modemSerial) {
                    onChange(settings.copy(modemSerial = it))
                }
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Brand.Hairline,
                )
                MenuRow(MENU_STARLINK_ID, settings.starlinkId) {
                    onChange(settings.copy(starlinkId = it))
                }
                MenuRow(MENU_ROUTER_ID, settings.routerId) { onChange(settings.copy(routerId = it)) }
                MenuRow(MENU_MAC, settings.modemMac) { onChange(settings.copy(modemMac = it)) }
            }
        }
    }
}

@Composable
private fun MenuRow(item: MenuItem, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepBadge(item.number, checked)
        RowIcon(item.iconRes, if (checked) Brand.Accent else Brand.TextMuted)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(item.titleRes),
                style = MaterialTheme.typography.bodyLarge,
                color = if (checked) Brand.TextPrimary else Brand.TextMuted,
            )
            Text(stringResource(item.hintRes), style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Brand.Accent,
                checkmarkColor = Color(0xFF04070E),
                uncheckedColor = Brand.Hairline,
            ),
        )
    }
}

@Composable
private fun AdvancedSection(state: UiState, actions: AppActions) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(
                stringResource(
                    if (expanded) R.string.advanced_collapse else R.string.advanced_expand
                ),
                color = Brand.TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (expanded) {
            BrandCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.fallback_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.fallback_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = Brand.TextMuted,
                            )
                        }
                        Switch(
                            checked = state.allowRawProbe,
                            onCheckedChange = actions.onAllowRawProbe,
                        )
                    }

                    state.diagnostics?.takeIf { it.isNotBlank() }?.let { log ->
                        HorizontalDivider(color = Brand.Hairline)
                        SectionLabel(stringResource(R.string.log_title))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(Brand.Ink)
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                log,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = Brand.TextMuted,
                            )
                        }
                        TextButton(onClick = { actions.onShare(log) }) {
                            RowIcon(R.drawable.ic_share, Brand.Accent, size = 16)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_send_log))
                        }
                    }

                    PrivacyOptionsEntry()
                }
            }
        }
    }
}

// ------------------------------------------------------------------ картка зчитування

@Composable
fun ReadingCard(reading: Reading, actions: AppActions, showDelete: Boolean) {
    var confirmDelete by remember { mutableStateOf(false) }
    val settings = reading.settings
    val context = LocalContext.current

    BrandCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.section_kit))
                Spacer(Modifier.weight(1f))
                Text(
                    Sharing.formatDate(reading.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.TextMuted,
                )
            }

            if (settings.kitNumber) {
                ScanField(MENU_KIT, reading.kitNumber,
                    { actions.onFieldChange(reading.uid, EditableField.KIT, it) },
                    { actions.onScanSingle(reading.uid, QrStep.KIT) })
            }
            if (settings.dishSerial) {
                ScanField(MENU_DISH_SN, reading.dishSerial,
                    { actions.onFieldChange(reading.uid, EditableField.DISH_SERIAL, it) },
                    { actions.onScanSingle(reading.uid, QrStep.DISH_SERIAL) })
            }
            if (settings.modemSerial) {
                ScanField(MENU_MODEM_SN, reading.modemSerial,
                    { actions.onFieldChange(reading.uid, EditableField.MODEM_SERIAL, it) },
                    { actions.onScanSingle(reading.uid, QrStep.MODEM_SERIAL) })
            }

            if (settings.starlinkId || settings.routerId || settings.modemMac) {
                HorizontalDivider(color = Brand.Hairline)
                SectionLabel(stringResource(R.string.section_network))

                if (settings.starlinkId) {
                    NetworkValue(MENU_STARLINK_ID, reading.starlinkId)
                }
                if (settings.routerId) {
                    NetworkValue(MENU_ROUTER_ID, reading.routerId)
                }
                if (settings.modemMac) {
                    ScanField(MENU_MAC, reading.effectiveMac,
                        { actions.onFieldChange(reading.uid, EditableField.MODEM_MAC, it) }, null)
                }

                TextButton(onClick = { actions.onRetryNetwork(reading.uid) }) {
                    RowIcon(R.drawable.ic_refresh, Brand.Accent, size = 16)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_retry_network))
                }
            }

            HorizontalDivider(color = Brand.Hairline)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RowIcon(R.drawable.ic_note)
                SectionLabel(stringResource(R.string.notes_label))
            }
            OutlinedTextField(
                value = reading.note,
                onValueChange = { actions.onFieldChange(reading.uid, EditableField.NOTE, it) },
                placeholder = {
                    Text(stringResource(R.string.notes_placeholder), color = Brand.TextMuted)
                },
                minLines = 3,
                shape = MaterialTheme.shapes.small,
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { actions.onShare(Sharing.buildText(context, reading)) },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Brand.Accent,
                        contentColor = Color(0xFF04070E),
                    ),
                ) {
                    RowIcon(R.drawable.ic_share, Color(0xFF04070E), size = 18)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_share))
                }
                if (showDelete) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.height(50.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Brand.Alert
                        ),
                    ) {
                        RowIcon(R.drawable.ic_delete, Brand.Alert, size = 18)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Brand.SurfaceRaised,
            title = { Text(stringResource(R.string.delete_one_title)) },
            text = { Text(stringResource(R.string.delete_one_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.onDelete(reading.uid)
                }) { Text(stringResource(R.string.action_delete), color = Brand.Alert) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Поле, яке заповнюється сканером, але яке завжди можна виправити руками. */
@Composable
private fun ScanField(
    item: MenuItem,
    value: String,
    onChange: (String) -> Unit,
    onScan: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StepBadge(item.number, value.isNotBlank())
            RowIcon(item.iconRes)
            Text(
                stringResource(item.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.TextMuted,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MonoValue,
                placeholder = { Text("—", color = Brand.TextMuted) },
                shape = MaterialTheme.shapes.small,
                colors = fieldColors(),
                modifier = Modifier.weight(1f),
            )
            if (onScan != null) {
                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.height(56.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    RowIcon(R.drawable.ic_qr, Brand.Accent, size = 20)
                }
            }
        }
    }
}

/** Значення, зняте по мережі, з версіями заліза й ПЗ під ним. */
@Composable
private fun NetworkValue(item: MenuItem, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StepBadge(item.number, value.isNotBlank())
            RowIcon(item.iconRes)
            Text(
                stringResource(item.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.TextMuted,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(Brand.Ink)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                value.ifBlank { stringResource(R.string.value_not_read) },
                style = if (value.isBlank()) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MonoValue
                },
                color = if (value.isBlank()) Brand.TextMuted else Brand.TextPrimary,
            )
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Brand.Accent,
    unfocusedBorderColor = Brand.Hairline,
    focusedContainerColor = Brand.Ink,
    unfocusedContainerColor = Brand.Ink,
    cursorColor = Brand.Accent,
)

// ------------------------------------------------------------------ історія

/** Вибрані записи мають пережити поворот екрана. */
private val UidSetSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

private fun Set<String>.toggle(uid: String): Set<String> =
    if (uid in this) this - uid else this + uid

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HistoryScreen(
    state: UiState,
    actions: AppActions,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var selected by rememberSaveable(stateSaver = UidSetSaver) { mutableStateOf(emptySet<String>()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    // Записи від новіших до старіших, розбиті по днях.
    val groups = remember(state.history) {
        state.history.sortedByDescending { it.timestamp }
            .groupBy { Sharing.dayOf(it.timestamp) }
            .toList()
    }
    val today = remember { Sharing.dayOf(System.currentTimeMillis()) }

    // Видалений запис не має лишатись у вибраному, тому рахуємо по живих.
    val chosen = state.history.filter { it.uid in selected }.sortedByDescending { it.timestamp }
    val context = LocalContext.current

    Scaffold(
        containerColor = Brand.Ink,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (picking) {
                            stringResource(R.string.history_selected, chosen.size)
                        } else {
                            stringResource(R.string.history_title)
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    TextButton(
                        onClick = {
                            if (picking) {
                                picking = false
                                selected = emptySet()
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        RowIcon(R.drawable.ic_back, Brand.TextPrimary, size = 20)
                    }
                },
                actions = {
                    if (picking) {
                        val allSelected = chosen.size == state.history.size
                        TextButton(
                            onClick = {
                                selected = if (allSelected) {
                                    emptySet()
                                } else {
                                    state.history.map { it.uid }.toSet()
                                }
                            }
                        ) {
                            Text(
                                stringResource(
                                    if (allSelected) R.string.action_deselect_all
                                    else R.string.action_select_all
                                )
                            )
                        }
                    } else if (state.history.isNotEmpty()) {
                        TextButton(onClick = { picking = true }) {
                            Text(stringResource(R.string.action_select))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (picking && chosen.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { actions.onShare(Sharing.buildText(context, chosen)) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Brand.Accent,
                            contentColor = Color(0xFF04070E),
                        ),
                    ) {
                        RowIcon(R.drawable.ic_share, Color(0xFF04070E), size = 18)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_share_count, chosen.size))
                    }
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.height(52.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Brand.Alert
                        ),
                    ) {
                        RowIcon(R.drawable.ic_delete, Brand.Alert, size = 18)
                    }
                }
            }
        },
    ) { padding ->
        if (state.history.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppLogo(size = 56)
                Text(
                    stringResource(R.string.history_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.history_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.TextMuted,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            groups.forEach { (day, dayReadings) ->
                val dayUids = dayReadings.map { it.uid }.toSet()

                item(key = "day-$day") {
                    DayHeader(
                        label = Sharing.dayLabel(context, day, today),
                        count = dayReadings.size,
                        picking = picking,
                        allSelected = selected.containsAll(dayUids),
                        onToggleDay = {
                            selected = if (selected.containsAll(dayUids)) {
                                selected - dayUids
                            } else {
                                selected + dayUids
                            }
                        },
                    )
                }

                items(dayReadings, key = { it.uid }) { reading ->
                    HistoryRow(
                        reading = reading,
                        picking = picking,
                        checked = reading.uid in selected,
                        onClick = {
                            if (picking) {
                                selected = selected.toggle(reading.uid)
                            } else {
                                onOpen(reading.uid)
                            }
                        },
                        onLongClick = {
                            picking = true
                            selected = selected + reading.uid
                        },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Brand.SurfaceRaised,
            title = {
                Text(
                    stringResource(
                        R.string.delete_many_title,
                        pluralStringResource(R.plurals.records, chosen.size, chosen.size),
                    )
                )
            },
            text = {
                Text(stringResource(R.string.delete_many_text))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        actions.onDeleteMany(chosen.map { it.uid })
                        picking = false
                        selected = emptySet()
                    }
                ) {
                    Text(stringResource(R.string.action_delete), color = Brand.Alert)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DayHeader(
    label: String,
    count: Int,
    picking: Boolean,
    allSelected: Boolean,
    onToggleDay: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(label)
        Spacer(Modifier.width(10.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = Brand.TextMuted,
        )
        Spacer(Modifier.weight(1f))
        if (picking) {
            TextButton(onClick = onToggleDay) {
                Text(
                    stringResource(
                        if (allSelected) R.string.day_deselect else R.string.day_select
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Accent,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(
    reading: Reading,
    picking: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    BrandCard(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        accent = picking && checked,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (picking) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onClick() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Brand.Accent,
                        checkmarkColor = Color(0xFF04070E),
                        uncheckedColor = Brand.Hairline,
                    ),
                )
            } else {
                RowIcon(R.drawable.ic_box, Brand.Accent, size = 22)
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(reading.titleOrNull ?: stringResource(R.string.record_untitled), style = MonoValue)
                Text(
                    Sharing.formatTime(reading.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.TextMuted,
                )
                if (reading.note.isNotBlank()) {
                    Text(
                        reading.note.lineSequence().first(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.TextMuted,
                        maxLines = 1,
                    )
                }
            }

            if (!picking) {
                Text("›", color = Brand.TextMuted, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(reading: Reading, actions: AppActions, onBack: () -> Unit) {
    Scaffold(
        containerColor = Brand.Ink,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        reading.titleOrNull ?: stringResource(R.string.record_untitled),
                        maxLines = 1,
                        style = MonoValue,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        RowIcon(R.drawable.ic_back, Brand.TextPrimary, size = 20)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ReadingCard(reading = reading, actions = actions, showDelete = true)
            Spacer(Modifier.height(16.dp))
        }
    }
}
