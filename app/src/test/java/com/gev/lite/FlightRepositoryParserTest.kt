package com.gev.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlightRepositoryParserTest {

    @Test
    fun classifiesMilitaryEmergencyAndNormalFlights() {
        val body = """
            {"ac":[
              {"hex":"abc123","flight":"RCH123","lat":51.0,"lon":-0.1,"alt_baro":10000,
               "gs":250,"track":90,"dbFlags":1,"emergency":"none","squawk":"7700"},
              {"hex":"def456","flight":"LIFEGUARD1","lat":51.0,"lon":-0.1,"alt_baro":1000,
               "gs":100,"track":90,"emergency":"lifeguard"},
              {"hex":"fed789","flight":"BAW123","lat":51.0,"lon":-0.1,"alt_baro":10000,
               "gs":250,"track":90,"emergency":"none"},
              {"hex":"aaa111","flight":"NHZ23","lat":51.0,"lon":-0.1,"alt_baro":1000,
               "gs":100,"track":90,"category":"A7","emergency":"none"},
              {"hex":"bbb222","flight":"NPAS21","lat":51.0,"lon":-0.1,"alt_baro":1000,
               "gs":100,"track":90,"category":"A7","emergency":"none"}
            ]}
        """.trimIndent()

        val repository = FlightRepository()
        val flights = repository.parse(body)

        assertEquals(5, flights.size)
        assertTrue(flights[0].military)
        assertTrue(flights[0].emergency)
        assertEquals("7700", flights[0].emergencyReason)

        assertFalse(flights[1].military)
        assertTrue(flights[1].emergency)
        assertEquals("MEDICAL", flights[1].emergencyReason)

        assertFalse(flights[2].military)
        assertFalse(flights[2].emergency)
        assertEquals("", flights[2].emergencyReason)

        assertFalse(flights[3].military)
        assertTrue(flights[3].emergency)
        assertEquals("MEDICAL", flights[3].emergencyReason)

        assertFalse(flights[4].military)
        assertTrue(flights[4].emergency)
        assertEquals("POLICE", flights[4].emergencyReason)

        assertEquals(
            "MEDICAL",
            repository.serviceOwnerReason("Center for Emergency Medicine of Western PA", "EC45"),
        )
        assertEquals(
            "POLICE",
            repository.serviceOwnerReason("Texas Department of Public Safety", "C208"),
        )
        assertEquals("", repository.serviceOwnerReason("Merck Sharp & Dohme LLC", "H160"))
    }
}
