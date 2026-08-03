package org.jellyfin.mobile.utils

import okhttp3.Interceptor
import okhttp3.Response
import org.jellyfin.mobile.BuildConfig

/**
 * Adds Cloudflare Access service-token headers (CF-Access-Client-Id / CF-Access-Client-Secret)
 * to requests headed to a *.ffilt.us host. This lets the app reach the Jellyfin server through
 * the Cloudflare Access gateway (e.g. https://jelly.ffilt.us) without an interactive login —
 * the APK itself carries the credential.
 *
 * Headers are only added for hosts under ffilt.us so the token is never leaked to arbitrary
 * LAN IPs or third-party servers the user may configure. When the build has no token
 * (debug / unprovisioned builds) this is a no-op.
 */
class CfAccessInterceptor : Interceptor {
    private val clientId: String = BuildConfig.CF_ACCESS_CLIENT_ID
    private val clientSecret: String = BuildConfig.CF_ACCESS_CLIENT_SECRET

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (clientId.isNotEmpty() && clientSecret.isNotEmpty() && isCloudflareProtected(request.url.host)) {
            val authenticatedRequest = request.newBuilder()
                .header("CF-Access-Client-Id", clientId)
                .header("CF-Access-Client-Secret", clientSecret)
                .build()
            return chain.proceed(authenticatedRequest)
        }

        return chain.proceed(request)
    }

    companion object {
        private const val CF_DOMAIN_SUFFIX = ".ffilt.us"

        fun isCloudflareProtected(host: String): Boolean = host.endsWith(CF_DOMAIN_SUFFIX)
    }
}
