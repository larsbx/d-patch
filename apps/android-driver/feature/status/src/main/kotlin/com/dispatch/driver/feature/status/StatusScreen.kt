package com.dispatch.driver.feature.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dispatch.driver.core.database.OutboxEntry
import com.dispatch.driver.core.database.PendingState
import com.dispatch.driver.core.model.capability.StatusOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun StatusRoute(viewModel: StatusViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    StatusScreen(state, error, onDeclare = viewModel::declare, onRefresh = viewModel::refreshCapabilities)
}

/**
 * The status surface. Every choice on it comes from the capability document;
 * the screen has no list of statuses and no idea which role it is serving.
 *
 * Section 26.7: state is always carried in text, never by colour alone.
 */
@Composable
fun StatusScreen(
    state: StatusUiState,
    error: String?,
    onDeclare: (value: String, note: String?) -> Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            StatusUiState.Loading -> Text("Loading your role…")
            is StatusUiState.NotOffered -> {
                Text(state.message, modifier = Modifier.testTag("status-not-offered"))
                OutlinedButton(onClick = onRefresh) { Text("Try again") }
            }
            is StatusUiState.Ready -> Ready(state, error, onDeclare)
        }
    }
}

@Composable
private fun Ready(state: StatusUiState.Ready, error: String?, onDeclare: (String, String?) -> Boolean) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }

    if (state.capabilitiesStale) {
        Text(
            "Offline: showing the choices your role had when last connected.",
            modifier = Modifier.testTag("capabilities-stale"),
        )
    }

    CurrentStatusCard(state.current)
    state.attention.forEach { entry -> AttentionCard(entry, state.options.labelOf(entry.status)) }

    Text("Declare status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    state.options.forEach { option ->
        val needsNote = option.requiresNote
        Button(
            onClick = {
                if (needsNote) {
                    selected = option.value
                } else {
                    selected = null
                    onDeclare(option.value, null)
                }
            },
            // Large targets: Section 25.5 reduces status to one-tap choices,
            // and the same size serves everyone else too.
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("status-option-${option.value}"),
        ) { Text(if (needsNote) "${option.label} (add a note)" else option.label) }
    }

    state.options.firstOrNull { it.value == selected }?.let { option ->
        NoteEntry(option, note, onNote = { note = it }) {
            if (onDeclare(option.value, note)) {
                selected = null
                note = ""
            }
        }
    }

    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("status-error")) }
}

@Composable
private fun NoteEntry(option: StatusOption, note: String, onNote: (String) -> Unit, onSend: () -> Unit) {
    OutlinedTextField(
        value = note,
        onValueChange = onNote,
        label = { Text("What is happening? (required for ${option.label})") },
        modifier = Modifier.fillMaxWidth().testTag("status-note"),
    )
    Button(onClick = onSend, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("status-send-note")) {
        Text("Declare ${option.label}")
    }
}

@Composable
private fun CurrentStatusCard(current: DisplayedStatus?) {
    Card(modifier = Modifier.fillMaxWidth().testTag("current-status")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (current == null) {
                Text("No status declared yet.")
                return@Column
            }
            Text(current.label, style = MaterialTheme.typography.headlineSmall)
            // Section 1: every fact says what kind it is and how fresh.
            Text("${current.sourceLabel} · declared ${time(current.occurredAt)}")
            Text(
                when (val f = current.freshness) {
                    Freshness.NotYetSent -> "Not yet sent — will send when connected"
                    is Freshness.Confirmed -> "Confirmed by the server ${time(f.confirmedAt)}"
                },
                modifier = Modifier.testTag("current-status-freshness"),
            )
        }
    }
}

@Composable
private fun AttentionCard(entry: OutboxEntry, label: String) {
    Card(modifier = Modifier.fillMaxWidth().testTag("attention-${entry.localId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (entry.state == PendingState.REJECTED) "Not accepted: $label" else "Waiting to send: $label")
            entry.correctiveAction?.let { Text(it) }
        }
    }
}

private val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun time(instant: Instant): String = formatter.format(instant)

private fun List<StatusOption>.labelOf(value: String) = firstOrNull { it.value == value }?.label ?: value
