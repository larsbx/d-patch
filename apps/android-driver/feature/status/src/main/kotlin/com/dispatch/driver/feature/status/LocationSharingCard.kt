package com.dispatch.driver.feature.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dispatch.driver.service.location.SharingCard

/**
 * Section 6.1 and 25.3: sharing is explicit, visible and revocable. The offer
 * says what sharing means before the driver agrees; while it runs, the card
 * says so in text and keeps `Stop sharing` one tap away (Section 26.7: never
 * colour alone).
 */
@Composable
internal fun LocationSharingCard(card: SharingCard, onShare: () -> Unit, onStop: () -> Unit) {
    if (card == SharingCard.Hidden) return
    Card(modifier = Modifier.fillMaxWidth().testTag("location-sharing")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Location", style = MaterialTheme.typography.titleMedium)
            when (card) {
                SharingCard.Hidden -> Unit
                SharingCard.Checking -> Text("Checking your trips…")
                SharingCard.Unavailable -> Text("Can't check your trips right now. Sharing can start once connected.")
                SharingCard.NoTrip -> Text("No trip to share your location for.")
                is SharingCard.Offer -> {
                    Text("Your dispatcher sees where you are while this trip is shared. It stops when the trip ends or when you stop it.")
                    Button(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("share-location")) {
                        Text("Share location for this trip")
                    }
                }
                SharingCard.Sharing -> {
                    Text("Sharing your location", modifier = Modifier.testTag("sharing-location"))
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("stop-sharing")) {
                        Text("Stop sharing")
                    }
                }
                SharingCard.Stopping -> Text("Stopped. Telling the server…")
            }
        }
    }
}
