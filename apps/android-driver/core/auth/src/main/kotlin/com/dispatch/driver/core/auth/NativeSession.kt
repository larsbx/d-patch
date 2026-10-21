package com.dispatch.driver.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dispatch.driver.core.model.session.SessionProvider
import com.dispatch.driver.core.model.session.EnrolmentStatement
import com.dispatch.driver.core.model.status.DeviceSigner
import com.dispatch.driver.core.model.status.StatusDeclaration
import com.dispatch.driver.core.model.status.StatusSigningBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** BizKit.Identity session and installation keys, encrypted by an Android
 * Keystore-backed master key. Ed25519 uses the project's existing BC scheme
 * on API 29+, without claiming a hardware-backed Ed25519 key.
 */
class NativeSession(
    private val preferences: SharedPreferences,
    private val baseUrl: String,
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).build(),
) : SessionProvider, DeviceSigner {
    private val revision = MutableStateFlow(0L)
    override val changes = revision.map { Unit }
    override fun subject(): String? = preferences.getString("subject", null)
    override fun selectedRoleAssignmentId(): String? = preferences.getString("role", null)
    override fun ownerSubject(): String? = subject()
    override fun visibleRoleAssignmentId(): String? = selectedRoleAssignmentId()
    override fun authorizationScheme(): String = "BizKit"
    override suspend fun accessToken(): String? = synchronized(this) { liveToken() }
    override suspend fun accessTokenFor(owner: String?): String? = synchronized(this) {
        if (owner != null && owner != subject()) null else liveToken()
    }
    private fun liveToken(): String? = if (preferences.getLong("expires", 0) > System.currentTimeMillis())
        preferences.getString("token", null) else null

    suspend fun beginSignIn(): String {
        val verifier = random(); val state = random()
        check(preferences.edit().putString("pkce", verifier).putString("state", state).commit())
        val challenge = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return request("v1/mobile/authorize", JSONObject().put("code_challenge", challenge).put("state", state), false)
            .getString("authorization_url").also { url ->
                val expected = Uri.parse(baseUrl); val actual = Uri.parse(url)
                require(actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port)
            }
    }

    suspend fun completeSignIn(uri: Uri) {
        require(uri.scheme == "com.dispatch.driver" && uri.host == "signin" && uri.path.isNullOrEmpty())
        val expected = preferences.getString("state", null) ?: error("No sign-in in progress")
        require(uri.getQueryParameter("state") == expected) { "Sign-in state mismatch" }
        val verifier = preferences.getString("pkce", null) ?: error("No verifier")
        val response = request("v1/mobile/token", JSONObject().put("code", uri.getQueryParameter("code"))
            .put("code_verifier", verifier), false)
        require(response.getString("token_type") == "BizKit")
        synchronized(this) {
            check(preferences.edit().remove("pkce").remove("state").remove("role")
                .putString("token", response.getString("access_token"))
                .putString("subject", response.getString("subject"))
                .putString("principal", response.getString("principal"))
                .putLong("expires", response.getLong("expires_at")).commit())
            revision.value++
        }
    }

    fun chooseRole(id: String) {
        synchronized(this) {
            check(preferences.edit().putString("role", id).commit()); revision.value++
        }
    }
    private fun keySlot(): String = "key:${subject() ?: error("Sign in first")}"
    private fun key(): Ed25519PrivateKeyParameters {
        val slot = keySlot()
        val seed = synchronized(this) {
            preferences.getString(slot, null)?.let { decode(it) } ?: ByteArray(32).also {
                SecureRandom().nextBytes(it); check(preferences.edit().putString(slot, encode(it)).commit())
            }
        }
        return Ed25519PrivateKeyParameters(seed, 0)
    }
    private fun keyId(public: ByteArray) = "k-" + public.take(4).joinToString("") { "%02x".format(it) }
    private fun sign(bytes: ByteArray, key: Ed25519PrivateKeyParameters): ByteArray = Ed25519Signer().run {
        init(true, key); update(bytes, 0, bytes.size); generateSignature()
    }

    /** Request -> proof -> administrator code. Approval is never performed here. */
    suspend fun requestEnrolment(): String {
        check(selectedRoleAssignmentId() != null) { "Choose a role first" }
        val key = key(); val public = key.generatePublicKey().encoded
        val challenge = request("v1/me/device-enrolment", JSONObject().put("key_id", keyId(public))
            .put("public_key", encode(public)).put("label", "Android status device"))
        val statement = decode(challenge.getString("statement"))
        val principal = preferences.getString("principal", null) ?: error("Sign in again before requesting device access")
        check(EnrolmentStatement.isOwn(statement, keyId(public), public, principal)) {
            "The device challenge is not an enrolment for your key. Contact your administrator."
        }
        val result = request("v1/me/device-enrolment/${challenge.getString("id")}/proof",
            JSONObject().put("signature", encode(sign(statement, key))))
        return result.getString("code")
    }

    suspend fun registerDevice() {
        check(selectedRoleAssignmentId() != null) { "Choose a role first" }
        val key = key(); val public = key.generatePublicKey().encoded
        val installationSlot = "installation_id"
        val installation = preferences.getString(installationSlot, null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString(installationSlot, it).commit())
        }
        val response = request("v1/me/devices", JSONObject().put("installation_id", installation)
            .put("key_id", keyId(public)).put("public_key", encode(public)))
        check(preferences.edit().putString(keySlot() + ":device", response.getString("device_id")).commit())
    }

    fun deviceReady(): Boolean = selectedRoleAssignmentId()?.let { preferences.contains(keySlot() + ":device") } ?: false

    override fun sign(declaration: StatusDeclaration, roleAssignmentId: String): StatusDeclaration = synchronized(this) {
        require(roleAssignmentId == selectedRoleAssignmentId()) { "Role changed; declare again" }
        val device = preferences.getString(keySlot() + ":device", null) ?: error("Enrol this device first")
        val payload = declaration.copy(deviceId = device, subject = subject(), deviceSignature = null)
        payload.copy(deviceSignature = encode(sign(StatusSigningBytes.encode(payload, roleAssignmentId), key())))
    }

    suspend fun signOut() {
        try { request("v1/mobile/session", null, method = "DELETE") } finally {
            synchronized(this) {
                check(preferences.edit().remove("token").remove("expires").remove("subject").remove("principal").remove("role").commit())
                revision.value++
            }
        }
    }

    private suspend fun request(path: String, body: JSONObject?, authenticated: Boolean = true, method: String = "POST"): JSONObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(baseUrl.trimEnd('/') + "/" + path).header("Accept", "application/json")
            if (authenticated) {
                val token = accessToken() ?: error("Sign in again")
                builder.header("Authorization", "BizKit $token")
                selectedRoleAssignmentId()?.let { builder.header("X-Role-Assignment-ID", it) }
            }
            builder.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                    error(detail?.takeIf { it.isNotBlank() } ?: "Request refused (${response.code})")
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }

    companion object {
        private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
        private fun random() = encode(ByteArray(32).also(SecureRandom()::nextBytes))
        @Suppress("DEPRECATION")
        fun create(context: Context, baseUrl: String): NativeSession {
            val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(context, "native_identity", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            return NativeSession(prefs, baseUrl)
        }
    }
}
