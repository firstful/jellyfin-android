package org.jellyfin.mobile.utils

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds Cloudflare Access service-token headers (CF-Access-Client-Id / CF-Access-Client-Secret)
 * to requests headed to a *.ffilt.us host. This lets the app reach the Jellyfin server through
 * the Cloudflare Access gateway (e.g. https://jelly.ffilt.us) without an interactive login.
 *
 * The token pair is NOT baked into the APK — it is minted at runtime from the LAN-only
 * mint service (mint.homelab.internal) on first run and stored encrypted in [CfTokenStore]
 * (app-secret-minting.md standard). When no token is present (never initialized / away from
 * home) this is a no-op and the app relies on the LAN split-DNS bypass or Access login.
 *
 * Headers are only added for hosts under ffilt.us so the token is never leaked to arbitrary
 * LAN IPs or third-party servers the user may configure.
 */
class CfAccessInterceptor(private val tokenStore: CfTokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (isCloudflareProtected(request.url.host)) {
            tokenStore.load()?.let { (clientId, clientSecret) ->
                val authenticatedRequest = request.newBuilder()
                    .header("CF-Access-Client-Id", clientId)
                    .header("CF-Access-Client-Secret", clientSecret)
                    .build()
                return chain.proceed(authenticatedRequest)
            }
        }

        return chain.proceed(request)
    }

    companion object {
        private const val CF_DOMAIN_SUFFIX = ".ffilt.us"

        fun isCloudflareProtected(host: String): Boolean = host.endsWith(CF_DOMAIN_SUFFIX)
    }
}
