package com.kuaixia.app.data

import com.kuaixia.app.core.error.AppException
import com.kuaixia.app.core.error.ErrorCode
import com.kuaixia.app.core.log.AppLogger
import com.kuaixia.app.core.model.MediaParseResult
import com.kuaixia.app.core.model.ParserServerConfig
import com.kuaixia.app.core.network.ApiClient
import com.kuaixia.app.core.network.dto.ErrorResponse
import com.kuaixia.app.core.network.dto.HealthResponse
import com.kuaixia.app.core.network.dto.ParseRequest
import com.kuaixia.app.core.util.ServerUrl
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 解析仓库：负责与解析服务器通信。
 *
 * Phase 1：解析全部走用户配置的服务器（无本地解析）。
 * 服务器不可用时提示失败，不自动无限切换。
 */
class ParserRepository(private val settings: SettingsRepository) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 当前生效的服务器（默认服务器，否则第一个启用的服务器）。 */
    suspend fun currentServer(): ParserServerConfig? =
        settings.serverSettings.first().currentServer()

    /** 测试指定服务器地址是否可连接。 */
    suspend fun testConnection(baseUrl: String): Result<HealthResponse> {
        val normalized = ServerUrl.normalize(baseUrl)
            ?: return Result.failure(AppException(ErrorCode.INVALID_URL, "服务器地址无效"))
        val started = System.currentTimeMillis()
        return runCatching {
            ApiClient.create(normalized).health()
        }.onSuccess {
            AppLogger.i("health ok elapsed_ms=${System.currentTimeMillis() - started}")
        }.onFailure {
            AppLogger.w("health fail type=${it::class.simpleName}")
        }
    }

    /** 解析：使用当前默认服务器。 */
    suspend fun parse(url: String): Result<MediaParseResult> {
        val server = currentServer()
            ?: return Result.failure(AppException(ErrorCode.NO_SERVER, "请先在设置中添加解析服务器"))
        return parseWith(url, server)
    }

    /** 解析：使用指定服务器。 */
    suspend fun parseWith(url: String, server: ParserServerConfig): Result<MediaParseResult> {
        val normalized = ServerUrl.normalize(server.baseUrl)
            ?: return Result.failure(AppException(ErrorCode.INVALID_URL, "服务器地址无效"))
        val started = System.currentTimeMillis()
        return runCatching {
            ApiClient.create(normalized).parse(ParseRequest(url))
        }.onSuccess {
            AppLogger.i(
                "parse ok platform=${it.platform} elapsed_ms=${System.currentTimeMillis() - started}",
            )
        }.mapError()
    }

    /** 把底层异常转换为带中文提示的统一 AppException。 */
    private fun Result<MediaParseResult>.mapError(): Result<MediaParseResult> =
        recoverCatching { throw toAppException(it) }

    private fun toAppException(t: Throwable): AppException {
        if (t is AppException) return t
        if (t is HttpException) {
            val code = runCatching {
                val body = t.response()?.errorBody()?.string()
                body?.let { json.decodeFromString<ErrorResponse>(it).code }
            }.getOrNull()
            return AppException(code ?: ErrorCode.SERVER_ERROR, messageFor(code))
        }
        if (t is SocketTimeoutException) {
            return AppException(ErrorCode.TIMEOUT, "连接超时，请检查服务器地址")
        }
        if (t is IOException) {
            return AppException(ErrorCode.NETWORK_ERROR, "网络错误，无法连接服务器")
        }
        return AppException(ErrorCode.PARSER_ERROR, "解析失败，请稍后重试")
    }

    private fun messageFor(code: String?): String = when (code) {
        ErrorCode.INVALID_URL -> "链接无效"
        ErrorCode.UNSUPPORTED_PLATFORM -> "暂不支持该平台"
        ErrorCode.PLATFORM_BLOCKED -> "该平台触发了风控拦截"
        ErrorCode.COOKIE_REQUIRED -> "该平台需要登录或 Cookie"
        ErrorCode.NETWORK_ERROR -> "网络错误"
        ErrorCode.TIMEOUT -> "连接超时"
        ErrorCode.LOGIN_REQUIRED -> "需要登录才能获取该资源"
        ErrorCode.ACCESS_DENIED -> "访问被拒绝"
        ErrorCode.MEDIA_NOT_FOUND -> "未找到媒体资源"
        ErrorCode.SERVER_ERROR -> "服务器错误"
        ErrorCode.PARSER_ERROR -> "解析失败"
        ErrorCode.UNKNOWN_ERROR -> "解析失败，请稍后重试"
        else -> "解析失败，请稍后重试"
    }
}
