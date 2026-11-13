package com.chillpill.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chillpill.R
import com.chillpill.data.restricted.AppPolicy
import com.chillpill.service.engine.Mode

/**
 * Editor for one [AppPolicy] (specs/per-app-modes.md): "When I open {app}" with the three modes,
 * then only the fields the selected mode uses, on each restricted app's card. Values differing from
 * [original] are highlighted as pending, like the rest of Settings.
 */
@Composable
fun PolicyEditor(
    /** App name for the heading ("When I open {app}"). */
    appName: String,
    draft: PolicyDraft,
    original: AppPolicy,
    onModeChange: (Mode) -> Unit,
    onFieldChange: (PolicyField, String) -> Unit,
    onFieldFocusLost: (PolicyField) -> Unit,
    onExtensionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val effective = draft.toPolicy(original)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.policy_when_i_open, appName),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Column(modifier = Modifier.selectableGroup()) {
            for (mode in Mode.values()) {
                ModeRow(
                    mode = mode,
                    minutes = effective.minutes,
                    selected = draft.mode == mode,
                    pending = draft.mode == mode && mode != original.mode,
                    onSelect = { onModeChange(mode) }
                )
            }
        }
        for (field in PolicyDraft.fields(draft.mode)) {
            Spacer(modifier = Modifier.height(10.dp))
            PolicyNumberField(
                label = fieldLabel(field, draft.mode),
                text = draft.text(field),
                isError = !draft.isValid(field),
                pending = draft.isValid(field) &&
                    draft.text(field).toInt() != PolicyDraft.valueOf(original, field),
                onValueChange = { onFieldChange(field, it) },
                onFocusLost = { onFieldFocusLost(field) }
            )
        }
        if (draft.mode != Mode.PAUSE_ONLY) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pending = draft.extensionAllowed != original.extensionAllowed
                Text(
                    text = stringResource(R.string.policy_allow_extension),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (pending) PendingWaitLabelColor else Color.Unspecified,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = draft.extensionAllowed, onCheckedChange = onExtensionChange)
            }
        }
    }
}

@Composable
private fun ModeRow(mode: Mode, minutes: Int, selected: Boolean, pending: Boolean, onSelect: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (pending) BorderStroke(1.5.dp, PendingWaitBorderColor) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
    ) {
        Row(
            modifier = Modifier.padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
            Column {
                Text(
                    text = modeName(mode),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = when (mode) {
                        Mode.PAUSE_ONLY -> stringResource(R.string.policy_mode_pause_description)
                        Mode.TIME_LIMIT -> stringResource(R.string.policy_mode_limit_description, minutes)
                        Mode.PICK -> stringResource(R.string.policy_mode_pick_description)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun modeName(mode: Mode): String = when (mode) {
    Mode.PAUSE_ONLY -> stringResource(R.string.policy_mode_pause)
    Mode.TIME_LIMIT -> stringResource(R.string.policy_mode_limit)
    Mode.PICK -> stringResource(R.string.policy_mode_pick)
}

@Composable
private fun fieldLabel(field: PolicyField, mode: Mode): String = when (field) {
    PolicyField.OPEN_WAIT -> stringResource(R.string.policy_open_wait)
    PolicyField.MINUTES -> if (mode == Mode.PAUSE_ONLY) {
        stringResource(R.string.policy_free_reentry)
    } else {
        stringResource(R.string.policy_time_allowed)
    }
    PolicyField.TIMES_UP_WAIT -> if (mode == Mode.PICK) {
        stringResource(R.string.policy_pick_again_wait)
    } else {
        stringResource(R.string.policy_times_up_wait)
    }
}

/** Number field with the Settings pending / error colours; reports focus loss for validation. */
@Composable
fun PolicyNumberField(
    label: String,
    text: String,
    isError: Boolean,
    pending: Boolean,
    onValueChange: (String) -> Unit,
    onFocusLost: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null
) {
    OutlinedTextField(
        value = text,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = if (supporting != null) {
            { Text(supporting) }
        } else {
            null
        },
        isError = isError,
        colors = if (pending) {
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PendingWaitBorderColor,
                unfocusedBorderColor = PendingWaitBorderColor,
                focusedLabelColor = PendingWaitLabelColor,
                unfocusedLabelColor = PendingWaitLabelColor,
                cursorColor = PendingWaitBorderColor
            )
        } else {
            OutlinedTextFieldDefaults.colors()
        },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) onFocusLost() },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

/** One-line description of a policy for a collapsed app card, e.g. "Time limit · 12 s · 5 min". */
@Composable
fun policySummary(policy: AppPolicy): String = when (policy.mode) {
    Mode.PAUSE_ONLY -> stringResource(
        R.string.policy_summary_pause, modeName(policy.mode), policy.openWaitSeconds, policy.minutes
    )
    Mode.TIME_LIMIT -> stringResource(
        R.string.policy_summary_limit, modeName(policy.mode), policy.openWaitSeconds, policy.minutes
    )
    Mode.PICK -> stringResource(R.string.policy_summary_pick, modeName(policy.mode), policy.openWaitSeconds)
}
