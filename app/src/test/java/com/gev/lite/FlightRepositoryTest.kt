package com.gev.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class FlightRepositoryTest {

    @Test
    fun testHelicopterEmergencyOwnerMatching() {
        val repository = FlightRepository()

        assertEquals(
            "POLICE",
            repository.serviceOwnerReason("METROPOLITAN POLICE SERVICE"),
        )
        assertEquals(
            "MEDICAL",
            repository.serviceOwnerReason("AIR AMBULANCE KENT SURREY SUSSEX"),
        )
        assertEquals(
            "FIRE",
            repository.serviceOwnerReason("LOS ANGELES FIRE DEPARTMENT"),
        )
        assertEquals(
            "RESCUE",
            repository.serviceOwnerReason("HER MAJESTY'S COASTGUARD"),
        )
        assertEquals(
            "MEDICAL",
            repository.serviceOwnerReason("Babcock MCS Onshore", "RHD"),
        )
        assertEquals(
            "MEDICAL",
            repository.serviceOwnerReason("Babcock Mission Critical Services Onshore Ltd"),
        )
        assertEquals(
            "",
            repository.serviceOwnerReason("Babcock MCS Offshore", "RHD"),
        )
    }

    @Test
    fun testParseAdsbLolResponse() {
        val repository = FlightRepository()
        // Test fetching UK flights directly from adsb.lol
        val flights = repository.fetchAround(53.5, -0.5, 350)
        assertNotNull("Expected non-null flights from adsb.lol", flights)
        assertFalse("Expected flights list not to be empty", flights!!.isEmpty())
        println("Fetched ${flights.size} flights over the UK")
        val first = flights.first()
        println("First flight: hex=${first.hex}, callsign=${first.callsign}, lat=${first.lat}, lon=${first.lon}, altM=${first.altitudeM}")
    }
}
