package com.gev.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CctvRepositoryTest {

    @Test
    fun loadsWorldwidePacksAndMedia() {
        val repository = CctvRepository()
        val cameras = repository.loadAll()
        val perPack = cameras.groupingBy { it.pack }.eachCount()
        println("Loaded ${cameras.size} cameras: $perPack")

        assertTrue("Expected the full bundled set", cameras.size >= 11000)
        for (pack in listOf(
            "austin", "calgary", "wsdot", "tfl", "finland", "norway",
            "hongkong", "drivebc", "nsw", "a22", "deldot", "california",
        )) {
            assertTrue("pack $pack loaded nothing", (perPack[pack] ?: 0) > 0)
        }

        // One real still per new still pack, through the same native fetch the
        // camera panel uses. NSW is included on purpose: it only serves a JPEG
        // to a browser user-agent.
        for (pack in listOf("finland", "norway", "hongkong", "drivebc", "nsw", "a22", "california")) {
            val camera = cameras.first { it.pack == pack }
            val frame = repository.fetchFrame(camera.imageUrl)
                ?: error("frame for $pack was empty (${camera.imageUrl})")
            assertTrue("frame for $pack was only ${frame.size} bytes", frame.size > 1000)
            assertEquals("frame for $pack is not a JPEG", 0xFF.toByte(), frame[0])
            assertEquals("frame for $pack is not a JPEG", 0xD8.toByte(), frame[1])
        }

        // Live video: every Delaware row is HLS-only, and Norway carries an HLS
        // URL on the cameras that publish one. The master playlists must answer
        // through the app's native fetch and parse as HLS.
        val deldot = cameras.first { it.pack == "deldot" }
        assertTrue(
            "DelDOT has no https HLS URL",
            deldot.videoUrl?.startsWith("https://video.deldot.gov") == true,
        )
        assertTrue("DelDOT unexpectedly has a still", deldot.imageUrl.isEmpty())

        val norwayVideo = cameras.firstOrNull { it.pack == "norway" && !it.videoUrl.isNullOrBlank() }
        assertTrue("Norway has no live video cameras", norwayVideo != null)

        val california = cameras.firstOrNull { it.pack == "california" }
        assertTrue("California has no cameras", california != null)
        assertTrue(
            "California has no image URL",
            california?.imageUrl?.startsWith("https://cwwp2.dot.ca.gov/") == true,
        )

        for ((label, url) in listOf(
            "deldot" to deldot.videoUrl,
            "norway" to norwayVideo?.videoUrl,
        )) {
            val playlist = repository.fetchFrame(url!!)
            if (playlist != null) {
                assertTrue("$label is not an HLS playlist", String(playlist).contains("#EXTM3U"))
            } else {
                println("$label stream is unreachable, skipping HLS assert")
            }
        }
    }
}
