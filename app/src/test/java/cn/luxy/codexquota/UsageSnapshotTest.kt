package cn.luxy.codexquota

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class UsageSnapshotTest {
    @Test fun missingFieldsRemainUnknownInsteadOfZero() {
        val snapshot = UsageSnapshot.parse(JSONObject("{}"), 100)
        assertNull(snapshot.fiveHour)
        assertNull(snapshot.weekly)
        assertNull(snapshot.availableResets)
        assertNull(snapshot.resetDetails)
    }

    @Test fun findsWindowsByDurationAndUsesAuthoritativeResetCount() {
        val snapshot = UsageSnapshot.parse(JSONObject("""{
            "rateLimitsByLimitId":{"codex":{
                "primary":{"usedPercent":60,"windowDurationMins":10080,"resetsAt":300},
                "secondary":{"usedPercent":25,"windowDurationMins":300,"resetsAt":200}
            }},
            "rateLimitResetCredits":{"availableCount":3,"credits":[
                {"status":"available","expiresAt":500},
                {"status":"available","expiresAt":400},
                {"status":"redeemed","expiresAt":100}
            ]}
        }"""), 100)
        assertEquals(75.0, snapshot.fiveHour!!.remainingPercent!!, 0.0)
        assertEquals(40.0, snapshot.weekly!!.remainingPercent!!, 0.0)
        assertEquals(3, snapshot.availableResets)
        assertEquals(listOf(400L, 500L), snapshot.resetDetails!!.map { it.expiresAt })
    }

    @Test fun unrelatedModelBucketDoesNotOverrideCodex() {
        val snapshot = UsageSnapshot.parse(JSONObject("""{
            "rateLimitsByLimitId":{"codex":{"primary":{"usedPercent":10,"windowDurationMins":60}},
            "codex_other":{"primary":{"usedPercent":99,"windowDurationMins":300}}}
        }"""))
        assertNull(snapshot.fiveHour)
    }

    @Test fun nullDetailsDifferFromAnEmptyList() {
        assertNull(UsageSnapshot.parse(JSONObject("""{"rateLimitResetCredits":{"availableCount":2,"credits":null}}""")).resetDetails)
        assertEquals(emptyList<ResetCredit>(), UsageSnapshot.parse(JSONObject("""{"rateLimitResetCredits":{"availableCount":0,"credits":[]}}""")).resetDetails)
    }

    @Test fun remainingPercentageIsClamped() {
        assertEquals(0.0, QuotaWindow(120.0, 300, null).remainingPercent!!, 0.0)
        assertEquals(100.0, QuotaWindow(-5.0, 300, null).remainingPercent!!, 0.0)
    }

    @Test fun elapsedResetDoesNotPretendQuotaIsRestored() {
        assertEquals("已到恢复时间，请刷新确认", formatCountdown(100, 101))
        assertEquals("1天 1小时后恢复", formatCountdown(90_000, 0))
    }

    @Test fun unixSecondsAreConvertedWithExplicitTimezone() {
        assertEquals("01月01日 08:00", formatTimestamp(0, ZoneId.of("Asia/Shanghai")))
        assertEquals("服务端未提供", formatTimestamp(null))
    }

    @Test fun authenticationAndTunnelOnlyAcceptOfficialHosts() {
        assertTrue(isOfficialLoginUrl("https://auth.openai.com/codex/device"))
        assertFalse(isOfficialLoginUrl("https://auth.openai.com.example.org/login"))
        assertFalse(isOfficialLoginUrl("http://auth.openai.com/codex/device"))
        assertFalse(isOfficialLoginUrl("https://auth.openai.com:8443/codex/device"))
        assertTrue(isAllowedTunnelHost("auth.openai.com"))
        assertTrue(isAllowedTunnelHost("chatgpt.com"))
        assertFalse(isAllowedTunnelHost("notopenai.com"))
        assertFalse(isAllowedTunnelHost("openai.com.example.org"))
        assertFalse(isAllowedTunnelHost("127.0.0.1"))
    }

    @Test fun accountSwitchClearsOldQuotaBeforeNewQueryCanFail() {
        val oldSnapshot = UsageSnapshot.parse(JSONObject("{}"), 100)
        val oldState = QuotaUiState(signedIn = true, snapshot = oldSnapshot, stale = false, plan = "plus")
        val switched = reconcileAccountState(oldState, "account-a", "account-b", "pro")
        val queryFailed = switched.copy(error = "网络失败", stale = true)
        assertNull(queryFailed.snapshot)
        assertEquals("pro", queryFailed.plan)
        assertSame(oldSnapshot, reconcileAccountState(oldState, "account-a", "account-a", "plus").snapshot)
    }

    @Test fun lateLoginEventsCannotEraseNewLogin() {
        assertFalse(isCurrentLoginCompletion(null, "old-login"))
        assertFalse(isCurrentLoginCompletion("new-login", "old-login"))
        assertTrue(isCurrentLoginCompletion("new-login", "new-login"))
    }
}
