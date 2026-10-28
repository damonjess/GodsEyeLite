package com.gev.lite

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
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

        /**
         * Served by WebViewAssetLoader. A real https origin (rather than
         * file://) is what gives the page a secure, stable origin, so Cesium's
         * blob-backed web workers work and the Cesium ion token can be scoped
         * to this exact origin.
         */
        const val APP_ORIGIN = "https://appassets.androidplatform.net"
        const val START_URL = "$APP_ORIGIN/assets/web/index.html"
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
    private val io: ExecutorService = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    /** Cameras by id, so a tap in the globe can be resolved back to a frame URL. */
    private val cameras = LinkedHashMap<String, CctvCamera>()
    private var selected: CctvCamera? = null
    private var globeReady = false
    private var clipPlayer: MediaPlayer? = null
    private var pendingClipUrl: String? = null
    private var pendingCamera: CctvCamera? = null

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
        frameVideo.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                // A clip requested before the texture existed starts now.
                val url = pendingClipUrl ?: return
                val camera = pendingCamera ?: return
                startClip(url, camera, texture)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
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
                    .put("imageUrl", camera.imageUrl),
            )
        }
        evaluate("window.GEVLite && GEVLite.setCameras($array)")
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
        val clip = camera.videoUrl
        if (clip == null) {
            stopClip()
            frameVideo.visibility = View.GONE
            frameImage.visibility = View.VISIBLE
            frameImage.setImageDrawable(null)
            loadFrame(camera)
            return
        }

        stopClip()
        frameImage.setImageDrawable(null)
        frameImage.visibility = View.GONE
        frameVideo.visibility = View.VISIBLE
        playClip(clip, camera)
    }

    /**
     * Plays a TfL jamcam clip, looping and muted.
     *
     * MediaPlayer + TextureView rather than VideoView: VideoView renders into a
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
        pendingClipUrl = url
        pendingCamera = camera
        cameraCredit.text = "${camera.credit}  \u00b7  ${getString(R.string.frame_loading)}"

        val texture = frameVideo.surfaceTexture
        if (texture != null) startClip(url, camera, texture)
        // Otherwise onSurfaceTextureAvailable starts it.
    }

    private fun startClip(url: String, camera: CctvCamera, texture: SurfaceTexture) {
        val player = MediaPlayer()
        clipPlayer = player
        try {
            player.setSurface(Surface(texture))
            player.isLooping = true
            player.setVolume(0f, 0f)
            player.setOnPreparedListener {
                cameraCredit.text = camera.credit
                if (selected?.id == camera.id) it.start()
            }
            player.setOnErrorListener { _, _, _ ->
                showStill(camera)
                true
            }
            player.setDataSource(url)
            player.prepareAsync()
        } catch (error: Exception) {
            Log.w(TAG, "clip failed: ${error.message}")
            showStill(camera)
        }
    }

    /** Falls back to the still image for a feed whose clip will not play. */
    private fun showStill(camera: CctvCamera) {
        releasePlayer()
        frameVideo.visibility = View.GONE
        frameImage.visibility = View.VISIBLE
        loadFrame(camera)
    }

    private fun stopClip() {
        pendingClipUrl = null
        pendingCamera = null
        releasePlayer()
    }

    private fun releasePlayer() {
        clipPlayer?.let { player ->
            runCatching { player.setOnPreparedListener(null) }
            runCatching { player.setOnErrorListener(null) }
            runCatching { player.stop() }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
        clipPlayer = null
    }

    private fun loadFrame(camera: CctvCamera) {
        cameraCredit.text = "${camera.credit}  ·  ${getString(R.string.frame_loading)}"
        io.execute {
            val bytes = repository.fetchFrame(camera.imageUrl)
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            main.post {
                if (isFinishing || selected?.id != camera.id) return@post
                if (bitmap != null) {
                    frameImage.setImageBitmap(bitmap)
                    cameraCredit.text = camera.credit
                } else {
                    frameImage.setImageDrawable(null)
                    cameraCredit.text = "${camera.credit}  ·  ${getString(R.string.frame_failed)}"
                }
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
                stopClip()
                selected = null
            }
            else -> super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        // Do not keep streaming a clip (or holding the decoder) in the background.
        clipPlayer?.let { player ->
            if (runCatching { player.isPlaying }.getOrDefault(false)) {
                runCatching { player.pause() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (cameraPanel.visibility == View.VISIBLE) {
            selected?.let { camera ->
                if (camera.videoUrl != null) playClip(camera.videoUrl, camera)
            }
        }
    }

    override fun onDestroy() {
        stopClip()
        io.shutdownNow()
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
