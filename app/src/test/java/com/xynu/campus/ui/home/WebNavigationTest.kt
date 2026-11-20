package com.xynu.campus.ui.home

import org.junit.Assert.assertEquals
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

    @Test
    fun `reader card binding stays in the webview before OAuth`() {
        assertEquals(
            "http://mobilelib.wx.chaoxing.com/weixin/userBind/showBindAndCheck.action?wxProductId=1512&schoolId=968",
            READER_CARD_BINDING_URL
        )
        assertTrue(isInAppWebUrl(READER_CARD_BINDING_URL))
        assertTrue(isReaderCardBindingUrl(READER_CARD_BINDING_URL))
        assertTrue(isWeChatOAuthUrl("https://open.weixin.qq.com/connect/oauth2/authorize?appid=test"))
        assertFalse(isOAuthFailureUrl("https://open.weixin.qq.com/connect/oauth2/authorize?appid=test"))
        assertTrue(isOAuthFailureUrl("https://open.weixin.qq.com/connect/oauth2/authorize?error=access_denied"))
    }
}
