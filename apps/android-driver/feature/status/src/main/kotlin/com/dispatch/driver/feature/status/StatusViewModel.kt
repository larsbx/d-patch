package com.dispatch.driver.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispatch.driver.core.auth.AuthoritySnapshot
import com.dispatch.driver.core.auth.AuthorityStore
import com.dispatch.driver.core.model.DriverStatus
import com.dispatch.driver.core.model.LocalStatus
import com.dispatch.driver.core.model.STATUS_DECLARE_SELF_CAPABILITY
import com.dispatch.driver.core.model.StatusRejection
import com.dispatch.driver.core.model.StatusRepository
import com.dispatch.driver.core.model.StatusSubmissionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatusUiState(
    val current: LocalStatus? = null,
    val latestRejection: StatusRejection? = null,
    val canDeclare: Boolean = false,
    val authorityMessage: String? = "Sign in to declare status.",
    val selected: DriverStatus? = null,
    val note: String = "",
    val message: String? = null,
)

private data class StatusFormState(
    val selected: DriverStatus?,
    val note: String,
    val message: String?,
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    private val repository: StatusRepository,
    private val authorityStore: AuthorityStore,
) : ViewModel() {
    private val selected = MutableStateFlow<DriverStatus?>(null)
    private val note = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)

    private val form =
        combine(selected, note, message) { selectedStatus, noteText, formMessage ->
            StatusFormState(selectedStatus, noteText, formMessage)
        }

    val uiState: StateFlow<StatusUiState> =
        combine(
            repository.currentStatus,
            repository.latestRejection,
            authorityStore.authority,
            form,
        ) { current, rejection, authority, formState ->
            val authorityMessage = authorityMessage(authority)
            StatusUiState(
                current = current,
                latestRejection = rejection,
                canDeclare = authorityMessage == null,
                authorityMessage = authorityMessage,
                selected = formState.selected,
                note = formState.note,
                message = formState.message,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatusUiState(),
        )

    fun select(status: DriverStatus) {
        selected.value = status
        message.value = null
    }

    fun updateNote(value: String) {
        note.value = value.take(1_000)
        message.value = null
    }

    fun submit() {
        val requested = selected.value
        if (requested == null) {
            message.value = "Choose a status first."
            return
        }
        if (requested.requiresNote && note.value.isBlank()) {
            message.value = "${requested.label} requires a note."
            return
        }

        viewModelScope.launch {
            when (val result = repository.declare(requested, note.value)) {
                is StatusSubmissionResult.Queued -> {
                    selected.value = null
                    note.value = ""
                    message.value = "Status queued for sync."
                }
                is StatusSubmissionResult.Blocked -> message.value = result.reason
            }
        }
    }

    fun retryLatestRejection() {
        val rejection = uiState.value.latestRejection ?: return
        if (rejection.code != "UNAUTHENTICATED") return

        viewModelScope.launch {
            if (!repository.retry(rejection.localId)) {
                message.value = "This rejected declaration cannot be retried unchanged."
            }
        }
    }

    private fun authorityMessage(authority: AuthoritySnapshot?): String? =
        when {
            authority == null -> "Sign in to declare status."
            authority.roleAssignmentId == null -> "Select an active role assignment."
            authority.deviceId == null -> "Register this device before declaring a status."
            STATUS_DECLARE_SELF_CAPABILITY !in authority.capabilities ->
                "The selected assignment does not grant status declaration."
            else -> null
        }
}
