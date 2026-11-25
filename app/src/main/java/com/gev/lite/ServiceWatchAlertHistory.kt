package com.gev.lite

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class ServiceWatchAlertRecord(
    val atMs: Long,
    val hex: String,
    val identity: String,
    val role: ServiceWatchRole,
    val distanceM: Double,
)

/**
 * A small local history of the background alerts that were actually posted.
 *
 * It lives in SharedPreferences rather than the WebView so it survives the app
 * being backgrounded or reloaded while the service is doing the polling.
 */
internal object ServiceWatchAlertStore {

    private const val PREFS = "service_watch_history"
    private const val KEY = "alerts"
    const val MAX_ENTRIES = 30

    fun load(context: Context): List<ServiceWatchAlertRecord> =
        decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null),
        )

    fun append(
        context: Context,
        added: List<ServiceWatchAlertRecord>,
    ): List<ServiceWatchAlertRecord> {
        if (added.isEmpty()) return load(context)
        val merged = merge(load(context), added)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, encode(merged))
            .apply()
        return merged
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }

    fun merge(
        existing: List<ServiceWatchAlertRecord>,
        added: List<ServiceWatchAlertRecord>,
    ): List<ServiceWatchAlertRecord> =
        (added + existing).sortedByDescending { it.atMs }.take(MAX_ENTRIES)

    fun encode(records: List<ServiceWatchAlertRecord>): String {
        val array = JSONArray()
        for (record in records) {
            array.put(
                JSONObject()
                    .put("atMs", record.atMs)
                    .put("hex", record.hex)
                    .put("identity", record.identity)
                    .put("role", record.role.wire)
                    .put("distanceM", record.distanceM),
            )
        }
        return array.toString()
    }

    fun decode(text: String?): List<ServiceWatchAlertRecord> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(text)
            val out = ArrayList<ServiceWatchAlertRecord>(minOf(array.length(), MAX_ENTRIES))
            for (i in 0 until minOf(array.length(), MAX_ENTRIES)) {
                val item = array.optJSONObject(i) ?: continue
                val hex = item.optString("hex", "").trim()
                val identity = item.optString("identity", "").trim()
                val atMs = item.optLong("atMs", 0L)
                val distanceM = item.optDouble("distanceM", Double.NaN)
                if (hex.isEmpty() || atMs <= 0L || !distanceM.isFinite()) continue
                out += ServiceWatchAlertRecord(
                    atMs = atMs,
                    hex = hex,
                    identity = identity.ifEmpty { hex.uppercase() },
                    role = ServiceWatchRole.fromWire(item.optString("role", ""))
                        ?: ServiceWatchRole.OTHER,
                    distanceM = distanceM,
                )
            }
            out.sortedByDescending { it.atMs }
        }.getOrDefault(emptyList())
    }
}
