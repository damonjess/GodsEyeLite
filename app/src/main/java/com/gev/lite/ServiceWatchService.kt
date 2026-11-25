package com.gev.lite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Opt-in background Service Watch.
 *
 * While the app is visible the WebView already refreshes Service Watch, so this
 * service deliberately does no network work. Once the app goes to the
 * background it polls the fixed ME location/radius and posts one notification
 * when a service aircraft that was not previously in the area appears.
 */
class ServiceWatchService : Service() {

    companion object {
        const val ACTION_START = "com.gev.lite.action.START_SERVICE_WATCH"
        const val ACTION_STOP = "com.gev.lite.action.STOP_SERVICE_WATCH"
        const val ACTION_STATE_CHANGED = "com.gev.lite.action.SERVICE_WATCH_STATE"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_RADIUS_KM = "radius_km"
        const val EXTRA_ROLES = "roles"

        private const val TAG = "ServiceWatchService"
        private const val CHANNEL_WATCH = "service_watch"
        private const val CHANNEL_ALERTS = "service_watch_alerts"
        private const val NOTIFICATION_WATCH = 9901
        private const val NOTIFICATION_ALERT = 9902
        private const val POLL_MS = 30_000L
        private const val VISIBLE_RECHECK_MS = 10_000L
        private const val MIN_RADIUS_KM = 1.0
        private const val MAX_RADIUS_KM = 100.0

        /** The Activity reads these to restore the alert UI after a resume. */
        @Volatile
        var isRunning = false
            private set

        @Volatile
        internal var activeConfig: ServiceWatchConfig? = null
            private set

        /** MainActivity sets this so the service does not duplicate foreground polling. */
        @Volatile
        var appVisible = false
    }

    private val repository = FlightRepository()
    private val monitor = ServiceWatchMonitor()
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val polling = AtomicBoolean(false)
    private val pollRunnable = Runnable { poll() }

    private var config: ServiceWatchConfig? = null
    private var pollScheduled = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWatching()
            return START_NOT_STICKY
        }

        val lat = intent?.getDoubleExtra(EXTRA_LAT, Double.NaN) ?: Double.NaN
        val lon = intent?.getDoubleExtra(EXTRA_LON, Double.NaN) ?: Double.NaN
        val radiusKm = intent?.getDoubleExtra(EXTRA_RADIUS_KM, Double.NaN) ?: Double.NaN
        val rolesText = intent?.getStringExtra(EXTRA_ROLES)
        val roles = if (rolesText == null) {
            ServiceWatchRole.all()
        } else {
            ServiceWatchRole.parseCsv(rolesText)
        }
        if (!lat.isFinite() || !lon.isFinite() || !radiusKm.isFinite() || roles.isEmpty()) {
            stopWatching()
            return START_NOT_STICKY
        }

        val next = ServiceWatchConfig(
            lat = lat,
            lon = lon,
            radiusKm = radiusKm.coerceIn(MIN_RADIUS_KM, MAX_RADIUS_KM),
            roles = roles,
        )
        if (next != config) {
            config = next
            monitor.reset()
        }
        activeConfig = next
        startForegroundCompat(buildWatchingNotification(next))
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        Log.i(
            TAG,
            "watching ${next.radiusKm.roundToInt()} km roles=" +
                next.roles.joinToString(",") { it.wire },
        )

        handler.removeCallbacks(pollRunnable)
        pollScheduled = false
        schedulePoll(0)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        activeConfig = null
        config = null
        handler.removeCallbacks(pollRunnable)
        pollScheduled = false
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun stopWatching() {
        isRunning = false
        activeConfig = null
        config = null
        handler.removeCallbacks(pollRunnable)
        pollScheduled = false
        executor.shutdownNow()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun schedulePoll(delayMs: Long) {
        if (!isRunning || pollScheduled) return
        pollScheduled = true
        handler.postDelayed(pollRunnable, delayMs)
    }

    private fun poll() {
        pollScheduled = false
        if (!isRunning) return
        val current = config ?: return
        if (appVisible) {
            schedulePoll(VISIBLE_RECHECK_MS)
            return
        }
        if (!polling.compareAndSet(false, true)) {
            schedulePoll(1_000L)
            return
        }

        executor.execute {
            val radiusNm = ceil(current.radiusKm / 1.852).toInt().coerceIn(5, 400)
            val fetched = runCatching {
                repository.fetchAround(current.lat, current.lon, radiusNm)
            }.onFailure {
                Log.w(TAG, "poll failed: ${it.message}")
            }.getOrNull()
            val enriched = fetched?.let {
                runCatching { repository.enrichEmergencyServices(it) }
                    .getOrDefault(it)
            }

            handler.post {
                polling.set(false)
                if (!isRunning || current != config) return@post
                if (appVisible) {
                    schedulePoll(VISIBLE_RECHECK_MS)
                    return@post
                }
                if (enriched != null) {
                    val update = monitor.update(enriched, current)
                    Log.i(TAG, "poll nearby=${update.nearby.size} new=${update.newAircraft.size}")
                    if (update.newAircraft.isNotEmpty()) {
                        recordAlertHistory(update.newAircraft, current)
                        postAlertNotification(update.newAircraft, current)
                    }
                }
                schedulePoll(POLL_MS)
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_WATCH,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_WATCH, notification)
        }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val watch = NotificationChannel(
            CHANNEL_WATCH,
            "Service Watch",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent status while Service Watch alerts are active"
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            "Service Watch alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "New emergency or service aircraft near the watched location"
        }
        manager.createNotificationChannels(listOf(watch, alerts))
    }

    private fun buildWatchingNotification(current: ServiceWatchConfig): Notification {
        val radius = current.radiusKm.roundToInt()
        return Notification.Builder(this, CHANNEL_WATCH)
            .setSmallIcon(R.drawable.ic_service_watch)
            .setColor(0xFF35E0C8.toInt())
            .setContentTitle("Service Watch active")
            .setContentText("Watching $radius km around your location")
            .setContentIntent(contentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "STOP",
                stopPendingIntent(),
            )
            .build()
    }

    private fun recordAlertHistory(newAircraft: List<Flight>, current: ServiceWatchConfig) {
        val atMs = System.currentTimeMillis()
        val records = newAircraft.map { flight ->
            ServiceWatchAlertRecord(
                atMs = atMs,
                hex = flight.hex,
                identity = identityOf(flight),
                role = ServiceWatchRole.ofReason(flight.emergencyReason),
                distanceM = ServiceWatchMonitor.distanceM(
                    current.lat,
                    current.lon,
                    flight.lat,
                    flight.lon,
                ),
            )
        }
        ServiceWatchAlertStore.append(this, records)
    }

    private fun identityOf(flight: Flight): String = flight.callsign.ifBlank {
        flight.registration.ifBlank { flight.hex.uppercase(Locale.US) }
    }

    private fun postAlertNotification(newAircraft: List<Flight>, current: ServiceWatchConfig) {
        val nearest = newAircraft.first()
        val identity = identityOf(nearest)
        val distanceM = ServiceWatchMonitor.distanceM(
            current.lat,
            current.lon,
            nearest.lat,
            nearest.lon,
        )
        val distance = if (distanceM < 1_000) {
            "${distanceM.roundToInt()} m"
        } else {
            String.format(Locale.US, "%.1f km", distanceM / 1_000)
        }
        val direction = if (distanceM < 100) {
            ""
        } else {
            " " + ServiceWatchMonitor.bearing(
                current.lat,
                current.lon,
                nearest.lat,
                nearest.lon,
            )
        }
        val more = if (newAircraft.size > 1) "  ·  +${newAircraft.size - 1} more" else ""
        val text = "$distance$direction within ${current.radiusKm.roundToInt()} km$more"

        val notification = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_service_watch)
            .setColor(0xFFFF5A4F.toInt())
            .setContentTitle(
                ServiceWatchMonitor.serviceLabel(nearest.emergencyReason) +
                    " nearby: " + identity,
            )
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ALERT, notification)
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, ServiceWatchService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
