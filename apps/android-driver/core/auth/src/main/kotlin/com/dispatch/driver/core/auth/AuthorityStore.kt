package com.dispatch.driver.core.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AuthoritySnapshot(
    val accessToken: String,
    val roleAssignmentId: String?,
    val deviceId: String?,
    val capabilities: Set<String>,
)

interface AuthorityStore {
    val authority: StateFlow<AuthoritySnapshot?>

    fun current(): AuthoritySnapshot?

    fun save(snapshot: AuthoritySnapshot)

    fun clear()
}

class EncryptedAuthorityStore(context: Context) : AuthorityStore {
    private val masterKey =
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

    private val preferences =
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private val mutableAuthority = MutableStateFlow(load())

    override val authority: StateFlow<AuthoritySnapshot?> = mutableAuthority.asStateFlow()

    override fun current(): AuthoritySnapshot? = mutableAuthority.value

    override fun save(snapshot: AuthoritySnapshot) {
        require(snapshot.accessToken.isNotBlank()) { "access token must not be blank" }

        preferences
            .edit()
            .putString(KEY_ACCESS_TOKEN, snapshot.accessToken)
            .putString(KEY_ROLE_ASSIGNMENT_ID, snapshot.roleAssignmentId)
            .putString(KEY_DEVICE_ID, snapshot.deviceId)
            .putStringSet(KEY_CAPABILITIES, snapshot.capabilities)
            .apply()

        mutableAuthority.value = snapshot
    }

    override fun clear() {
        preferences.edit().clear().apply()
        mutableAuthority.value = null
    }

    private fun load(): AuthoritySnapshot? {
        val token = preferences.getString(KEY_ACCESS_TOKEN, null)?.takeIf(String::isNotBlank)
            ?: return null

        return AuthoritySnapshot(
            accessToken = token,
            roleAssignmentId = preferences.getString(KEY_ROLE_ASSIGNMENT_ID, null),
            deviceId = preferences.getString(KEY_DEVICE_ID, null),
            capabilities = preferences.getStringSet(KEY_CAPABILITIES, emptySet()).orEmpty().toSet(),
        )
    }

    private companion object {
        const val FILE_NAME = "dispatch_authority"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ROLE_ASSIGNMENT_ID = "role_assignment_id"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_CAPABILITIES = "capabilities"
    }
}
