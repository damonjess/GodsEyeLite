package com.gev.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceWatchAlertStoreTest {

    private fun record(
        atMs: Long,
        hex: String = "abc123",
        identity: String = "HELIMED1",
        role: ServiceWatchRole = ServiceWatchRole.MEDEVAC,
        distanceM: Double = 4_200.0,
    ) = ServiceWatchAlertRecord(
        atMs = atMs,
        hex = hex,
        identity = identity,
        role = role,
        distanceM = distanceM,
    )

    @Test
    fun encodeDecodeRoundTripsRecordsNewestFirst() {
        val older = record(1_000L, hex = "aaa111", role = ServiceWatchRole.POLICE)
        val newer = record(2_000L, hex = "bbb222", role = ServiceWatchRole.FIRE)

        val decoded = ServiceWatchAlertStore.decode(
            ServiceWatchAlertStore.encode(listOf(older, newer)),
        )

        assertEquals(listOf("bbb222", "aaa111"), decoded.map { it.hex })
        assertEquals("HELIMED1", decoded.first().identity)
        assertEquals(ServiceWatchRole.FIRE, decoded.first().role)
        assertEquals(4_200.0, decoded.first().distanceM, 0.001)
    }

    @Test
    fun mergeKeepsOnlyTheMostRecentEntries() {
        val existing = (1..20).map { record(it.toLong(), hex = "old$it") }
        val added = (21..35).map { record(it.toLong(), hex = "new$it") }

        val merged = ServiceWatchAlertStore.merge(existing, added)

        assertEquals(ServiceWatchAlertStore.MAX_ENTRIES, merged.size)
        assertEquals("new35", merged.first().hex)
        assertEquals("old6", merged.last().hex)
    }

    @Test
    fun malformedAndUnknownValuesAreHandled() {
        assertTrue(ServiceWatchAlertStore.decode("not json").isEmpty())

        val decoded = ServiceWatchAlertStore.decode(
            """[
              {"atMs":1000,"hex":"aaa111","identity":"","role":"BOGUS","distanceM":1200},
              {"atMs":0,"hex":"bad","role":"POLICE","distanceM":1},
              {"atMs":2000,"hex":"","role":"POLICE","distanceM":1}
            ]""",
        )

        assertEquals(1, decoded.size)
        assertEquals("AAA111", decoded[0].identity)
        assertEquals(ServiceWatchRole.OTHER, decoded[0].role)
    }
}
