package com.github.damontecres.wholphin.services.hilt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.work.WorkManager
import com.github.damontecres.wholphin.BuildConfig
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.ReleaseCompanionFeature
import com.github.damontecres.wholphin.services.SeerrApi
import com.github.damontecres.wholphin.services.release.CompanionOriginPolicy
import com.github.damontecres.wholphin.services.release.DefaultReleaseCompanionApi
import com.github.damontecres.wholphin.services.release.DefaultReleaseCompanionRepository
import com.github.damontecres.wholphin.services.release.JellyfinCredential
import com.github.damontecres.wholphin.services.release.JellyfinCredentialProvider
import com.github.damontecres.wholphin.services.release.OkHttpReleaseCompanionTransport
import com.github.damontecres.wholphin.services.release.ReleaseCompanionException
import com.github.damontecres.wholphin.services.release.ReleaseHttpMethod
import com.github.damontecres.wholphin.services.release.ReleaseTransportRequest
import com.github.damontecres.wholphin.services.release.SensitiveValue
import com.github.damontecres.wholphin.util.CoroutineContextApiClientFactory
import com.github.damontecres.wholphin.util.WholphinDispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * An [OkHttpClient] that includes the user's access token when making requests
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthOkHttpClient

/**
 * A basic [OkHttpClient] that does not include auth
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StandardOkHttpClient

/**
 * A dedicated client for the release companion. It never contains Jellyfin authentication
 * interceptors; credentials are attached only to the origin-locked session exchange request.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ReleaseCompanionOkHttpClient

/**
 * A [CoroutineScope] with [WholphinDispatchers.IO]
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoCoroutineScope

/**
 * A [CoroutineScope] with [WholphinDispatchers.Default]
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultCoroutineScope

/**
 * [WholphinDispatchers.IO]
 *
 * @see IoCoroutineScope
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * [WholphinDispatchers.Default]
 *
 * @see DefaultCoroutineScope
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun clientInfo(
        @ApplicationContext context: Context,
    ): ClientInfo =
        ClientInfo(
            name = context.getString(R.string.app_name),
            version = BuildConfig.VERSION_NAME,
        )

    @StandardOkHttpClient
    @Provides
    @Singleton
    fun okHttpClient() =
        OkHttpClient
            .Builder()
            .apply {
                // TODO user agent, timeouts, logging, etc
            }.build()

    @AuthOkHttpClient
    @Provides
    @Singleton
    fun authOkHttpClient(
        serverRepository: ServerRepository,
        @StandardOkHttpClient okHttpClient: OkHttpClient,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ) = okHttpClient
        .newBuilder()
        .addInterceptor {
            val request = it.request()
            val newRequest =
                serverRepository.current.value?.user?.accessToken?.let { token ->
                    request
                        .newBuilder()
                        .addHeader(
                            "Authorization",
                            AuthorizationHeaderBuilder.buildHeader(
                                clientName = clientInfo.name,
                                clientVersion = clientInfo.version,
                                deviceId = deviceInfo.id,
                                deviceName = deviceInfo.name,
                                accessToken = token,
                            ),
                        ).build()
                }
            it.proceed(newRequest ?: request)
        }.build()

    @ReleaseCompanionOkHttpClient
    @Provides
    @Singleton
    fun releaseCompanionOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun releaseCompanionFeature(
        serverRepository: ServerRepository,
        preferences: DataStore<AppPreferences>,
        @ReleaseCompanionOkHttpClient okHttpClient: OkHttpClient,
        @IoCoroutineScope ioScope: CoroutineScope,
    ): ReleaseCompanionFeature {
        val defaultBaseUrl = BuildConfig.COMPANION_BASE_URL.trim()
        val originPolicy =
            CompanionOriginPolicy(
                allowCleartext = BuildConfig.COMPANION_ALLOW_CLEARTEXT,
            )
        val credentialProvider =
            JellyfinCredentialProvider {
                val current = serverRepository.current.value
                val token = current?.user?.accessToken?.takeIf(String::isNotBlank)
                if (current == null || token == null) {
                    null
                } else {
                    JellyfinCredential(
                        identityKey = "${current.server.id}:${current.user.id}",
                        accessToken = SensitiveValue.of(token),
                    )
                }
            }

        fun transport(baseUrl: String) =
            OkHttpReleaseCompanionTransport(
                baseUrl = baseUrl,
                baseClient = okHttpClient,
                originPolicy = originPolicy,
            )

        val feature =
            ReleaseCompanionFeature.configurable(
                buildEnabled = BuildConfig.COMPANION_ENABLED,
                initialBaseUrl = defaultBaseUrl,
                baseUrlNormalizer = {
                    OkHttpReleaseCompanionTransport
                        .validateBaseUrl(it, originPolicy)
                        .toString()
                },
                repositoryFactory = { baseUrl ->
                    DefaultReleaseCompanionRepository(
                        api = DefaultReleaseCompanionApi(transport(baseUrl)),
                        jellyfinCredentialProvider = credentialProvider,
                    )
                },
                connectionTester = { baseUrl ->
                    val response =
                        transport(baseUrl).execute(
                            ReleaseTransportRequest(
                                method = ReleaseHttpMethod.GET,
                                pathSegments = listOf("readyz"),
                            ),
                        )
                    if (response.statusCode != 200) {
                        throw ReleaseCompanionException.HttpFailure(response.statusCode)
                    }
                },
            )

        if (BuildConfig.COMPANION_ENABLED) {
            ioScope.launch {
                preferences.data
                    .map { stored ->
                        stored.companionBaseUrl.trim().ifEmpty { defaultBaseUrl }
                    }.distinctUntilChanged()
                    .collect(feature::configure)
            }
        }
        return feature
    }

    @Provides
    @Singleton
    fun okHttpFactory(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) = CoroutineContextApiClientFactory(OkHttpFactory(okHttpClient))

    @Provides
    @Singleton
    fun jellyfin(
        okHttpFactory: CoroutineContextApiClientFactory,
        @ApplicationContext context: Context,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ): Jellyfin =
        createJellyfin {
            this.context = context
            this.clientInfo = clientInfo
            this.deviceInfo = deviceInfo
            apiClientFactory = okHttpFactory
            socketConnectionFactory = okHttpFactory
            minimumServerVersion = Jellyfin.minimumVersion
        }

    @Provides
    @Singleton
    fun apiClient(jellyfin: Jellyfin) = jellyfin.createApi()

    @Provides
    @Singleton
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = WholphinDispatchers.IO

    @Provides
    @Singleton
    @IoCoroutineScope
    fun ioCoroutineScope(
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = WholphinDispatchers.Default

    @Provides
    @Singleton
    @DefaultCoroutineScope
    fun defaultCoroutineScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun workManager(
        @ApplicationContext context: Context,
    ): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun seerrApi(
        @StandardOkHttpClient okHttpClient: OkHttpClient,
    ) = SeerrApi(okHttpClient)
}

@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {
    @Provides
    @Singleton
    fun deviceInfo(
        @ApplicationContext context: Context,
    ): DeviceInfo = androidDevice(context)
}
