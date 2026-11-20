package com.xynu.campus.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNavigationTest {

    @Test
    fun `http and https stay in the app`() {
        assertTrue(isInAppWebUrl("http://mobilelib.wx.chaoxing.com/weixin/hall/showNew"))
        assertTrue(isInAppWebUrl("https://open.weixin.qq.com/connect/oauth2/authorize"))
        assertTrue(isInAppWebUrl("about:blank"))
    }

    @Test
    fun `non web schemes are handed to the system`() {
        assertFalse(isInAppWebUrl("weixin://dl/officialaccounts"))
        assertFalse(isInAppWebUrl("intent://oauth#Intent;scheme=weixin;end"))
        assertFalse(isInAppWebUrl("mailto:support@example.com"))
    }
}
