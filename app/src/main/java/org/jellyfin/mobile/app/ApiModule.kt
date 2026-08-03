package org.jellyfin.mobile.app

import okhttp3.OkHttpClient
import org.jellyfin.mobile.utils.CfAccessInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val apiModule = module {
    // Jellyfin API builder and API client instance
    single {
        createJellyfin {
            context = androidContext()
            clientInfo = ClientInfo(name = Constants.APP_INFO_NAME, version = Constants.APP_INFO_VERSION)
            // Inject an OkHttp client with the Cloudflare Access interceptor so every
            // SDK API call to a *.ffilt.us host carries the service-token headers.
            apiClientFactory = OkHttpFactory(
                base = OkHttpClient.Builder()
                    .addInterceptor(CfAccessInterceptor())
                    .build(),
            )
        }
    }
    single { get<Jellyfin>().createApi() }
}
