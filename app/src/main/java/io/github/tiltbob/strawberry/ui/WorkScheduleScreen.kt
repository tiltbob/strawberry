package io.github.tiltbob.strawberry.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.tiltbob.strawberry.R
import io.github.tiltbob.strawberry.core.ApplyResult
import io.github.tiltbob.strawberry.platform.ProfileCandidate
import io.github.tiltbob.strawberry.platform.ProfileKind
import io.github.tiltbob.strawberry.platform.ProfileState
import io.github.tiltbob.strawberry.platform.describeResult
import io.github.tiltbob.strawberry.schedule.Schedule
import io.github.tiltbob.strawberry.schedule.weekStartingOn
import io.github.tiltbob.strawberry.ui.theme.WorkScheduleTheme
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** What the screen can ask the app to do. */
interface ScreenActions {
    fun onScheduleChange(schedule: Schedule) {}
    fun onChooseProfile(serial: Long) {}
    fun onRescan() {}
    fun onCopy(text: String) {}
    fun onAllowNotifications() {}
    fun onOpenExactAlarmSettings() {}
    fun onOpenUnusedAppSettings() {}
    fun onRequestBatteryExemption() {}
    fun onTurnOnWorkApps() {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkScheduleScreen(state: ScreenState?, actions: ScreenActions) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        if (state == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val context = LocalContext.current
        // Recreated on every refresh so a changed 12/24 hour setting shows up on resume.
        val formats = remember(context, state.now) { Formats(context) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(state, formats, actions)
            SetupCard(state, actions)
            ScheduleCard(state.schedule, formats, actions::onScheduleChange)
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

// ---- Status ----

@Composable
private fun StatusCard(state: ScreenState, formats: Formats, actions: ScreenActions) {
    val selected = state.profile as? ProfileState.Selected
    val profile = selected?.profile
    val schedule = state.schedule
    var choosing by rememberSaveable { mutableStateOf(false) }

    SectionCard {
        Text(
            text = stringResource(
                when {
                    profile == null -> R.string.status_work_unknown
                    profile.paused -> R.string.status_work_paused
                    else -> R.string.status_work_on
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (selected != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.status_profile, selected.profile.label),
                    modifier = Modifier.weight(1f),
                )
                if (selected.alternatives.isNotEmpty()) {
                    TextButton(onClick = { choosing = true }) {
                        Text(stringResource(R.string.status_change_profile))
                    }
                }
            }
        }
        Text(nextChangeText(state, formats))

        val status = state.status
        val result = status?.lastApplyResult
        if (status?.lastApplyAt != null && result != null) {
            Text(
                text = stringResource(
                    R.string.status_last_applied,
                    formats.moment(status.lastApplyAt, state.now, state.zone),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = describeResult(LocalContext.current, result),
                style = MaterialTheme.typography.bodySmall,
                color = if (result.isDefinitive) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        if (schedule.enabled && status?.nextAlarmAt != null && !status.nextAlarmExact) {
            Text(
                text = stringResource(R.string.status_inexact),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (profile != null && profile.paused && result == ApplyResult.NeedsCredential) {
            Button(onClick = actions::onTurnOnWorkApps) {
                Text(stringResource(R.string.status_turn_on_now))
            }
        }
    }

    if (choosing && selected != null) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.profile_choose_title)) },
            text = {
                ProfileChoices(listOf(selected.profile) + selected.alternatives) {
                    choosing = false
                    actions.onChooseProfile(it)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun nextChangeText(state: ScreenState, formats: Formats): String {
    val schedule = state.schedule
    if (!schedule.enabled) return stringResource(R.string.status_schedule_off)
    val next = schedule.nextTransition(state.now, state.zone)
        ?: return stringResource(R.string.status_no_changes)
    val moment = formats.moment(next, state.now, state.zone)
    return if (schedule.isWorkOnAt(next, state.zone)) {
        stringResource(R.string.status_turns_on, moment)
    } else {
        stringResource(R.string.status_pauses, moment)
    }
}

// ---- Setup checklist ----

@Composable
private fun SetupCard(state: ScreenState, actions: ScreenActions) {
    val stepsLeft = state.requiredStepsLeft
    if (stepsLeft == 0 && state.batteryUnrestricted) return
    val packageName = LocalContext.current.packageName

    SectionCard {
        Text(
            text = if (stepsLeft > 0) {
                pluralStringResource(R.plurals.setup_title, stepsLeft, stepsLeft)
            } else {
                stringResource(R.string.setup_title_recommended)
            },
            style = MaterialTheme.typography.titleLarge,
        )
        if (!state.hasQuietModePermission) {
            SetupStep(R.string.setup_permission_title, R.string.setup_permission_why) {
                CommandBox(AdbCommands.grant(packageName, state.userId), actions)
                Text(
                    text = stringResource(R.string.setup_permission_verify),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        ProfileStep(state.profile, actions)
        if (!state.notificationsAllowed) {
            SetupStep(R.string.setup_notifications_title, R.string.setup_notifications_why) {
                Button(onClick = actions::onAllowNotifications) { Text(stringResource(R.string.setup_allow)) }
            }
        }
        if (!state.exactAlarmsAllowed) {
            SetupStep(R.string.setup_exact_title, R.string.setup_exact_why) {
                Button(onClick = actions::onOpenExactAlarmSettings) {
                    Text(stringResource(R.string.setup_open_settings))
                }
            }
        }
        if (state.unusedAppRestrictionsOn) {
            SetupStep(R.string.setup_unused_title, R.string.setup_unused_why) {
                Button(onClick = actions::onOpenUnusedAppSettings) {
                    Text(stringResource(R.string.setup_open_settings))
                }
            }
        }
        if (!state.batteryUnrestricted) {
            SetupStep(R.string.setup_battery_title, R.string.setup_battery_why, recommended = true) {
                CommandBox(AdbCommands.batteryWhitelist(packageName), actions)
                OutlinedButton(onClick = actions::onRequestBatteryExemption) {
                    Text(stringResource(R.string.setup_allow))
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    title: Int,
    why: Int,
    recommended: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (recommended) {
            Text(
                text = stringResource(R.string.setup_recommended),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(why), style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun CommandBox(command: String, actions: ScreenActions) {
    var copied by remember(command) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            SelectionContainer {
                Text(
                    text = command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(
                onClick = {
                    actions.onCopy(command)
                    copied = true
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(if (copied) R.string.setup_copied else R.string.setup_copy))
            }
        }
    }
}

@Composable
private fun ProfileStep(profile: ProfileState, actions: ScreenActions) {
    when (profile) {
        is ProfileState.Selected -> Unit
        ProfileState.None -> SetupStep(R.string.setup_no_profile_title, R.string.setup_no_profile_why) {
            OutlinedButton(onClick = actions::onRescan) { Text(stringResource(R.string.setup_scan_again)) }
        }
        is ProfileState.NeedsChoice -> SetupStep(R.string.setup_choose_title, R.string.setup_choose_why) {
            ProfileChoices(profile.candidates, actions::onChooseProfile)
        }
        is ProfileState.NeedsConfirmation ->
            SetupStep(R.string.setup_identify_title, R.string.setup_identify_why) {
                Text(stringResource(R.string.setup_identify_manual), style = MaterialTheme.typography.bodyMedium)
                ProfileChoices(profile.candidates, actions::onChooseProfile)
                OutlinedButton(onClick = actions::onRescan) { Text(stringResource(R.string.setup_scan_again)) }
            }
    }
}

/** A list of profiles to pick from. Unidentified profiles ask for confirmation first. */
@Composable
private fun ProfileChoices(candidates: List<ProfileCandidate>, onChoose: (Long) -> Unit) {
    var confirming by remember { mutableStateOf<ProfileCandidate?>(null) }
    Column {
        candidates.forEach { candidate ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) {
                        if (candidate.kind == ProfileKind.UNKNOWN) confirming = candidate else onChoose(candidate.serial)
                    }
                    .padding(vertical = 8.dp),
            ) {
                Text(candidate.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(
                        R.string.profile_row_detail,
                        candidate.serial,
                        stringResource(if (candidate.paused) R.string.profile_paused else R.string.profile_on),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    confirming?.let { candidate ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.profile_confirm_title)) },
            text = { Text(stringResource(R.string.profile_confirm_text, candidate.label)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    onChoose(candidate.serial)
                }) { Text(stringResource(R.string.profile_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

// ---- Schedule editor ----

private enum class TimeField { START, END }

@Composable
private fun ScheduleCard(schedule: Schedule, formats: Formats, onChange: (Schedule) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<TimeField?>(null) }

    SectionCard {
        Text(stringResource(R.string.schedule_title), style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = schedule.enabled,
                    role = Role.Switch,
                    onValueChange = { onChange(schedule.copy(enabled = it)) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.schedule_follow), modifier = Modifier.weight(1f))
            Switch(checked = schedule.enabled, onCheckedChange = null)
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.schedule_from))
            TimeButton(
                text = formats.time(schedule.start),
                description = R.string.schedule_start_description,
                onClick = { editing = TimeField.START },
            )
            Text(stringResource(R.string.schedule_to))
            TimeButton(
                text = formats.time(schedule.end),
                description = R.string.schedule_end_description,
                onClick = { editing = TimeField.END },
            )
        }

        Text(stringResource(R.string.schedule_days), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            weekStartingOn(formats.firstDayOfWeek).forEach { day ->
                val selected = day in schedule.days
                FilterChip(
                    selected = selected,
                    onClick = {
                        onChange(schedule.copy(days = if (selected) schedule.days - day else schedule.days + day))
                    },
                    label = { Text(formats.shortDay(day)) },
                    modifier = Modifier.semantics { contentDescription = formats.fullDay(day) },
                )
            }
        }

        Text(
            text = formats.summary(schedule),
            color = if (schedule.days.isEmpty()) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }

    editing?.let { field ->
        TimeDialog(
            title = stringResource(
                if (field == TimeField.START) R.string.schedule_pick_start else R.string.schedule_pick_end,
            ),
            initial = if (field == TimeField.START) schedule.start else schedule.end,
            is24Hour = formats.is24Hour,
            onDismiss = { editing = null },
            onConfirm = { time ->
                editing = null
                onChange(if (field == TimeField.START) schedule.copy(start = time) else schedule.copy(end = time))
            },
        )
    }
}

@Composable
private fun TimeButton(text: String, description: Int, onClick: () -> Unit) {
    val label = stringResource(description, text)
    OutlinedButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    title: String,
    initial: LocalTime,
    is24Hour: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val pickerState = rememberTimePickerState(initial.hour, initial.minute, is24Hour)
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    ) {
        TimePicker(state = pickerState)
    }
}

@Preview(showBackground = true)
@Composable
private fun WorkScheduleScreenPreview() {
    WorkScheduleTheme {
        WorkScheduleScreen(
            state = ScreenState(
                schedule = Schedule(enabled = true),
                profile = ProfileState.None,
                status = null,
                hasQuietModePermission = false,
                userId = 0,
                notificationsAllowed = true,
                exactAlarmsAllowed = true,
                unusedAppRestrictionsOn = false,
                batteryUnrestricted = false,
                now = Instant.now(),
                zone = ZoneId.systemDefault(),
            ),
            actions = object : ScreenActions {},
        )
    }
}
