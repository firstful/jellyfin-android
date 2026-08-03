package org.jellyfin.mobile.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.mobile.BuildConfig
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal fire-and-forget telemetry client for the Sutliff homelab.
 *
 * POSTs a raw string body to the telemetry ingest endpoint (telemetry.ffilt.us).
 * No auth needed — the endpoint is public by design (see ops/telemetry.md).
 * Errors are logged and swallowed; telemetry must never affect the app.
 *
 * Rate limiting: the ingest allows 60 req/min per client. App events are rare
 * (launch, server connect, playback start) so a simple min-interval throttle
 * is enough to stay well under the cap.
 */
object Telemetry {
    private const val MIN_INTERVAL_MS = 2000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastSent = AtomicLong(0)
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Send one telemetry event. Body is a raw string, e.g.
     * "jellyfin-android 2.0.0-sutliff.1 app_launch".
     * Throttled to [MIN_INTERVAL_MS] between sends; drops are silent.
     */
    fun send(event: String) {
        val url = BuildConfig.TELEMETRY_URL
        if (url.isBlank()) return // unprovisioned/debug build

        val now = System.currentTimeMillis()
        val last = lastSent.get()
        if (now - last < MIN_INTERVAL_MS) return
        if (!lastSent.compareAndSet(last, now)) return

        scope.launch {
            try {
                val body = event.toRequestBody("text/plain".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Timber.w("Telemetry send returned %d", response.code)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Telemetry send failed")
            }
        }
    }
}
