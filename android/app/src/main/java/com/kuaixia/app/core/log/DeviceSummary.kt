package com.kuaixia.app.core.log

/**
 * 设备与环境摘要（日志 V2）：导出日志时自动生成，供多人测试比对环境差异。
 *
 * 只采集**系统真实可获取**且无隐私风险的字段；取不到用 `unknown`，绝不伪造。
 * 明确禁止采集：IMEI / IMSI / 手机号 / Google 账号 / Android ID / 精确位置 / 联系人 / Cookie / 登录账号。
 */
object DeviceSummary {

    data class Field(val key: String, val value: String?)

    fun render(fields: List<Field>): String = buildString {
        appendLine("========== DEVICE SUMMARY ==========")
        fields.forEach { f ->
            appendLine("${f.key}=${f.value?.takeIf { it.isNotBlank() } ?: "unknown"}")
        }
        append("====================================")
    }
}
