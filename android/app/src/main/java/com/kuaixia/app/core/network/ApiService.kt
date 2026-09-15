package com.kuaixia.app.core.network

import com.kuaixia.app.core.model.MediaParseResult
import com.kuaixia.app.core.network.dto.HealthResponse
import com.kuaixia.app.core.network.dto.ParseRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Kuaixia Parser API Protocol 客户端。
 *
 * 所有兼容服务器都实现同样的接口，因此这里不写任何
 * if serverA / if serverB 的分支逻辑。
 */
interface ApiService {

    @GET("api/v1/health")
    suspend fun health(): HealthResponse

    @POST("api/v1/parse")
    suspend fun parse(@Body body: ParseRequest): MediaParseResult
}
