package com.slipstream.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.slipstream.app.peer.PeerConnectionState
import com.slipstream.app.peer.PeerStatus
import com.slipstream.meridian.component.MeridianStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 3: the nav shell. Five destinations, Home first; a top-bar connection pill whose colour
 * follows [PeerConnectionState] exactly as Plan 5's shell specifies.
 */
@RunWith(RobolectricTestRunner::class)
class SlipstreamNavHostTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the five destinations are declared in order, Home first`() {
        assertEquals(
            listOf("Home", "Browse", "Transfers", "History", "Settings"),
            SlipstreamDestination.entries.map { it.label },
        )
        assertEquals(SlipstreamDestination.Home, SlipstreamDestination.entries.first())
    }

    @Test
    fun `Home is shown on launch`() {
        compose.setContent {
            SlipstreamNavHost(peerStatus = MutableStateFlow(PeerStatus(PeerConnectionState.Idle)))
        }
        compose.onNodeWithTag("screen-content").assertTextEquals("Home")
    }

    @Test
    fun `bottom navigation shows all five destinations and switches screens`() {
        compose.setContent {
            SlipstreamNavHost(peerStatus = MutableStateFlow(PeerStatus(PeerConnectionState.Idle)))
        }

        SlipstreamDestination.entries.forEach { destination ->
            compose.onNodeWithTag("navitem-${destination.route}").assertTextEquals(destination.label)
        }

        compose.onNodeWithTag("navitem-browse").performClick()
        compose.onNodeWithTag("screen-content").assertTextEquals("Browse")
    }

    @Test
    fun `connection state maps to MeridianStatus exactly as Plan 5's shell does`() {
        assertEquals(MeridianStatus.Positive, PeerConnectionState.Connected.toMeridianStatus())
        assertEquals(MeridianStatus.Info, PeerConnectionState.Searching.toMeridianStatus())
        assertEquals(MeridianStatus.Warning, PeerConnectionState.Degraded.toMeridianStatus())
        assertEquals(MeridianStatus.Critical, PeerConnectionState.Lost.toMeridianStatus())
        assertEquals(MeridianStatus.Neutral, PeerConnectionState.Idle.toMeridianStatus())
    }

    @Test
    fun `the degraded pill names the band`() {
        compose.setContent {
            SlipstreamNavHost(
                peerStatus = MutableStateFlow(
                    PeerStatus(PeerConnectionState.Degraded, band = "2.4 GHz"),
                ),
            )
        }
        compose.onNodeWithText("Degraded — 2.4 GHz").assertIsDisplayed()
    }
}
