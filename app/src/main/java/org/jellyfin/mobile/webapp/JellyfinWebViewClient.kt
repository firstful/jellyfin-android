package org.jellyfin.mobile.webapp

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.utils.CfAccessInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.initLocale
import org.jellyfin.mobile.utils.inject
import org.jellyfin.sdk.model.serializer.toUUID
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.Reader
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

abstract class JellyfinWebViewClient(
    private val coroutineScope: CoroutineScope,
    private val server: ServerEntity,
    private val assetsPathHandler: AssetsPathHandler,
    private val mainViewModel: MainViewModel,
    private val okHttpClient: OkHttpClient,
) : WebViewClientCompat() {

    abstract fun onConnectedToWebapp()

    abstract fun onErrorReceived()

    override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val path = url.path?.lowercase(Locale.ROOT) ?: return null
        return when {
            path.matches(Constants.MAIN_BUNDLE_PATH_REGEX) && "deferred" !in url.query.orEmpty() -> {
                onConnectedToWebapp()
                assetsPathHandler.inject("native/injectionScript.js")
            }
            // Load injected scripts from application assets
            path.contains("/native/") -> assetsPathHandler.inject("native/${url.lastPathSegment}")
            // Load the chrome.cast.js library instead
            path.endsWith(Constants.CAST_SDK_PATH) -> assetsPathHandler.inject("native/chrome.cast.js")
            path.endsWith(Constants.SESSION_CAPABILITIES_PATH) -> {
                coroutineScope.launch {
                    val credentials = suspendCoroutine { continuation ->
                        webView.evaluateJavascript(
                            "JSON.parse(window.localStorage.getItem('jellyfin_credentials'))",
                        ) { result ->
                            try {
                                continuation.resume(JSONObject(result))
                            } catch (e: JSONException) {
                                val message = "Failed to extract credentials"
                                Timber.e(e, message)
                                continuation.resumeWithException(Exception(message, e))
                            }
                        }
                    }
                    val storedServer = credentials.getJSONArray("Servers").getJSONObject(0)
                    val user = storedServer.getString("UserId").toUUID()
                    val token = storedServer.getString("AccessToken")
                    mainViewModel.setupUser(server.id, user, token)
                    webView.initLocale(user)
                }
                null
            }
            // Cloudflare Access: re-issue requests to *.ffilt.us hosts through the
            // OkHttp client that carries the CF-Access service-token headers. The
            // webapp UI + its API XHRs go through the WebView network stack, so this
            // is required for external access behind CF Access.
            CfAccessInterceptor.isCloudflareProtected(url.host.orEmpty()) -> {
                fetchWithCfHeaders(request)
            }
            else -> null
        }
    }

    private fun fetchWithCfHeaders(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val method = request.method
        val requestHeaders = request.requestHeaders

        // Only GET/HEAD can be re-issued through OkHttp — WebResourceRequest has no
        // request-body accessor in modern API levels, so POST bodies can't be
        // forwarded. POST requests are covered by the injected JS shim that adds
        // the CF headers to fetch/XHR calls (see injectionScript.js).
        if (method != "GET" && method != "HEAD") return null

        val builder = Request.Builder()
            .url(url)
            .apply {
                requestHeaders.forEach { (name, value) -> header(name, value) }
            }
            .method(method, null)

        return try {
            val response = okHttpClient.newCall(builder.build()).execute()
            val body = response.body ?: return null
            val contentType = body.contentType()
            val mimeType = contentType?.type + "/" + contentType?.subtype
            val encoding = contentType?.charset()?.name()

            WebResourceResponse(
                mimeType,
                encoding,
                response.code,
                response.message,
                response.headers.toMultimap().mapValues { it.value.joinToString(",") },
                body.byteStream(),
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to re-issue WebView request through CF client: %s", url)
            null
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        val errorMessage = errorResponse.data?.run { bufferedReader().use(Reader::readText) }
        Timber.e("Received WebView HTTP %d error: %s", errorResponse.statusCode, errorMessage)

        if (request.isForMainFrame) onErrorReceived()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceErrorCompat,
    ) {
        val description = when {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION) -> error.description
            else -> null
        }
        val errorCode = when {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_CODE) -> error.errorCode
            else -> ERROR_UNKNOWN
        }
        Timber.e("Received WebView error %d at %s: %s", errorCode, request.url.toString(), description)

        // Abort on some specific error codes or when the request url matches the server url
        if (request.isForMainFrame) onErrorReceived()
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        Timber.e("Received SSL error: %s", error.toString())
        handler.cancel()
    }
}
