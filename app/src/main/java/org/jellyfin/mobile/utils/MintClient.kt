package org.jellyfin.mobile.utils

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Runtime token minting client (app-secret-minting.md standard, 2026-08-03).
 *
 * On first run while on the home LAN, the app POSTs {app, device_id} to
 * mint.homelab.internal (LAN-only, never tunneled) and receives a per-device
 * Cloudflare Access service-token pair, stored encrypted in [CfTokenStore].
 * The APK contains NO token at build time — a dump yields nothing.
 *
 * mint.homelab.internal only resolves on the LAN (split-DNS); away from home the
 * call fails fast and the app keeps working with any previously-minted token
 * (or without one — the user must be home once to initialize).
 */
class MintClient(
    private val context: Context,
    private val tokenStore: CfTokenStore,
    private val httpClient: OkHttpClient,
) {
    private val mintUrl = "http://mint.homelab.internal/mint"
    private val appName = "jellyfin"

    /** Returns true if a token was minted (new) — false if already present or unreachable. */
    fun ensureToken(): Boolean {
        if (tokenStore.load() != null) return false
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown-device"
        val json = JSONObject()
            .put("app", appName)
            .put("device_id", deviceId)
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(mintUrl)
            .post(body)
            .build()
        return try {
            val client = httpClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val obj = JSONObject(resp.body?.string().orEmpty())
                val clientId = obj.optString("client_id")
                val clientSecret = obj.optString("client_secret")
                if (clientId.isNotEmpty() && clientSecret.isNotEmpty()) {
                    tokenStore.store(clientId, clientSecret)
                    true
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            false // LAN-only — unreachable when away; not an error
        }
    }

    /** Fire-and-forget mint attempt on app start (must be on the LAN). */
    fun tryMintInBackground() {
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.IO) { ensureToken() }
        }
    }
}
