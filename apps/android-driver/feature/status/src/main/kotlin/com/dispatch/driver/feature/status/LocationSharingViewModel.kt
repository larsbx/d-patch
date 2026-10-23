package com.dispatch.driver.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dispatch.driver.service.location.SharingCard
import com.dispatch.driver.service.location.TripSharing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The location card's state and buttons; the rules are `TripSharing`'s. */
@HiltViewModel
class LocationSharingViewModel @Inject constructor(private val trips: TripSharing) : ViewModel() {

    val card: StateFlow<SharingCard> = trips.state

    init {
        viewModelScope.launch { trips.refresh() }
    }

    fun share() {
        viewModelScope.launch { trips.share() }
    }

    fun stop() {
        viewModelScope.launch { trips.stop() }
    }
}
