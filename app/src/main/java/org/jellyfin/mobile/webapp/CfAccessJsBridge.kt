package org.jellyfin.mobile.webapp

import android.webkit.JavascriptInterface
import org.jellyfin.mobile.BuildConfig

/**
 * Exposes the Cloudflare Access service-token pair to the injected webapp JS so
 * fetch/XHR requests can carry the CF-Access headers. WebView has no API to add
 * headers to subresource requests (and WebResourceRequest#getRequestBody doesn't
 * exist in modern API levels), so the injection script patches fetch + XHR and
 * reads the values from this bridge.
 */
class CfAccessJsBridge {
    @JavascriptInterface
    fun getClientId(): String = BuildConfig.CF_ACCESS_CLIENT_ID

    @JavascriptInterface
    fun getClientSecret(): String = BuildConfig.CF_ACCESS_CLIENT_SECRET

    @JavascriptInterface
    fun isEnabled(): Boolean = BuildConfig.CF_ACCESS_CLIENT_ID.isNotEmpty() && BuildConfig.CF_ACCESS_CLIENT_SECRET.isNotEmpty()
}
