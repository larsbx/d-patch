package com.dispatch.driver.feature.status

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dispatch.driver.core.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

interface StatusRepository {
    fun events(): Flow<List<LocalStatusEvent>>
    suspend fun submit(context: RoleContext, draft: StatusDraft): Result<Unit>
}

data class StatusUiState(
    val roleContext: RoleContext? = null,
    val events: List<LocalStatusEvent> = emptyList(),
    val selected: ParticipantStatus = ParticipantStatus.AVAILABLE,
    val note: String = "",
    val message: String? = null,
) {
    val enabled: Boolean get() = roleContext?.canDeclareStatus(System.currentTimeMillis()) == true
}

class StatusPresenter(
    private val repository: StatusRepository,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = mutable.asStateFlow()

    init {
        scope.launch { repository.events().collect { items -> mutable.update { it.copy(events = items) } } }
    }

    fun selectRole(context: RoleContext?) = mutable.update { it.copy(roleContext = context) }
    fun selectStatus(value: ParticipantStatus) = mutable.update { it.copy(selected = value) }
    fun editNote(value: String) = mutable.update { it.copy(note = value.take(1_000)) }

    fun submit() {
        val snapshot = mutable.value
        val context = snapshot.roleContext
        if (context == null || !context.canDeclareStatus(System.currentTimeMillis())) {
            mutable.update { it.copy(message = "This role assignment cannot declare a status.") }
            return
        }
        val draft = StatusDraft(snapshot.selected, snapshot.note.ifBlank { null }, null)
        draft.validationError()?.let { error ->
            mutable.update { it.copy(message = error) }; return
        }
        scope.launch {
            repository.submit(context, draft)
                .onSuccess { mutable.update { it.copy(note = "", message = "Saved on this device. Sync will continue automatically.") } }
                .onFailure { error -> mutable.update { it.copy(message = error.message ?: "Could not save status.") } }
        }
    }
}

@Composable
fun StatusScreen(presenter: StatusPresenter, modifier: Modifier = Modifier) {
    val state by presenter.state.collectAsState()
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("My status", style = MaterialTheme.typography.headlineMedium)
        if (!state.enabled) {
            Text("Status declaration is unavailable for the selected role assignment.",
                color = MaterialTheme.colorScheme.error)
        }
        var expanded by remember { mutableStateOf(false) }
        Button(onClick = { expanded = true }, enabled = state.enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(state.selected.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ParticipantStatus.entries.forEach { status ->
                DropdownMenuItem(text = { Text(status.label) }, onClick = {
                    presenter.selectStatus(status); expanded = false
                })
            }
        }
        OutlinedTextField(value = state.note, onValueChange = presenter::editNote,
            label = { Text(if (state.selected.requiresNote) "Note (required)" else "Note (optional)") },
            enabled = state.enabled, modifier = Modifier.fillMaxWidth())
        Button(onClick = presenter::submit, enabled = state.enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Save status") }
        state.message?.let { Text(it) }
        Text("Recent declarations", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.events, key = { it.localId }) { event ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(event.status.label)
                        Text(when (event.state) {
                            DeliveryState.PENDING, DeliveryState.SENDING -> "Pending sync"
                            DeliveryState.SYNCED -> "Synced"
                            DeliveryState.REJECTED -> "Needs correction"
                        })
                        event.correctiveAction?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
