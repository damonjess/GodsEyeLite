package com.gev.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        val californiaCameras = cameras.filter { it.pack == "california" }
        assertTrue("California has no cameras", californiaCameras.isNotEmpty())
        assertTrue(
            "California dropped the still-only cameras to the cap",
            californiaCameras.size >= 3000,
        )
        assertTrue(
            "California has no live cameras left",
            californiaCameras.count { it.videoUrl != null } >= 2000,
        )
        val california = californiaCameras.first()
        assertTrue(
            "California has no image URL",
            california.imageUrl.startsWith("https://cwwp2.dot.ca.gov/"),
        )
        assertTrue(
            "California has no live HLS URL",
            californiaCameras.firstOrNull { it.videoUrl != null }
                ?.videoUrl?.startsWith("https://wzmedia.dot.ca.gov/") == true,
        )

        for ((label, url) in listOf(
            "deldot" to deldot.videoUrl,
            "norway" to norwayVideo?.videoUrl,
            "california" to californiaCameras.firstOrNull { it.videoUrl != null }?.videoUrl,
        )) {
            val playlist = repository.fetchFrame(url!!)
            if (playlist != null) {
                assertTrue("$label is not an HLS playlist", String(playlist).contains("#EXTM3U"))
            } else {
                println("$label stream is unreachable, skipping HLS assert")
            }
        }

        val tfl = cameras.first { it.pack == "tfl" }
        val tflClip = tfl.videoUrl?.let { repository.fetchFrame(it) }
        if (tflClip != null) {
            assertTrue("TfL clip is too small to be a video", tflClip.size > 1000)
            assertEquals("TfL clip is not an MP4", "ftyp", String(tflClip, 4, 4))
        } else {
            println("TfL clip is unreachable, skipping MP4 assert")
        }
    }

    @Test
    fun normalizesEveryCaltransStreamSpelling() {
        val path = "D4/example.stream/playlist.m3u8"
        for (prefix in listOf(
            "https://wzmedia.dot.ca.gov/",
            "https://wzmedia.dot.ca.gov:443/",
            "http://wzmedia.dot.ca.gov/",
            "ttps://wzmedia.dot.ca.gov/",
        )) {
            assertEquals(
                "https://wzmedia.dot.ca.gov/$path",
                CctvRepository.caltransStream(prefix + path),
            )
        }
        assertNull(CctvRepository.caltransStream(""))
        assertNull(CctvRepository.caltransStream("https://example.com/D4/a.stream/playlist.m3u8"))
    }

    /**
     * Audits the LIVE set against the publishers' catalogs.
     *
     * The globe labels a camera LIVE purely from `videoUrl`, so a parser that
     * drops or invents one changes the filter without any visible error. This
     * pins down which packs may be live, which must never be, and the shape of
     * every stream URL.
     */
    @Test
    fun liveCamerasMatchTheirPublishers() {
        val cameras = CctvRepository().loadAll()
        val perPack = cameras.groupingBy { it.pack }.eachCount()
        val live = cameras.filter { !it.videoUrl.isNullOrBlank() }
        val livePerPack = live.groupingBy { it.pack }.eachCount()
        println("LIVE AUDIT: ${cameras.size} cameras, ${live.size} live")
        for (pack in CctvRepository.PACKS) {
            println(
                "LIVE AUDIT: ${pack.key} total=${perPack[pack.key] ?: 0} " +
                    "live=${livePerPack[pack.key] ?: 0}",
            )
        }

        // Publishers that serve snapshots only. Marking one of these live would
        // put a dead clip behind the LIVE badge.
        for (key in listOf(
            "austin", "calgary", "wsdot", "finland", "hongkong", "drivebc", "nsw", "a22",
        )) {
            assertEquals("$key is snapshot-only but has live cameras", 0, livePerPack[key] ?: 0)
        }

        // Publishers that actually carry video. Each must contribute live rows.
        for (key in listOf("tfl", "norway", "deldot", "california")) {
            assertTrue("$key has no live cameras", (livePerPack[key] ?: 0) > 0)
        }

        // Every TfL jamcam and every active DelDOT camera publishes video.
        assertEquals("TfL lost live cameras", perPack["tfl"] ?: 0, livePerPack["tfl"] ?: 0)
        assertEquals("DelDOT lost live cameras", perPack["deldot"] ?: 0, livePerPack["deldot"] ?: 0)

        val expectedHost = mapOf(
            "tfl" to "https://s3-eu-west-1.amazonaws.com/jamcams.tfl.gov.uk/",
            "norway" to "https://kamera.vegvesen.no/",
            "deldot" to "https://video.deldot.gov/",
            "california" to "https://wzmedia.dot.ca.gov/",
        )
        for (camera in live) {
            val url = camera.videoUrl!!
            val host = expectedHost[camera.pack]
            assertTrue("${camera.id} is live in an unexpected pack ${camera.pack}", host != null)
            assertTrue("${camera.id} stream is not https: $url", url.startsWith("https://"))
            assertTrue("${camera.id} stream is on the wrong host: $url", url.startsWith(host!!))
            if (camera.pack == "deldot") {
                assertTrue(
                    "DelDOT camera ${camera.id} unexpectedly has a still",
                    camera.imageUrl.isBlank(),
                )
            } else {
                assertTrue(
                    "${camera.id} has no still fallback",
                    camera.imageUrl.startsWith("https://"),
                )
            }
        }

        // The California cap must not silently truncate live rows.
        val california = cameras.filter { it.pack == "california" }
        assertTrue("California lost cameras to the cap", california.size >= 3300)
        assertTrue(
            "California lost live cameras",
            california.count { it.videoUrl != null } >= 2000,
        )
        assertEquals("Duplicate camera ids", cameras.size, cameras.map { it.id }.toSet().size)
    }
}
