@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dispatch.driver

import android.content.Context
import androidx.room.Room
import androidx.work.*
import com.dispatch.driver.core.database.*
import com.dispatch.driver.core.model.*
import com.dispatch.driver.core.network.*
import com.dispatch.driver.feature.status.StatusRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType

interface SessionProvider {
    suspend fun bearerToken(): String?
    fun activeRole(): RoleContext?
    fun deviceId(): String
}
class UnconfiguredSessionProvider : SessionProvider {
    override suspend fun bearerToken(): String? = null
    override fun activeRole(): RoleContext? = null
    override fun deviceId(): String = "unregistered"
}
class AndroidStatusRepository(private val context: Context, private val outbox: StatusOutbox,
    private val sessions: SessionProvider, private val json: Json) : StatusRepository {
    override fun events(): Flow<List<LocalStatusEvent>> = outbox.observeEvents().map { rows ->
        rows.map { row -> LocalStatusEvent(row.localId, row.eventId, row.roleAssignmentId,
            ParticipantStatus.valueOf(row.status), row.note, row.occurredAt, row.deviceSequence,
            DeliveryState.valueOf(row.state), row.rejectionCode, row.correctiveAction) }
    }
    override suspend fun submit(contextRole: RoleContext, draft: StatusDraft): Result<Unit> = runCatching {
        require(contextRole.canDeclareStatus(System.currentTimeMillis())) {
            "This role assignment cannot declare a status."
        }
        draft.validationError()?.let { throw IllegalArgumentException(it) }
        val localId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val occurredAt = Instant.now().toString()
        outbox.enqueue(sessions.deviceId(), localId, eventId, contextRole.roleAssignmentId,
            draft.status.name, draft.note, draft.assignmentId, occurredAt, { sequence ->
                json.encodeToString(StatusEventRequest(eventId, draft.assignmentId, draft.status.name,
                    occurredAt, draft.note, sessions.deviceId(), sequence, draft.supersedesEventId))
            }, System.currentTimeMillis())
        StatusSyncWorker.enqueue(context)
    }
}
object RuntimeGraph {
    lateinit var database: DispatchDatabase
    lateinit var api: StatusApi
    lateinit var sessions: SessionProvider
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    fun initialize(context: Context) {
        database = Room.databaseBuilder(context, DispatchDatabase::class.java, "dispatch.db").build()
        sessions = UnconfiguredSessionProvider()
        api = Retrofit.Builder().baseUrl("https://localhost/")
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build().create(StatusApi::class.java)
    }
}
class StatusSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = RuntimeGraph.database.statusOutbox()
        val token = RuntimeGraph.sessions.bearerToken() ?: return Result.retry()
        var shouldRetry = false
        dao.ready(System.currentTimeMillis()).forEach { item ->
            if (dao.claim(item.localId) != 1) return@forEach
            val request = runCatching {
                RuntimeGraph.json.decodeFromString(StatusEventRequest.serializer(), item.canonicalJson)
            }.getOrElse {
                dao.deletePending(item.localId)
                dao.rejected(item.localId, "LOCAL_PAYLOAD_INVALID",
                    "Discard this local item and submit the status again.")
                return@forEach
            }
            val roleId = dao.roleAssignmentId(item.localId)
            if (roleId == null) {
                dao.deletePending(item.localId)
                dao.rejected(item.localId, "LOCAL_ROLE_CONTEXT_MISSING",
                    "Discard this local item and submit under an active role assignment.")
                return@forEach
            }
            val outcome = try {
                val response = RuntimeGraph.api.create("Bearer $token", roleId, item.localId, request)
                val problem = response.errorBody()?.string()?.let {
                    runCatching { RuntimeGraph.json.decodeFromString(Problem.serializer(), it) }.getOrNull()
                }
                StatusResponsePolicy.classify(response.code(), problem)
            } catch (_: java.io.IOException) { UploadOutcome.Retry(30_000) }
            when (outcome) {
                UploadOutcome.Accepted -> { dao.synced(item.localId); dao.deletePending(item.localId) }
                is UploadOutcome.Retry -> {
                    dao.retry(item.localId, System.currentTimeMillis() + outcome.delayMillis); shouldRetry = true
                }
                is UploadOutcome.Rejected -> {
                    dao.deletePending(item.localId)
                    dao.rejected(item.localId, outcome.code, outcome.correctiveAction)
                }
            }
        }
        return if (shouldRetry) Result.retry() else Result.success()
    }
    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<StatusSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "status-outbox-sync", ExistingWorkPolicy.KEEP, request)
        }
    }
}
