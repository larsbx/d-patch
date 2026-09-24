package com.dispatch.driver.feature.status

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.dispatch.driver.core.database.OutboxEntry
import com.dispatch.driver.core.database.PendingState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The rendered screen: what is offered, and what it says about the current status. */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["androidx.loader.content"])
class StatusScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val declared = mutableListOf<Pair<String, String?>>()

    private fun show(state: StatusUiState, error: String? = null) = compose.setContent {
        StatusScreen(state, error, onDeclare = { v, n -> declared += v to n; true }, onRefresh = {})
    }

    private fun ready(current: DisplayedStatus? = null, attention: List<OutboxEntry> = emptyList(), stale: Boolean = false) =
        StatusUiState.Ready(document().statusOptions, current, attention, "ra-1", stale)

    @Test
    fun `offers exactly the document's statuses, and a one-tap choice declares at once`() {
        show(ready())

        compose.onNodeWithTag("status-option-AT_PICKUP").performClick()

        assertEquals(listOf("AT_PICKUP" to null), declared)
    }

    @Test
    fun `a status that needs a note asks for one before declaring`() {
        show(ready())

        compose.onNodeWithTag("status-option-DELAYED").performClick()
        assertEquals(emptyList<Pair<String, String?>>(), declared)

        compose.onNodeWithTag("status-note").performTextInput("Road closed")
        compose.onNodeWithTag("status-send-note").performClick()

        assertEquals(listOf("DELAYED" to "Road closed"), declared)
    }

    @Test
    fun `the current status names its source and its freshness in words`() {
        show(ready(DisplayedStatus("At pickup", "Courier-reported status", Instant.EPOCH, Freshness.NotYetSent)))

        fun inCard(text: String) = hasText(text, substring = true) and hasAnyAncestor(hasTestTag("current-status"))
        compose.onNode(inCard("At pickup")).assertIsDisplayed()
        compose.onNode(inCard("Courier-reported status")).assertIsDisplayed()
        compose.onNodeWithText("Not yet sent — will send when connected").assertIsDisplayed()
    }

    @Test
    fun `a rejected declaration shows the corrective action`() {
        val rejected = OutboxEntry(7, "DELAYED", "x", "2026-09-24T10:00:00Z", PendingState.REJECTED, 1, "ROLE_ASSIGNMENT_INVALID", "Choose your role again and redeclare.")

        show(ready(attention = listOf(rejected)))

        compose.onNodeWithText("Not accepted: Delayed").assertIsDisplayed()
        compose.onNodeWithText("Choose your role again and redeclare.").assertIsDisplayed()
    }

    @Test
    fun `a stale document is labelled`() {
        show(ready(stale = true))
        compose.onNodeWithTag("capabilities-stale").assertIsDisplayed()
    }

    @Test
    fun `when the surface is not offered, no status control exists`() {
        show(StatusUiState.NotOffered("Your current role does not declare a status."))

        compose.onNodeWithTag("status-not-offered").assertIsDisplayed()
        compose.onNodeWithTag("status-option-AT_PICKUP").assertDoesNotExist()
    }
}
