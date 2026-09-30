package cn.luxy.codexquota

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageQueryTest {
    @Test fun transientStartupErrorRenewsAndRecovers() = runBlocking {
        val calls = mutableListOf<String>()
        val payload = JSONObject().put("rateLimits", JSONObject())
        val result = readUsageWithRecovery { method, params ->
            calls.add(method)
            when (calls.size) {
                1 -> throw CodexRpcException(-32603)
                2 -> { assertTrue(params.getBoolean("refreshToken")); JSONObject("""{"account":{"type":"chatgpt"}}""") }
                else -> payload
            }
        }
        assertSame(payload, result)
        assertEquals(listOf("account/rateLimits/read", "account/read", "account/rateLimits/read"), calls)
    }
    @Test fun persistentFailureIsNotRetriedForever() = runBlocking {
        var queries = 0
        try {
            readUsageWithRecovery { method, _ ->
                if (method == "account/read") JSONObject("""{"account":{"type":"chatgpt"}}""")
                else { queries++; throw CodexRpcException(-32603) }
            }
            fail("必须保留持续失败状态")
        } catch (_: CodexRpcException) { assertEquals(2, queries) }
    }
    @Test fun otherRpcErrorsAreNotRetried() = runBlocking {
        var calls = 0
        try {
            readUsageWithRecovery { _, _ -> calls++; throw CodexRpcException(-32601) }
            fail("不应吞掉不支持的方法错误")
        } catch (_: CodexRpcException) { assertEquals(1, calls) }
    }
}
