package com.rendox.grocerygenius.network.di

import com.rendox.grocerygenius.BuildConfig
import com.rendox.grocerygenius.network.GitHubApi
import com.rendox.grocerygenius.network.webdav.WebDavHttpClient
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkApiModule {
    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .apply {
            if (BuildConfig.DEBUG) {
                this.addNetworkInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    }
                )
            }
        }
        .readTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideGitHubApi(okHttpClient: OkHttpClient): GitHubApi = Retrofit.Builder()
        .client(okHttpClient)
        // raw.githubusercontent.com is not subject to the REST API limit of 60 unauthenticated
        // requests per hour, which made the first sync fail with HTTP 403
        .baseUrl("https://raw.githubusercontent.com/Krys600/GroceryGenius/develop/assets/")
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(GitHubApi::class.java)

    @Provides
    @Singleton
    @WebDavHttpClient
    fun provideWebDavOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .apply {
            if (BuildConfig.DEBUG) {
                this.addNetworkInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                        redactHeader("Authorization")
                    }
                )
            }
        }
        // OkHttp turns redirected PUT requests into GET requests, which would drop the upload
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()
}