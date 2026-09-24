package com.dispatch.driver.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispatch.driver.core.database.StatusOutbox
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import com.dispatch.driver.core.model.capability.SyncRequester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Section 25.2's status submission: validate, queue in one transaction, ask
 * for a sync. The screen updates from the outbox Flow, not from anything this
 * class holds, so what is shown is what is queued.
 */
@HiltViewModel
class StatusViewModel @Inject constructor(
    private val capabilities: CapabilityProvider,
    private val outbox: StatusOutbox,
    private val sync: SyncRequester,
) : ViewModel() {

    val state: StateFlow<StatusUiState> =
        combine(capabilities.state, outbox.view(), StatusPresenter::present)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatusUiState.Loading)

    private val _error = MutableStateFlow<String?>(null)

    /** Why the last declaration was not queued, if it was not. */
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch { capabilities.refresh() }
        // Opening the screen is Section 25.3's "app foreground" moment for the
        // outbox too: anything queued gets another chance to go.
        sync.requestSync()
    }

    /** Queues a declaration; false, with [error] set, when it cannot be. */
    fun declare(value: String, note: String?): Boolean {
        val ready = capabilities.state.value as? CapabilityState.Ready ?: return false
        _error.value = StatusPresenter.validate(ready.document, value, note)
        if (_error.value != null) return false

        viewModelScope.launch {
            outbox.declare(value, note?.trim(), ready.document.roleAssignmentId)
            sync.requestSync()
        }
        return true
    }

    fun refreshCapabilities() {
        viewModelScope.launch { capabilities.refresh() }
    }
}
