package cn.luxy.codexquota

import kotlinx.coroutines.delay
import org.json.JSONObject

/** 官方组件冷启动或连接恢复时的内部错误只恢复一次，避免无限重试。 */
suspend fun readUsageWithRecovery(request: suspend (String, JSONObject) -> JSONObject): JSONObject {
    return try {
        request("account/rateLimits/read", JSONObject())
    } catch (failure: CodexRpcException) {
        if (failure.code != -32603) throw failure
        delay(500)
        val account = request("account/read", JSONObject().put("refreshToken", true)).optJSONObject("account")
        check(account?.optString("type") == "chatgpt") { "登录状态已失效，请重新登录。" }
        request("account/rateLimits/read", JSONObject())
    }
}
