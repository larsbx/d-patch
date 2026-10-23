package com.dispatch.driver.service.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Section 25.3: "Reboot receiver resumes only if the persisted consent is valid
 * and the active assignment still exists after server confirmation."
 *
 * The decision is [SharingControl.resume]'s, which starts nothing without that
 * confirmation. Android lets a boot broadcast start a location foreground
 * service; it allows a receiver about ten seconds, so a server that has not
 * answered within [CONFIRM_MS] counts as no answer, and the next launch of the
 * app asks again.
 */
class SharingBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Null when called directly rather than delivered as a broadcast.
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                withTimeoutOrNull(CONFIRM_MS) { SharingHost.current.sharingControl.resume() }
            } finally {
                pending?.finish()
            }
        }
    }

    private companion object {
        const val CONFIRM_MS = 8_000L
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
