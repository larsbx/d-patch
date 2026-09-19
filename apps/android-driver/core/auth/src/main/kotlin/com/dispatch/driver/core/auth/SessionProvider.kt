package com.dispatch.driver.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dispatch.driver.core.model.RoleContext
import com.dispatch.driver.core.model.UuidV7

interface SessionProvider {
    suspend fun bearerToken(): String?
    fun activeRole(): RoleContext?
    fun deviceId(): String
}

data class AuthenticatedSession(
    val accessToken: String,
    val roleAssignmentId: String,
    val capabilities: Set<String>,
    val expiresAtEpochMillis: Long? = null,
)

@Suppress("DEPRECATION")
class EncryptedSessionProvider(context: Context) : SessionProvider {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val deviceIdLock = Any()

    override suspend fun bearerToken(): String? =
        prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }

    override fun activeRole(): RoleContext? {
        val roleAssignmentId =
            prefs.getString(KEY_ROLE_ASSIGNMENT_ID, null)?.takeIf { it.isNotBlank() }
                ?: return null
        val capabilities = prefs.getStringSet(KEY_CAPABILITIES, emptySet())
            ?.toSet()
            .orEmpty()
        val expiresAt = if (prefs.contains(KEY_ROLE_EXPIRES_AT)) {
            prefs.getLong(KEY_ROLE_EXPIRES_AT, 0L)
        } else {
            null
        }

        return RoleContext(roleAssignmentId, capabilities, expiresAt)
    }

    override fun deviceId(): String = synchronized(deviceIdLock) {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            ?: UuidV7.generate().toString().also { generated ->
                prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            }
    }

    fun replaceAuthenticatedSession(session: AuthenticatedSession) {
        require(session.accessToken.isNotBlank()) { "Access token must not be blank." }
        require(session.roleAssignmentId.isNotBlank()) { "Role assignment ID must not be blank." }

        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_ROLE_ASSIGNMENT_ID, session.roleAssignmentId)
            .putStringSet(KEY_CAPABILITIES, session.capabilities.toSet())

        if (session.expiresAtEpochMillis == null) {
            editor.remove(KEY_ROLE_EXPIRES_AT)
        } else {
            editor.putLong(KEY_ROLE_EXPIRES_AT, session.expiresAtEpochMillis)
        }

        editor.apply()
    }

    fun clearAuthenticatedSession() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_ROLE_ASSIGNMENT_ID)
            .remove(KEY_CAPABILITIES)
            .remove(KEY_ROLE_EXPIRES_AT)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "dispatch-authenticated-session"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ROLE_ASSIGNMENT_ID = "role_assignment_id"
        const val KEY_CAPABILITIES = "capabilities"
        const val KEY_ROLE_EXPIRES_AT = "role_expires_at"
        const val KEY_DEVICE_ID = "device_id"
    }
}
