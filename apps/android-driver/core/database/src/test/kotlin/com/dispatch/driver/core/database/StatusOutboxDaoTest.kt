package com.dispatch.driver.core.database

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class StatusOutboxDaoTest {
    private lateinit var database: DispatchDatabase
    private lateinit var dao: StatusOutboxDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                DispatchDatabase::class.java,
            ).allowMainThreadQueries().build()
        dao = database.statusOutboxDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sentRowsStillReserveTheirDeviceSequence() = runTest {
        dao.insertPending(event(localId = "one", sequence = 1))
        dao.markSent("one", at = 20)

        assertEquals(2L, dao.nextDeviceSequence("device-a"))
    }

    @Test
    fun rejectedRowPreservesExactServerCorrectiveDetail() = runTest {
        dao.insertPending(event(localId = "one", sequence = 1))
        val detail = "Select an active role assignment and submit again."
        dao.markRejected("one", at = 20, code = "ROLE_ASSIGNMENT_INVALID", detail = detail)

        val rejected = dao.observeLatestRejected().first()
        assertEquals("ROLE_ASSIGNMENT_INVALID", rejected?.lastErrorCode)
        assertEquals(detail, rejected?.lastErrorDetail)
    }

    private fun event(localId: String, sequence: Long) =
        PendingEventEntity(
            localId = localId,
            kind = "participant_status",
            canonicalJson = "{}",
            deviceId = "device-a",
            deviceSequence = sequence,
            roleAssignmentId = "role-a",
            idempotencyKey = "status:$localId",
            createdAt = 10,
            attemptCount = 0,
            nextAttemptAt = 10,
            state = PendingEventStates.PENDING,
        )
}
