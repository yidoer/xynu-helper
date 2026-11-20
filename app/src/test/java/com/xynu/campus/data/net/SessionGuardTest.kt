package com.xynu.campus.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGuardTest {

    @Test
    fun `http client uses bounded campus network timeouts`() {
        val client = HttpClientFactory.create()

        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(15_000, client.writeTimeoutMillis)
    }

    @Test
    fun `jw login redirect is detected without matching public pages`() {
        assertTrue(
            SessionDetector.isExpired(
                SessionKind.JW,
                "http://jwgl.xynu.edu.cn/jwglxt/xtgl/login_slogin.html",
                ""
            )
        )
        assertTrue(
            SessionDetector.isExpired(
                SessionKind.JW,
                "http://jwgl.xynu.edu.cn/jwglxt/cjcx/result.html",
                "<form action='/jwglxt/xtgl/login_slogin.html'><input name='yhm'></form>"
            )
        )
        assertFalse(
            SessionDetector.isExpired(
                SessionKind.JW,
                "http://jwc.xynu.edu.cn/jwdt.htm",
                "<a href='/xtgl/login_slogin.html'>登录</a>"
            )
        )
    }

    @Test
    fun `opac login redirect and login form are detected but search link is not`() {
        assertTrue(
            SessionDetector.isExpired(
                SessionKind.LIB,
                "http://222.21.62.201:8011/opac/login?referer=/opac/mylibrary",
                ""
            )
        )
        assertTrue(
            SessionDetector.isExpired(
                SessionKind.LIB,
                "http://222.21.62.201:8011/opac/mylibrary",
                "<form action='/pages/include/checklogin.jsp'><input name='username'><input name='password'></form>"
            )
        )
        assertFalse(
            SessionDetector.isExpired(
                SessionKind.LIB,
                "http://222.21.62.201:8011/opac/search",
                "<a href='/opac/login'>登录</a><div class='jp-searchList'>结果</div>"
            )
        )
    }
}
