package com.gev.lite

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A small 3D-globe viewer: photorealistic globe with 3D buildings plus public
 * traffic cameras.
 *
 * Layout of responsibilities:
 *  - Kotlin owns the network. Native HTTP has no CORS, so the public camera
 *    catalogs and frames are fetched directly, with no proxy and no server.
 *  - The WebView owns the rendering: Cesium draws the globe, the 3D buildings
 *    and the camera pins.
 *  - The bridge between them is [Bridge], plus `GEVLite.setCameras(...)` in the
 *    other direction.
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "GodsEyeLite"
        const val PREFS = "gev-lite"
        const val PREF_ION_TOKEN = "ion_token"

        /** DelDOT's stream host is friendlier to a browser identity than the media stack's default UA. */
        const val BROWSER_MEDIA_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126 Mobile Safari/537.36"

        /** How often an open snapshot panel re-fetches its frame. */
        const val STILL_REFRESH_MS = 10_000L

        /**
         * Caltrans' HLS host can take half a minute to answer a playlist, which
         * is why the stream looks dead at the default couple of seconds. The
         * panel shows the still underneath while this is happening.
         */
        const val CALTRANS_CONNECT_TIMEOUT_MS = 20_000
        const val CALTRANS_READ_TIMEOUT_MS = 30_000
        const val CALTRANS_CLIP_TIMEOUT_MS = 40_000L

        /**
         * Served by WebViewAssetLoader. A real https origin (rather than
         * file://) is what gives the page a secure, stable origin, so Cesium's
         * blob-backed web workers work and the Cesium ion token can be scoped
         * to this exact origin.
         */
        const val APP_ORIGIN = "https://appassets.androidplatform.net"
        const val START_URL = "$APP_ORIGIN/assets/web/index.html"

        const val REQUEST_NOTIFICATIONS = 102
    }

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var ionTokenInput: EditText
    private lateinit var cameraPanel: LinearLayout
    private lateinit var frameImage: ImageView
    private lateinit var frameVideo: TextureView
    private lateinit var cameraTitle: TextView
    private lateinit var cameraCredit: TextView

    private val repository = CctvRepository()
    private val flights = FlightRepository()
    private val satellites = SatelliteRepository()
    private val events = EventsRepository()
    private val weather = WeatherRepository()
    private val flightFetchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val satelliteFetchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val eventFetchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val weatherFetchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val detailsInFlight: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val io: ExecutorService = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    private var geoCallback: android.webkit.GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null

    /** Cameras by id, so a tap in the globe can be resolved back to a frame URL. */
    private val cameras = LinkedHashMap<String, CctvCamera>()
    private var selected: CctvCamera? = null
    private var globeReady = false
    private var pendingWatchAlertConfig: ServiceWatchConfig? = null
    private var watchAlertReceiverRegistered = false

    private val watchAlertStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pushServiceWatchAlertsState()
        }
    }
    private var clipPlayer: ExoPlayer? = null
    private var stillRefresh: Runnable? = null

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private var ionToken: String
        get() = prefs.getString(PREF_ION_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(PREF_ION_TOKEN, value.trim()).apply()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Debug builds only: makes the globe inspectable over chrome://inspect
        // (adb forward to the webview_devtools_remote socket), which is how the
        // imagery and bridge problems in this app were diagnosed.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        settingsPanel = findViewById(R.id.settingsPanel)
        ionTokenInput = findViewById(R.id.ionTokenInput)
        cameraPanel = findViewById(R.id.cameraPanel)
        frameImage = findViewById(R.id.frameImage)
        frameVideo = findViewById(R.id.frameVideo)
        cameraTitle = findViewById(R.id.cameraTitle)
        cameraCredit = findViewById(R.id.cameraCredit)

        setUpWebView()
        setUpButtons()
        loadCameras()
    }

    private fun setUpWebView() {
        webView = findViewById(R.id.webView)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // The bundled app is same-origin with itself; remote tiles are CORS-enabled.
            allowFileAccess = false
            allowContentAccess = false
        }
        webView.setBackgroundColor(0xFF07070B.toInt())

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val response = assetLoader.shouldInterceptRequest(request.url) ?: return null
                // Bundled assets are replaced wholesale on every install, but the
                // WebView caches them heuristically. Without this, an update keeps
                // running the PREVIOUS build's app.js until the cache expires —
                // which is exactly what hid a HUD change until a hard reload.
                // Only intercepted (bundled) responses are marked; remote requests
                // like the Cesium CDN keep their normal caching.
                val headers = HashMap(response.responseHeaders ?: emptyMap())
                headers["Cache-Control"] = "no-store"
                response.responseHeaders = headers
                return response
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "page loaded: $url")
                // Push state as soon as the page is up; the bridge's onGlobeReady
                // covers the case where Cesium says hello first.
                pushToken()
                if (cameras.isNotEmpty()) pushCameras()
            }
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: android.webkit.GeolocationPermissions.Callback
            ) {
                val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasFine || hasCoarse) {
                    callback.invoke(origin, true, false)
                } else {
                    geoCallback = callback
                    geoOrigin = origin
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        101
                    )
                }
            }
        }

        webView.addJavascriptInterface(Bridge(), "GEVAndroid")
        webView.loadUrl(START_URL)
    }

    private fun setUpButtons() {
        findViewById<Button>(R.id.keyButton).setOnClickListener {
            ionTokenInput.setText(ionToken)
            settingsPanel.visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.saveKey).setOnClickListener {
            ionToken = ionTokenInput.text.toString()
            settingsPanel.visibility = View.GONE
            pushToken()
        }
        findViewById<Button>(R.id.closeKey).setOnClickListener {
            settingsPanel.visibility = View.GONE
        }
        findViewById<Button>(R.id.reloadFrame).setOnClickListener {
            selected?.let { camera ->
                cameraTitle.text = camera.name
                cameraCredit.text = camera.credit
                showMedia(camera)
            }
        }
        findViewById<Button>(R.id.closeFrame).setOnClickListener {
            cameraPanel.visibility = View.GONE
            clearStillRefresh()
            stopClip()
            selected = null
        }
    }

    private fun loadCameras() {
        io.execute {
            val loaded = repository.loadAll()
            main.post {
                if (isFinishing) return@post
                cameras.clear()
                for (camera in loaded) cameras[camera.id] = camera
                status.text = buildStatusLine(loaded)
                pushPacks()
                pushCameras()
            }
        }
    }

    private fun buildStatusLine(loaded: List<CctvCamera>): String {
        if (loaded.isEmpty()) return "No cameras reachable — check your connection"
        val perPack = loaded.groupingBy { it.pack }.eachCount()
        val parts = CctvRepository.PACKS.mapNotNull { pack ->
            perPack[pack.key]?.let { "${pack.label} $it" }
        }
        return "${loaded.size} cameras  ·  ${parts.joinToString("  ·  ")}"
    }

    /** Sends the globe the camera list, which is what draws the pins. */
    private fun pushCameras() {
        if (cameras.isEmpty()) return
        val array = JSONArray()
        for (camera in cameras.values) {
            array.put(
                JSONObject()
                    .put("id", camera.id)
                    .put("pack", camera.pack)
                    .put("name", camera.name)
                    .put("lat", camera.lat)
                    .put("lon", camera.lon)
                    // The globe loads thumbnails from the publisher's CDN directly:
                    // a plain <img> needs no CORS, and every pack serves https.
                    // Sending the URL here is what lets a card render without a
                    // round trip through the native frame fetcher.
                    .put("imageUrl", camera.imageUrl)
                    // Lets the globe mark a camera card as live video; the
                    // native panel itself already has the full camera record.
                    .put("videoUrl", camera.videoUrl ?: ""),
            )
        }
        evaluate("window.GEVLite && GEVLite.setCameras($array)")
    }

    /** Fetches aircraft around the point the globe is looking at and pushes them back. */
    private fun loadFlights(lat: Double, lon: Double, radiusNm: Double) {
        if (io.isShutdown) return
        // One request at a time: a slow response must not stack up behind new ones.
        if (!flightFetchInFlight.compareAndSet(false, true)) return
        io.execute {
            try {
                val result = flights.fetchAround(lat, lon, radiusNm.toInt())
                if (result == null) {
                    evaluate("window.GEVLite && GEVLite.flightsFailed && GEVLite.flightsFailed()")
                } else {
                    evaluate("window.GEVLite && GEVLite.setFlights(${flights.toJson(result)})")
                    // Push the live planes immediately, then add service tags
                    // discovered from registration metadata. The lookup is
                    // cached per helicopter, so later polls do no network work.
                    val enriched = runCatching { flights.enrichEmergencyServices(result) }
                        .getOrDefault(result)
                    if (enriched !== result) {
                        evaluate("window.GEVLite && GEVLite.setFlights(${flights.toJson(enriched)})")
                    }
                }
            } finally {
                flightFetchInFlight.set(false)
            }
        }
    }

    /** Fetches the TLE catalog once and lets the WebView propagate the orbits locally. */
    private fun loadSatellites() {
        if (io.isShutdown || !satelliteFetchInFlight.compareAndSet(false, true)) return
        io.execute {
            try {
                val result = runCatching { satellites.load() }.getOrNull()
                if (result.isNullOrEmpty()) {
                    evaluate("window.GEVLite && GEVLite.satellitesFailed && GEVLite.satellitesFailed()")
                } else {
                    evaluate("window.GEVLite && GEVLite.setSatellites(${satellites.toJson(result)})")
                }
            } finally {
                satelliteFetchInFlight.set(false)
            }
        }
    }

    /** Fetches the keyless USGS + NASA EONET event feeds for the events layer. */
    private fun loadEvents() {
        if (io.isShutdown || !eventFetchInFlight.compareAndSet(false, true)) return
        io.execute {
            try {
                val result = runCatching { events.load() }.getOrNull()
                if (result.isNullOrEmpty()) {
                    evaluate("window.GEVLite && GEVLite.eventsFailed && GEVLite.eventsFailed()")
                } else {
                    evaluate("window.GEVLite && GEVLite.setEvents(${events.toJson(result)})")
                }
            } finally {
                eventFetchInFlight.set(false)
            }
        }
    }

    /** Fetches current conditions for the camera cities from Open-Meteo. */
    private fun loadWeather() {
        if (io.isShutdown || !weatherFetchInFlight.compareAndSet(false, true)) return
        io.execute {
            try {
                val result = runCatching { weather.load() }.getOrNull()
                if (result.isNullOrEmpty()) {
                    evaluate("window.GEVLite && GEVLite.weatherFailed && GEVLite.weatherFailed()")
                } else {
                    evaluate("window.GEVLite && GEVLite.setWeather(${weather.toJson(result)})")
                }
            } finally {
                weatherFetchInFlight.set(false)
            }
        }
    }

    /** Fetches the photo and route for one tapped plane and pushes each to the globe as it arrives. */
    private fun loadFlightDetails(hex: String, callsign: String, lat: Double, lon: Double) {
        // A hex code is 6 hex digits. Anything else never reaches a URL or script.
        if (io.isShutdown || !Regex("^[0-9a-fA-F]{6}$").matches(hex)) return
        if (!detailsInFlight.add(hex)) return

        val pending = java.util.concurrent.atomic.AtomicInteger(2)
        val done: () -> Unit = {
            if (pending.decrementAndGet() == 0) {
                detailsInFlight.remove(hex)
            }
        }
        io.execute {
            try {
                flights.photoFor(hex)?.let { pushFlightDetail(hex, "photo", it) }
            } finally {
                done()
            }
        }
        io.execute {
            try {
                flights.routeFor(callsign, lat, lon)?.let { pushFlightDetail(hex, "route", it) }
            } finally {
                done()
            }
        }
    }

    private fun pushFlightDetail(hex: String, key: String, value: org.json.JSONObject) {
        evaluate("window.GEVLite && GEVLite.setFlightDetails('$hex', {$key: $value})")
    }

    /** Sends per-pack labels and required attributions. */
    private fun pushPacks() {
        val array = JSONArray()
        for (pack in CctvRepository.PACKS) {
            array.put(
                JSONObject()
                    .put("key", pack.key)
                    .put("label", pack.label)
                    .put("credit", pack.credit),
            )
        }
        evaluate("window.GEVLite && GEVLite.setPacks($array)")
    }

    private fun pushToken() {
        evaluate("window.GEVLite && GEVLite.setIonToken(${JSONObject.quote(ionToken)})")
    }

    private fun evaluate(script: String) {
        main.post { if (!isFinishing) webView.evaluateJavascript(script, null) }
    }

    private fun openCamera(id: String) {
        val camera = cameras[id] ?: return
        selected = camera
        cameraTitle.text = camera.name
        cameraCredit.text = camera.credit
        cameraPanel.visibility = View.VISIBLE
        showMedia(camera)
    }

    /** Shows the clip when the feed publishes one, otherwise the still. */
    private fun showMedia(camera: CctvCamera) {
        stopClip()
        clearStillRefresh()
        frameImage.setImageDrawable(null)
        val clip = camera.videoUrl
        if (clip == null) {
            frameVideo.visibility = View.GONE
            frameImage.visibility = View.VISIBLE
            loadFrame(camera)
            return
        }

        // Keep the publisher's still under the video while the stream negotiates.
        // Caltrans' HLS host can take half a minute to answer, and a black panel
        // in the meantime looked like the camera was dead.
        frameVideo.visibility = View.GONE
        if (camera.imageUrl.isNotBlank()) {
            frameImage.visibility = View.VISIBLE
            loadFrame(camera)
        } else {
            frameImage.visibility = View.GONE
        }
        playClip(clip, camera)
    }

    /**
     * Plays a live HLS clip, muted.
     *
     * ExoPlayer + TextureView rather than VideoView: VideoView renders into a
     * SurfaceView, which does not composite correctly in the same window as a
     * hardware-accelerated WebView — the clip decoded (the codec logged frames)
     * but the panel showed nothing but its background colour. A TextureView is
     * an ordinary view, so it composites with the rest of the layout.
     *
     * If the clip cannot be played, this falls back to the still frame so the
     * panel never ends up empty.
     */
    private fun playClip(url: String, camera: CctvCamera) {
        releasePlayer()
        cameraCredit.text = "${camera.credit}  \u00b7  ${getString(R.string.frame_loading)}"
        // ExoPlayer attaches its own TextureView listener and waits for the
        // surface, so this is safe even while the panel is still being laid out.
        startClip(url, camera)
    }

    private fun startClip(url: String, camera: CctvCamera) {
        val player = ExoPlayer.Builder(this).build()
        clipPlayer = player
        val isCaltrans = url.startsWith("https://wzmedia.dot.ca.gov/")
        val fallbackDelay = if (isCaltrans) CALTRANS_CLIP_TIMEOUT_MS else 6_000L
        val timeoutRunnable = Runnable {
            if (clipPlayer == player && selected?.id == camera.id && !player.isPlaying && player.playbackState != Player.STATE_READY) {
                Log.w(TAG, "clip playback timed out, falling back to still image")
                showStill(camera)
            }
        }
        try {
            player.setVideoTextureView(frameVideo)
            player.volume = 0f
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && selected?.id == camera.id) {
                        main.removeCallbacks(timeoutRunnable)
                        cameraCredit.text = camera.credit
                        // The still stays as the poster until the first frame
                        // is decoded, so there is never a black gap.
                        frameVideo.visibility = View.VISIBLE
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && selected?.id == camera.id) {
                        main.removeCallbacks(timeoutRunnable)
                        cameraCredit.text = camera.credit
                        frameVideo.visibility = View.VISIBLE
                    }
                }

                override fun onRenderedFirstFrame() {
                    if (selected?.id == camera.id) {
                        clearStillRefresh()
                        frameImage.visibility = View.GONE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    main.removeCallbacks(timeoutRunnable)
                    Log.w(TAG, "clip failed: ${error.errorCodeName}")
                    if (selected?.id == camera.id) showStill(camera)
                }
            })
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(BROWSER_MEDIA_USER_AGENT)
                .setConnectTimeoutMs(if (isCaltrans) CALTRANS_CONNECT_TIMEOUT_MS else 6_000)
                .setReadTimeoutMs(if (isCaltrans) CALTRANS_READ_TIMEOUT_MS else 6_000)
                .setAllowCrossProtocolRedirects(true)
            if (url.startsWith("https://video.deldot.gov/")) {
                // Its own map sends a browser identity and referer.
                http.setDefaultRequestProperties(mapOf("Referer" to "https://deldot.gov/map/"))
            }
            // Infers HLS for .m3u8 (DelDOT/Norway) and progressive MP4 for
            // TfL's short clips; forcing HlsMediaSource would break the clips.
            val source = DefaultMediaSourceFactory(http)
                .createMediaSource(MediaItem.fromUri(url))
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
            main.postDelayed(timeoutRunnable, fallbackDelay)
        } catch (error: Exception) {
            main.removeCallbacks(timeoutRunnable)
            Log.w(TAG, "clip failed: ${error.message}")
            showStill(camera)
        }
    }

    /** Falls back to the still image for a feed whose clip will not play. */
    private fun showStill(camera: CctvCamera) {
        releasePlayer()
        clearStillRefresh()
        frameVideo.visibility = View.GONE
        frameImage.visibility = View.VISIBLE
        // DelDOT publishes no still. Without this guard the fallback tried to
        // fetch "", which threw on a worker thread and took the app down.
        if (camera.imageUrl.isBlank()) {
            frameImage.setImageDrawable(null)
            cameraCredit.text = "${camera.credit}  \u00b7  ${getString(R.string.frame_failed)}"
            return
        }
        loadFrame(camera)
    }

    private fun stopClip() {
        releasePlayer()
    }

    /** Cancels the pending auto-refresh of a snapshot frame. */
    private fun clearStillRefresh() {
        stillRefresh?.let { main.removeCallbacks(it) }
        stillRefresh = null
    }

    private fun releasePlayer() {
        clipPlayer?.let { player ->
            runCatching { player.clearVideoTextureView(frameVideo) }
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        clipPlayer = null
    }

    private fun loadFrame(camera: CctvCamera, auto: Boolean = false) {
        clearStillRefresh()
        if (!auto) {
            cameraCredit.text = "${camera.credit}  ·  ${getString(R.string.frame_loading)}"
        }
        io.execute {
            val bytes = repository.fetchFrame(camera.imageUrl)
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            main.post {
                if (isFinishing || selected?.id != camera.id) return@post
                if (bitmap != null) {
                    frameImage.setImageBitmap(bitmap)
                    cameraCredit.text = camera.credit
                } else if (!auto || frameImage.drawable == null) {
                    // A failed refresh keeps the last good frame on screen.
                    frameImage.setImageDrawable(null)
                    cameraCredit.text = "${camera.credit}  ·  ${getString(R.string.frame_failed)}"
                }

                val clipPlaying = camera.videoUrl != null && clipPlayer?.isPlaying == true
                if (cameraPanel.visibility != View.VISIBLE || clipPlaying) return@post
                // WSDOT, DriveBC and the other snapshot packs publish stills
                // only. Refreshing while the panel is open is what makes them
                // a live view instead of one frozen frame.
                val next = Runnable {
                    if (!isFinishing && selected?.id == camera.id && cameraPanel.visibility == View.VISIBLE) {
                        loadFrame(camera, auto = true)
                    }
                }
                stillRefresh = next
                main.postDelayed(next, STILL_REFRESH_MS)
            }
        }
    }

    /** The WebView calls into these; they arrive on a background thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun onGlobeReady() {
            main.post {
                globeReady = true
                pushToken()
                pushPacks()
                pushCameras()
                pushServiceWatchAlertsState()
                pushServiceWatchAlertHistory()
                loadSatellites()
            }
        }

        @JavascriptInterface
        fun startServiceWatchAlerts(
            lat: Double,
            lon: Double,
            radiusKm: Double,
            roles: String,
        ) {
            main.post { this@MainActivity.startServiceWatchAlerts(lat, lon, radiusKm, roles) }
        }

        @JavascriptInterface
        fun stopServiceWatchAlerts() {
            main.post { this@MainActivity.stopServiceWatchAlerts() }
        }

        @JavascriptInterface
        fun clearServiceWatchAlertHistory() {
            main.post {
                ServiceWatchAlertStore.clear(this@MainActivity)
                pushServiceWatchAlertHistory()
            }
        }

        @JavascriptInterface
        fun openCamera(id: String) {
            // Must be qualified: an unqualified call here resolves to THIS method
            // (the nearest enclosing declaration), which posts itself again and
            // spins the main thread forever without ever opening the panel.
            main.post { this@MainActivity.openCamera(id) }
        }

        @JavascriptInterface
        fun requestFlights(lat: Double, lon: Double, radiusNm: Double) {
            loadFlights(lat, lon, radiusNm)
        }

        @JavascriptInterface
        fun requestSatellites() {
            loadSatellites()
        }

        @JavascriptInterface
        fun requestEvents() {
            loadEvents()
        }

        @JavascriptInterface
        fun requestWeather() {
            loadWeather()
        }

        @JavascriptInterface
        fun requestFlightDetails(hex: String, callsign: String, lat: Double, lon: Double) {
            loadFlightDetails(hex, callsign, lat, lon)
        }

        /** Opens an allow-listed external attribution/photo page in the browser. */
        @JavascriptInterface
        fun openLink(url: String) {
            val uri = android.net.Uri.parse(url)
            val host = uri.host.orEmpty()
            val allowed = host == "planespotters.net" || host.endsWith(".planespotters.net") ||
                host == "open-meteo.com" || host.endsWith(".open-meteo.com")
            if (uri.scheme != "https" || !allowed) return
            this@MainActivity.runOnUiThread {
                runCatching {
                    this@MainActivity.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, uri),
                    )
                }
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.i(TAG, "globe: $message")
        }
    }

    @Deprecated("Framework Activity back handling")
    override fun onBackPressed() {
        when {
            settingsPanel.visibility == View.VISIBLE -> settingsPanel.visibility = View.GONE
            cameraPanel.visibility == View.VISIBLE -> {
                cameraPanel.visibility = View.GONE
                clearStillRefresh()
                stopClip()
                selected = null
            }
            else -> super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            101 -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                geoCallback?.invoke(geoOrigin, granted, false)
                geoCallback = null
                geoOrigin = null
            }
            REQUEST_NOTIFICATIONS -> {
                val config = pendingWatchAlertConfig
                pendingWatchAlertConfig = null
                val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                if (config != null) {
                    if (granted) {
                        launchServiceWatchAlerts(config)
                    } else {
                        pushServiceWatchAlertsState(false, "DENIED", null)
                    }
                }
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun registerWatchAlertReceiver() {
        if (watchAlertReceiverRegistered) return
        val filter = IntentFilter(ServiceWatchService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(watchAlertStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(watchAlertStateReceiver, filter)
        }
        watchAlertReceiverRegistered = true
    }

    private fun unregisterWatchAlertReceiver() {
        if (!watchAlertReceiverRegistered) return
        runCatching { unregisterReceiver(watchAlertStateReceiver) }
        watchAlertReceiverRegistered = false
    }

    private fun startServiceWatchAlerts(
        lat: Double,
        lon: Double,
        radiusKm: Double,
        rolesText: String,
    ) {
        val roles = ServiceWatchRole.parseCsv(rolesText)
        if (!lat.isFinite() || !lon.isFinite() || !radiusKm.isFinite() || radiusKm <= 0) {
            pushServiceWatchAlertsState(false, "ERROR", null)
            return
        }
        if (roles.isEmpty()) {
            pushServiceWatchAlertsState(false, "PICK ROLE", null)
            return
        }
        val config = ServiceWatchConfig(
            lat = lat,
            lon = lon,
            radiusKm = radiusKm,
            roles = roles,
        )
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingWatchAlertConfig = config
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
            return
        }
        launchServiceWatchAlerts(config)
    }

    private fun launchServiceWatchAlerts(config: ServiceWatchConfig) {
        val intent = Intent(this, ServiceWatchService::class.java).apply {
            action = ServiceWatchService.ACTION_START
            putExtra(ServiceWatchService.EXTRA_LAT, config.lat)
            putExtra(ServiceWatchService.EXTRA_LON, config.lon)
            putExtra(ServiceWatchService.EXTRA_RADIUS_KM, config.radiusKm)
            putExtra(
                ServiceWatchService.EXTRA_ROLES,
                config.roles.joinToString(",") { it.wire },
            )
        }
        try {
            startForegroundService(intent)
            pushServiceWatchAlertsState(true, "", config)
        } catch (error: Exception) {
            Log.w(TAG, "could not start Service Watch alerts: ${error.message}")
            pushServiceWatchAlertsState(false, "ERROR", null)
        }
    }

    private fun stopServiceWatchAlerts() {
        pendingWatchAlertConfig = null
        runCatching { stopService(Intent(this, ServiceWatchService::class.java)) }
        pushServiceWatchAlertsState(false, "", null)
    }

    /** Restores the alert UI from the service after the WebView reloads or the app resumes. */
    private fun pushServiceWatchAlertsState(
        on: Boolean = ServiceWatchService.isRunning,
        message: String = "",
        config: ServiceWatchConfig? = ServiceWatchService.activeConfig,
    ) {
        // onCreate marks the service running just before onStartCommand stores its
        // config. The start broadcast will follow; do not flip the UI off in between.
        if (on && config == null && ServiceWatchService.isRunning) return
        val activeConfig = config.takeIf { on }
        val configJson = if (activeConfig == null) {
            "null"
        } else {
            JSONObject()
                .put("lat", activeConfig.lat)
                .put("lon", activeConfig.lon)
                .put("radiusKm", activeConfig.radiusKm)
                .put("roles", JSONArray(activeConfig.roles.map { it.wire }))
                .toString()
        }
        val enabled = activeConfig != null
        evaluate(
            "window.GEVLite && typeof GEVLite.setServiceWatchAlertsState === 'function' " +
                "&& GEVLite.setServiceWatchAlertsState($enabled, " +
                "${JSONObject.quote(message)}, $configJson)"
        )
    }

    private fun pushServiceWatchAlertHistory() {
        val history = ServiceWatchAlertStore.load(this)
        evaluate(
            "window.GEVLite && typeof GEVLite.setServiceWatchAlertHistory === 'function' " +
                "&& GEVLite.setServiceWatchAlertHistory(${ServiceWatchAlertStore.encode(history)})"
        )
    }

    override fun onPause() {
        ServiceWatchService.appVisible = false
        unregisterWatchAlertReceiver()
        super.onPause()
        webView.onPause()
        clearStillRefresh()
        // Do not keep streaming a clip (or holding the decoder) in the background.
        clipPlayer?.let { player -> runCatching { if (player.isPlaying) player.pause() } }
    }

    override fun onResume() {
        super.onResume()
        ServiceWatchService.appVisible = true
        registerWatchAlertReceiver()
        webView.onResume()
        pushServiceWatchAlertsState()
        pushServiceWatchAlertHistory()
        if (cameraPanel.visibility == View.VISIBLE) {
            selected?.let { camera -> showMedia(camera) }
        }
    }

    override fun onDestroy() {
        ServiceWatchService.appVisible = false
        unregisterWatchAlertReceiver()
        pendingWatchAlertConfig = null
        clearStillRefresh()
        stopClip()
        io.shutdownNow()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
