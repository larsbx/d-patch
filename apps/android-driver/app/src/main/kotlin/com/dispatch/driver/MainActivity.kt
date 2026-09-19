package com.dispatch.driver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import com.dispatch.driver.core.database.StatusOutbox
import com.dispatch.driver.feature.status.StatusPresenter
import com.dispatch.driver.feature.status.StatusScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = AndroidStatusRepository(this, StatusOutbox(RuntimeGraph.database),
            RuntimeGraph.sessions, RuntimeGraph.json)
        val presenter = StatusPresenter(repository, lifecycleScope)
        presenter.selectRole(RuntimeGraph.sessions.activeRole())
        setContent { MaterialTheme { StatusScreen(presenter) } }
    }
}
