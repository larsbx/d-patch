package com.dispatch.driver.feature.status

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dispatch.driver.core.network.Trip
import com.dispatch.driver.service.location.SharingCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The status screen's location card: consent asked for plainly, sharing always visible, Stop one tap away. */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["androidx.loader.content"])
class LocationSharingCardTest {

    @get:Rule
    val compose = createComposeRule()

    private var shares = 0
    private var stops = 0

    private fun show(card: SharingCard, state: StatusUiState = StatusUiState.Loading) = compose.setContent {
        StatusScreen(state, null, onDeclare = { _, _ -> true }, onRefresh = {}, onSignIn = {}, sharing = card, onShare = { shares++ }, onStopSharing = { stops++ })
    }

    @Test
    fun `a trip is offered with what sharing means, and sharing asks for nothing more`() {
        show(SharingCard.Offer(Trip("trip-1", "load-1", "ACTIVE", "2026-10-23T08:00:00Z", null, null)))

        compose.onNodeWithText("Your dispatcher sees where you are while this trip is shared.", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("share-location").performClick()

        assertEquals(1, shares)
    }

    @Test
    fun `while sharing, the card says so and Stop sharing stops it`() {
        show(SharingCard.Sharing)

        compose.onNodeWithText("Sharing your location").assertIsDisplayed()
        compose.onNodeWithTag("stop-sharing").performClick()

        assertEquals(1, stops)
    }

    // Section 2.2: even signed out, a running session shows itself.
    @Test
    fun `a running session shows on every screen state`() {
        show(SharingCard.Sharing, StatusUiState.SignInRequired)
        compose.onNodeWithTag("stop-sharing").assertIsDisplayed()
    }

    @Test
    fun `stopping, no trip and hidden say so or show nothing`() {
        show(SharingCard.Stopping)
        compose.onNodeWithText("Stopped. Telling the server…").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag("share-location")).fetchSemanticsNodes().size)
    }

    @Test
    fun `hidden is nothing at all`() {
        show(SharingCard.Hidden)
        assertEquals(0, compose.onAllNodes(hasTestTag("location-sharing")).fetchSemanticsNodes().size)
    }
}
