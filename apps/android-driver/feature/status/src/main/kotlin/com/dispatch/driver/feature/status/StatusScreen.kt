package com.dispatch.driver.feature.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dispatch.driver.core.model.DriverStatus
import com.dispatch.driver.core.model.LocalSyncState

@Composable
fun StatusScreen(
    viewModel: StatusViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(modifier = modifier) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Driver-reported status", style = MaterialTheme.typography.headlineSmall)
            }
            item { CurrentStatusCard(state) }

            state.authorityMessage?.let { explanation ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(explanation, modifier = Modifier.padding(16.dp))
                    }
                }
            }

            state.latestRejection?.let { rejection ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "Server rejected a queued declaration",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(rejection.correctiveAction)
                            Text(
                                "Code: ${rejection.code}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (rejection.code == "UNAUTHENTICATED") {
                                OutlinedButton(
                                    onClick = viewModel::retryLatestRejection,
                                    enabled = state.canDeclare,
                                ) {
                                    Text("Retry after sign-in")
                                }
                            }
                        }
                    }
                }
            }

            item { Text("Choose status", style = MaterialTheme.typography.titleMedium) }
            items(DriverStatus.entries, key = { it.name }) { status ->
                OutlinedButton(
                    onClick = { viewModel.select(status) },
                    enabled = state.canDeclare,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(status.label)
                        Text(status.meaning, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            state.selected?.let { selected ->
                item {
                    Text(
                        "Selected: ${selected.label}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    OutlinedTextField(
                        value = state.note,
                        onValueChange = viewModel::updateNote,
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(if (selected.requiresNote) "Note (required)" else "Note (optional)")
                        },
                        supportingText = { Text("${state.note.length}/1000") },
                    )
                }
                item {
                    Button(
                        onClick = viewModel::submit,
                        enabled = state.canDeclare,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Queue status")
                    }
                }
            }

            state.message?.let { message -> item { Text(message) } }
        }
    }
}

@Composable
private fun CurrentStatusCard(state: StatusUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val current = state.current
            if (current == null) {
                Text("No status declared on this device yet.")
                return@Column
            }

            Text(current.status.label, style = MaterialTheme.typography.titleLarge)
            Text("Source: Participant declaration")
            Text("Occurred: ${current.occurredAt}")
            Text(
                when (current.syncState) {
                    LocalSyncState.PENDING -> "Sync: queued offline"
                    LocalSyncState.RETRY -> "Sync: waiting to retry"
                    LocalSyncState.SENDING -> "Sync: sending"
                    LocalSyncState.SENT -> "Sync: confirmed by server"
                    LocalSyncState.REJECTED -> "Sync: rejected by server"
                },
            )
        }
    }
}
