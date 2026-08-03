package org.jellyfin.mobile.webapp

import android.webkit.JavascriptInterface
import org.jellyfin.mobile.utils.CfTokenStore

/**
 * Exposes the minted Cloudflare Access service-token pair to the injected webapp JS so
 * fetch/XHR requests can carry the CF-Access headers. WebView has no API to add headers
 * to subresource requests (and WebResourceRequest#getRequestBody doesn't exist in modern
 * API levels), so the injection script patches fetch + XHR and reads the values from this
 * bridge. Token comes from the runtime-minted [CfTokenStore] — never from BuildConfig.
 */
class CfAccessJsBridge(private val tokenStore: CfTokenStore) {
    @JavascriptInterface
    fun getClientId(): String = tokenStore.load()?.first ?: ""

    @JavascriptInterface
    fun getClientSecret(): String = tokenStore.load()?.second ?: ""

    @JavascriptInterface
    fun isEnabled(): Boolean = tokenStore.load() != null
}
