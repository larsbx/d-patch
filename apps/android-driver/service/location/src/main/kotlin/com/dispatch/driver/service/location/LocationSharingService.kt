package com.dispatch.driver.service.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dispatch.driver.core.model.location.Sharing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * What the service needs from the app, so this module needs no DI framework.
 * The app installs one in `Application.onCreate`, which runs before any
 * service of the process starts; an instrumented test installs the same.
 */
interface SharingHost {
    val sessions: SharingSessions
    val sharingControl: SharingControl

    /** Emits when the app comes to the foreground: Section 25.3 uploads then. */
    val foreground: Flow<Unit>

    /** A session bound to [record]'s consent: its outbox, the platform provider, the demand stream. */
    fun sharingSession(record: SharingRecord): LocationSharing

    companion object {
        @Volatile private var installed: SharingHost? = null

        fun install(host: SharingHost) {
            installed = host
        }

        val current: SharingHost get() = checkNotNull(installed) { "No SharingHost installed: Application.onCreate installs it" }
    }
}

/**
 * The process's live sharing session, if any. Holding its revocation signal
 * here is what makes stopping synchronous from anywhere in the app: the
 * session releases the provider as soon as [stop] completes it, without
 * waiting for an intent to reach the service.
 */
class SharingSessions {
    private val current = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val _activity = MutableStateFlow(false)

    /** Whether a session is sharing now, for whatever shows it. */
    val activity: StateFlow<Boolean> = _activity.asStateFlow()

    val active: Boolean get() = current.get()?.isActive == true

    /** A new session's signal; any previous session is stopped. */
    fun open(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { current.getAndSet(it)?.complete(Unit) }.also { publish() }

    fun stop() {
        current.get()?.complete(Unit)
        publish()
    }

    fun close(signal: CompletableDeferred<Unit>) {
        current.compareAndSet(signal, null)
        publish()
    }

    private fun publish() {
        _activity.value = active
    }
}

/** [SharingRunner] as the foreground service: started by intent, stopped at once through [sessions]. */
class ServiceSharingRunner(private val context: Context, private val sessions: SharingSessions) : SharingRunner {
    override fun start(sharing: Sharing, roleAssignmentId: String) {
        val record = SharingRecord(sharing.assignmentId, sharing.consentGrantId, roleAssignmentId)
        ContextCompat.startForegroundService(context, LocationSharingService.start(context, record))
    }

    override fun stop() = sessions.stop()
}

/**
 * Section 25.3's foreground service. It runs one [LocationSharing] session for
 * one consent, shows that it does for as long as it does, and offers
 * `Stop sharing`, which goes through [SharingControl.stop]: collection stops
 * here first, then the server is told.
 *
 * Not sticky: a killed process is not restarted into sharing. The app resumes
 * only through [SharingControl.resume], after the server confirms the consent.
 */
class LocationSharingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Job? = null
    private val host get() = SharingHost.current

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> recordOf(intent)?.let(::share) ?: stopSelf()
            ACTION_STOP -> scope.launch { host.sharingControl.stop() }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun share(record: SharingRecord) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (session?.isActive == true) return
        val revoked = host.sessions.open()
        session = scope.launch {
            val end = try {
                host.sharingSession(record).run(revoked, host.foreground)
            } finally {
                host.sessions.close(revoked)
            }
            if (end == SharingEnd.ConsentEnded) host.sharingControl.ended()
            ServiceCompat.stopForeground(this@LocationSharingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // Cancelling ends the session, whose `finally` clears it from `sessions`.
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Location sharing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, LocationSharingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing your location")
            .setContentText("For this trip, until you stop or it ends.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop sharing", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.dispatch.driver.location.START"
        const val ACTION_STOP = "com.dispatch.driver.location.STOP"
        private const val CHANNEL = "location-sharing"
        private const val NOTIFICATION_ID = 25_3

        fun start(context: Context, record: SharingRecord): Intent =
            Intent(context, LocationSharingService::class.java).setAction(ACTION_START)
                .putExtra("assignment_id", record.assignmentId)
                .putExtra("consent_grant_id", record.consentGrantId)
                .putExtra("role_assignment_id", record.roleAssignmentId)

        /** The consent a start intent carries, or null when it carries none. */
        fun recordOf(intent: Intent): SharingRecord? {
            val assignment = intent.getStringExtra("assignment_id") ?: return null
            val grant = intent.getStringExtra("consent_grant_id") ?: return null
            val role = intent.getStringExtra("role_assignment_id") ?: return null
            return SharingRecord(assignment, grant, role)
        }
    }
}
