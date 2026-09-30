package cn.luxy.codexquota

import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
    @Test fun unknownQuotaAndResetRemainUnknown() {
        assertEquals("—", widgetPercent(null))
        assertEquals("恢复时间 —", widgetReset(QuotaWindow(20.0, 300, null)))
    }
    @Test fun widgetDisplaysRemainingRatherThanConsumedQuota() {
        assertEquals("36%", widgetPercent(QuotaWindow(64.0, 300, 100)))
        assertEquals("0%", widgetPercent(QuotaWindow(100.0, 300, 100)))
    }
    @Test fun pastDeadlineDoesNotManufactureNewQuota() {
        assertEquals("10%", widgetPercent(QuotaWindow(90.0, 300, 0)))
        assertTrue(widgetReset(QuotaWindow(90.0, 300, 0)).endsWith("恢复"))
    }
}
