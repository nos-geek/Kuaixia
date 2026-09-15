package com.kuaixia.app.core.network

import com.kuaixia.app.core.util.ServerUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * 按服务器地址动态构建 Retrofit 客户端。
 * 服务器地址由用户在设置中填写，因此不能写死 baseUrl。
 */
object ApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val contentType = "application/json".toMediaType()

    /**
     * 为给定 baseUrl 构建 ApiService。
     * baseUrl 需已通过 [ServerUrl.normalize] 规范化（保证以 / 结尾）。
     */
    fun create(baseUrl: String): ApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(ApiService::class.java)
    }
}
