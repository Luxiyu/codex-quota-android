package cn.luxy.codexquota

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class QuotaWindow(
    val usedPercent: Double?,
    val durationMinutes: Long?,
    val resetsAt: Long?,
) {
    val remainingPercent: Double? get() = usedPercent?.let { (100.0 - it).coerceIn(0.0, 100.0) }
}

data class ResetCredit(val expiresAt: Long?)

data class UsageSnapshot(
    val fiveHour: QuotaWindow?,
    val weekly: QuotaWindow?,
    val availableResets: Int?,
    val resetDetails: List<ResetCredit>?,
    val fetchedAt: Long,
) {
    companion object {
        fun parse(payload: JSONObject, fetchedAt: Long = Instant.now().epochSecond): UsageSnapshot {
            val buckets = payload.optJSONObject("rateLimitsByLimitId")
            val bucket = buckets?.optJSONObject("codex") ?: payload.optJSONObject("rateLimits")
            val windows = listOfNotNull(bucket?.optJSONObject("primary"), bucket?.optJSONObject("secondary"))
                .map { QuotaWindow(it.numberOrNull("usedPercent"), it.longOrNull("windowDurationMins"), it.longOrNull("resetsAt")) }
            // 按服务端实际窗口长度匹配，避免把其他模型额度误标成 5 小时或周额度。
            val reset = payload.optJSONObject("rateLimitResetCredits")
            val details = reset?.optJSONArray("credits")?.let { rows ->
                (0 until rows.length()).mapNotNull { index ->
                    rows.optJSONObject(index)?.takeIf { it.optString("status") == "available" }
                        ?.let { ResetCredit(it.longOrNull("expiresAt")) }
                }.sortedWith(compareBy(nullsLast()) { it.expiresAt })
            }
            return UsageSnapshot(
                fiveHour = windows.firstOrNull { it.durationMinutes == 300L },
                weekly = windows.firstOrNull { it.durationMinutes == 10_080L },
                availableResets = reset?.longOrNull("availableCount")?.takeIf { it >= 0 && it <= Int.MAX_VALUE }?.toInt(),
                resetDetails = details,
                fetchedAt = fetchedAt,
            )
        }
    }
}

private fun JSONObject.numberOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() }

private fun JSONObject.longOrNull(key: String): Long? =
    numberOrNull(key)?.takeIf { it >= 0 && it <= Long.MAX_VALUE.toDouble() }?.toLong()

fun formatTimestamp(seconds: Long?, zone: ZoneId = ZoneId.systemDefault()): String = seconds?.let {
    runCatching { DateTimeFormatter.ofPattern("MM月dd日 HH:mm").withZone(zone).format(Instant.ofEpochSecond(it)) }
        .getOrDefault("时间不可用")
} ?: "服务端未提供"

fun formatCountdown(deadline: Long?, now: Long): String {
    if (deadline == null) return "服务端未提供恢复时间"
    val remaining = deadline - now
    if (remaining <= 0) return "已到恢复时间，请刷新确认"
    val days = remaining / 86_400
    val hours = remaining % 86_400 / 3_600
    val minutes = remaining % 3_600 / 60
    return if (days > 0) "${days}天 ${hours}小时后恢复" else "${hours}小时 ${minutes}分钟后恢复"
}
