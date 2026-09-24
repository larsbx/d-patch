package com.dispatch.driver.feature.status

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dispatch.driver.core.database.DispatchDatabase
import com.dispatch.driver.core.database.StatusOutbox
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.CapabilityState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Section 25.2's submission path through the real outbox: validate, queue,
 * ask for a sync — and nothing queued when validation fails.
 */
@RunWith(RobolectricTestRunner::class)
class StatusViewModelTest {

    private val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DispatchDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val outbox = StatusOutbox(database)
    private var syncRequests = 0

    private val capabilities = object : CapabilityProvider {
        override val state = MutableStateFlow<CapabilityState>(CapabilityState.Ready(document(), stale = false))
        var refreshes = 0
        override suspend fun refresh() { refreshes++ }
    }

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    private fun viewModel() = StatusViewModel(capabilities, outbox) { syncRequests++ }

    @Test
    fun `opening the screen refreshes capabilities and gives the queue a chance to send`() {
        viewModel()

        assertEquals(1, capabilities.refreshes)
        assertEquals(1, syncRequests)
    }

    @Test
    fun `a valid declaration is queued under the document's assignment and a sync requested`() = runTest {
        val vm = viewModel()

        assertTrue(vm.declare("DELAYED", "  Road closed  "))

        val queued = outbox.view().first().events.single()
        assertEquals("DELAYED", queued.status)
        assertEquals("Road closed", queued.note)
        assertEquals("ra-1", database.outbox().observeEvents().first().single().roleAssignmentId)
        assertEquals(2, syncRequests)
        assertNull(vm.error.value)
    }

    @Test
    fun `an invalid declaration is refused with a reason and nothing is queued`() = runTest {
        val vm = viewModel()

        assertFalse(vm.declare("DELAYED", ""))

        assertEquals("Delayed needs a note saying what is happening.", vm.error.value)
        assertTrue(outbox.view().first().events.isEmpty())
        assertEquals(1, syncRequests)
    }

    @Test
    fun `nothing can be declared without a verified document`() = runTest {
        capabilities.state.value = CapabilityState.SignedOut
        val vm = viewModel()

        assertFalse(vm.declare("AT_PICKUP", null))
        assertTrue(outbox.view().first().events.isEmpty())
    }
}
