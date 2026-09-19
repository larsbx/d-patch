@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.dispatch.driver

import android.content.Context
import androidx.room.Room
import androidx.work.*
import com.dispatch.driver.core.auth.AuthenticatedSession
import com.dispatch.driver.core.auth.EncryptedSessionProvider
import com.dispatch.driver.core.auth.SessionProvider
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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType

class AndroidStatusRepository(
    private val context: Context,
    private val outbox: StatusOutbox,
    private val sessions: SessionProvider,
    private val json: Json,
) : StatusRepository {
    override fun events(): Flow<List<LocalStatusEvent>> = outbox.observeEvents().map { rows ->
        rows.map { row ->
            LocalStatusEvent(
                row.localId,
                row.eventId,
                row.roleAssignmentId,
                ParticipantStatus.valueOf(row.status),
                row.note,
                row.occurredAt,
                row.deviceSequence,
                DeliveryState.valueOf(row.state),
                row.rejectionCode,
                row.correctiveAction,
            )
        }
    }

    override suspend fun submit(contextRole: RoleContext, draft: StatusDraft): Result<Unit> = runCatching {
        require(contextRole.canDeclareStatus(System.currentTimeMillis())) {
            "This role assignment cannot declare a status."
        }
        draft.validationError()?.let { throw IllegalArgumentException(it) }

        val localId = UUID.randomUUID().toString()
        val eventId = UuidV7.generate().toString()
        val occurredAt = Instant.now().toString()
        outbox.enqueue(
            sessions.deviceId(),
            localId,
            eventId,
            contextRole.roleAssignmentId,
            draft.status.name,
            draft.note,
            draft.assignmentId,
            occurredAt,
            { sequence ->
                json.encodeToString(
                    StatusEventRequest(
                        eventId,
                        draft.assignmentId,
                        draft.status.name,
                        occurredAt,
                        draft.note,
                        sessions.deviceId(),
                        sequence,
                        draft.supersedesEventId,
                    ),
                )
            },
            System.currentTimeMillis(),
        )
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

        val sessionStore = EncryptedSessionProvider(context.applicationContext)
        sessions = sessionStore
        bootstrapDebugSession(sessionStore)

        api = Retrofit.Builder()
            .baseUrl(configuredBaseUrl())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(StatusApi::class.java)
    }

    private fun configuredBaseUrl(): HttpUrl {
        val raw = BuildConfig.API_BASE_URL.trim()
        require(raw.isNotEmpty()) {
            "Configure dispatchApiBaseUrl or DISPATCH_API_BASE_URL before starting the app."
        }
        val normalized = if (raw.endsWith("/")) raw else "$raw/"
        val parsed = normalized.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Configured API base URL is not a valid HTTP(S) URL.")
        require(BuildConfig.DEBUG || parsed.isHttps) {
            "Release builds require an HTTPS API base URL."
        }
        return parsed
    }

    private fun bootstrapDebugSession(sessionStore: EncryptedSessionProvider) {
        if (!BuildConfig.DEBUG) return

        val token = BuildConfig.DEV_BEARER_TOKEN.trim()
        val roleAssignmentId = BuildConfig.DEV_ROLE_ASSIGNMENT_ID.trim()
        if (token.isEmpty() && roleAssignmentId.isEmpty()) return

        require(token.isNotEmpty() && roleAssignmentId.isNotEmpty()) {
            "Debug session bootstrap requires both bearer token and role-assignment ID."
        }

        val capabilities = BuildConfig.DEV_CAPABILITIES
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        // Development-only handoff for exercising the vertical slice before the OIDC UI lands.
        // These values only drive local presentation; the server still revalidates the token,
        // captured role assignment, validity interval, scope, and capability on every mutation.
        sessionStore.replaceAuthenticatedSession(
            AuthenticatedSession(
                accessToken = token,
                roleAssignmentId = roleAssignmentId,
                capabilities = capabilities,
            ),
        )
    }
}

internal enum class SyncRunResult { SUCCESS, RETRY }

internal class StatusSyncRunner(
    private val dao: StatusOutboxDao,
    private val sessions: SessionProvider,
    private val api: StatusApi,
    private val json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): SyncRunResult {
        // WorkManager can stop a CoroutineWorker at any suspension point. A prior run may
        // therefore have committed SENDING without reaching its terminal update.
        dao.reclaimInterruptedClaims()

        val token = sessions.bearerToken() ?: return SyncRunResult.RETRY
        var shouldRetry = false

        while (true) {
            val page = dao.ready(nowMillis())
            if (page.isEmpty()) break

            for (item in page) {
                if (process(item, token)) shouldRetry = true
            }
        }

        return if (shouldRetry) SyncRunResult.RETRY else SyncRunResult.SUCCESS
    }

    private suspend fun process(item: PendingEventEntity, token: String): Boolean {
        if (dao.claim(item.localId) != 1) return false

        val request = runCatching {
            json.decodeFromString(StatusEventRequest.serializer(), item.canonicalJson)
        }.getOrNull()
        if (request == null) {
            dao.deletePending(item.localId)
            dao.rejected(
                item.localId,
                "LOCAL_PAYLOAD_INVALID",
                "Discard this local item and submit the status again.",
            )
            return false
        }

        // The queued row's captured role assignment is authoritative for this upload context.
        // A later role switch changes neither this header nor the event's local audit context.
        val roleId = dao.roleAssignmentId(item.localId)
        if (roleId == null) {
            dao.deletePending(item.localId)
            dao.rejected(
                item.localId,
                "LOCAL_ROLE_CONTEXT_MISSING",
                "Discard this local item and submit under an active role assignment.",
            )
            return false
        }

        val outcome = try {
            val response = api.create("Bearer $token", roleId, item.localId, request)
            val problem = response.errorBody()?.string()?.let {
                runCatching { json.decodeFromString(Problem.serializer(), it) }.getOrNull()
            }
            StatusResponsePolicy.classify(response.code(), problem)
        } catch (_: java.io.IOException) {
            UploadOutcome.Retry(30_000)
        }

        return when (outcome) {
            UploadOutcome.Accepted -> {
                dao.synced(item.localId)
                dao.deletePending(item.localId)
                false
            }
            is UploadOutcome.Retry -> {
                dao.retry(item.localId, nowMillis() + outcome.delayMillis)
                true
            }
            is UploadOutcome.Rejected -> {
                dao.deletePending(item.localId)
                dao.rejected(item.localId, outcome.code, outcome.correctiveAction)
                false
            }
        }
    }
}

class StatusSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (
            StatusSyncRunner(
                RuntimeGraph.database.statusOutbox(),
                RuntimeGraph.sessions,
                RuntimeGraph.api,
                RuntimeGraph.json,
            ).run()
        ) {
            SyncRunResult.SUCCESS -> Result.success()
            SyncRunResult.RETRY -> Result.retry()
        }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<StatusSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "status-outbox-sync",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
