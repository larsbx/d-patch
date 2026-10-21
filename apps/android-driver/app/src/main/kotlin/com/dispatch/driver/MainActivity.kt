package com.dispatch.driver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dispatch.driver.core.auth.NativeSession
import com.dispatch.driver.core.model.capability.CapabilityProvider
import com.dispatch.driver.core.model.capability.SyncRequester
import com.dispatch.driver.core.network.DispatchClient
import com.dispatch.driver.core.network.Fetch
import com.dispatch.driver.core.network.RoleAssignmentChoice
import com.dispatch.driver.feature.status.StatusRoute
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var session: NativeSession
    @Inject lateinit var client: DispatchClient
    @Inject lateinit var capabilities: CapabilityProvider
    @Inject lateinit var sync: SyncRequester
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var revision by mutableIntStateOf(0)
    private var message by mutableStateOf<String?>(null)
    private var choices by mutableStateOf<List<RoleAssignmentChoice>>(emptyList())
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        message?.let { Text(it) }
                        // Recompose after a session or role transition; the status
                        // screen gets a fresh capability view before it is offered.
                        key(revision) {
                            if (session.subject() == null) {
                                Button(enabled = !busy, onClick = { perform {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.beginSignIn())))
                                } }) { Text("Sign in") }
                            } else {
                                Text("Signed in")
                                Button(enabled = !busy, onClick = { perform {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.beginSignIn())))
                                } }) { Text("Sign in again") }
                                Button(enabled = !busy, onClick = { perform { loadRoles() } }) { Text("Choose or change role") }
                                choices.forEach { choice ->
                                    Button(enabled = !busy, onClick = { perform {
                                        session.chooseRole(choice.id); choices = emptyList()
                                        capabilities.refresh(); revision++; sync.requestSync()
                                    } }) { Text(choice.label) }
                                }
                                if (session.selectedRoleAssignmentId() != null) {
                                    Button(enabled = !busy, onClick = { perform {
                                        message = "Ask your administrator to approve device code: " + session.requestEnrolment()
                                    } }) { Text("Request device access") }
                                    Button(enabled = !busy, onClick = { perform {
                                        session.registerDevice(); capabilities.refresh(); revision++
                                        sync.requestSync(); message = "Device access is ready."
                                    } }) { Text("Check device approval") }
                                    if (session.deviceReady()) StatusRoute()
                                }
                                Button(enabled = !busy, onClick = { perform {
                                    session.signOut(); choices = emptyList(); capabilities.refresh(); revision++
                                } }) { Text("Sign out") }
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleCallback(intent) }
    private fun handleCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "com.dispatch.driver") perform {
            session.completeSignIn(uri); loadRoles(); capabilities.refresh(); revision++; sync.requestSync()
        }
    }
    private suspend fun loadRoles() {
        when (val result = client.roleAssignments()) {
            is Fetch.Ok -> { choices = result.value; if (choices.isEmpty()) message = "No active roles. Contact your administrator." }
            is Fetch.Problem -> error(result.detail ?: "Sign in again to load your roles.")
            Fetch.Unreachable -> error("Connect to load your roles.")
        }
    }
    private fun perform(block: suspend () -> Unit) {
        if (busy) return
        activityScope.launch {
            busy = true
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = error.message ?: "Try again." }
            finally { busy = false; revision++ }
        }
    }
    override fun onDestroy() { activityScope.cancel(); super.onDestroy() }
}
