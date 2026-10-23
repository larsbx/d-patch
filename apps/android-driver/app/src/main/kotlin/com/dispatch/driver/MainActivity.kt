package com.dispatch.driver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.dispatch.driver.feature.status.StatusRoute
import com.dispatch.driver.service.location.TripSharing
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var signIn: SignInFlow

    @Inject lateinit var deviceRegistration: DeviceRegistration

    @Inject lateinit var tripSharing: TripSharing

    @Inject lateinit var sharingHost: AppSharingHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity has already handled the intent it was started with.
        if (savedInstanceState == null) receive(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val message by signIn.message.collectAsStateWithLifecycle()
                    Column {
                        message?.let { Text(it, Modifier.padding(16.dp)) }
                        StatusRoute(onSignIn = ::openSignIn)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        deviceRegistration.retry()
        // In the foreground, where a location service may start: Section 25.3's
        // resumption, after the server confirms the consent, and any owed stop.
        lifecycleScope.launch { tripSharing.resume() }
        // Section 25.3: a running session sends what it holds.
        sharingHost.foregrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** RFC 8252 §6: the system browser, never a web view the app could read. */
    private fun openSignIn() {
        signIn.start()?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
    }

    /** biz-kit's redirect arrives as a VIEW of the app's own scheme; anything else is not one. */
    private fun receive(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.dataString?.let(signIn::accept)
    }
}
