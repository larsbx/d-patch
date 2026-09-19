package com.dispatch.driver.feature.status

import com.dispatch.driver.core.auth.AuthoritySnapshot
import com.dispatch.driver.core.auth.AuthorityStore
import com.dispatch.driver.core.model.DriverStatus
import com.dispatch.driver.core.model.LocalStatus
import com.dispatch.driver.core.model.STATUS_DECLARE_SELF_CAPABILITY
import com.dispatch.driver.core.model.StatusRejection
import com.dispatch.driver.core.model.StatusRepository
import com.dispatch.driver.core.model.StatusSubmissionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun capabilityEnablesDeclarationWithoutAnyRoleKeyCheck() = runTest {
        val authority =
            FakeAuthorityStore(
                AuthoritySnapshot(
                    accessToken = "token",
                    roleAssignmentId = "arbitrary-role-assignment-id",
                    deviceId = "device",
                    capabilities = setOf(STATUS_DECLARE_SELF_CAPABILITY),
                ),
            )
        val viewModel = StatusViewModel(FakeRepository(), authority)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        assertTrue(viewModel.uiState.value.canDeclare)
    }

    @Test
    fun missingCapabilityFailsClosed() = runTest {
        val authority =
            FakeAuthorityStore(
                AuthoritySnapshot(
                    accessToken = "token",
                    roleAssignmentId = "role",
                    deviceId = "device",
                    capabilities = emptySet(),
                ),
            )
        val viewModel = StatusViewModel(FakeRepository(), authority)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        assertFalse(viewModel.uiState.value.canDeclare)
    }

    @Test
    fun delayedStatusRequiresNoteBeforeRepositoryCall() = runTest {
        val repository = FakeRepository()
        val authority =
            FakeAuthorityStore(
                AuthoritySnapshot(
                    accessToken = "token",
                    roleAssignmentId = "role",
                    deviceId = "device",
                    capabilities = setOf(STATUS_DECLARE_SELF_CAPABILITY),
                ),
            )
        val viewModel = StatusViewModel(repository, authority)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }

        viewModel.select(DriverStatus.DELAYED)
        viewModel.submit()

        assertTrue(repository.declarations.isEmpty())
        assertTrue(viewModel.uiState.value.message?.contains("requires a note") == true)
    }

    private class FakeRepository : StatusRepository {
        override val currentStatus = MutableStateFlow<LocalStatus?>(null)
        override val latestRejection = MutableStateFlow<StatusRejection?>(null)
        val declarations = mutableListOf<Pair<DriverStatus, String?>>()

        override suspend fun declare(
            status: DriverStatus,
            note: String?,
        ): StatusSubmissionResult {
            declarations += status to note
            return StatusSubmissionResult.Queued("id")
        }

        override suspend fun retry(localId: String): Boolean = true
    }

    private class FakeAuthorityStore(
        initial: AuthoritySnapshot?,
    ) : AuthorityStore {
        private val mutable = MutableStateFlow(initial)
        override val authority: StateFlow<AuthoritySnapshot?> = mutable

        override fun current(): AuthoritySnapshot? = mutable.value

        override fun save(snapshot: AuthoritySnapshot) {
            mutable.value = snapshot
        }

        override fun clear() {
            mutable.value = null
        }
    }
}
