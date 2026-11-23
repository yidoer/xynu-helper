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

    /**
     * 大小写不敏感匹配：真实教务/OPAC 页面并不保证全小写
     * （常见 `LOGIN_SLOGIN.html`、`CheckLogin.jsp`、`<FORM ...>`）。
     *
     * ## 为什么必须单独写这一组
     *
     * 原实现是 `body.lowercase()` 后再 `contains(...)`，改写后变成
     * `contains(s, ignoreCase = true)`。两者对上述输入等价，但**上面三条
     * 既有用例的输入全是小写**，所以把 `ignoreCase` 改成 `false` 之后
     * 它们依然全绿——这条改写等于没有测试保护。
     *
     * 实测：把 `ignoreCase` 强制为 `false` 后，原 3 条用例
     * `BUILD SUCCESSFUL`、无一条失败；加上本组用例后同一变异即刻变红。
     */
    @Test
    fun `session detection is case insensitive`() {
        // 大写 URL
        assertTrue(
            "大写 URL 里的 LOGIN_SLOGIN 也应判定为过期",
            SessionDetector.isExpired(
                SessionKind.JW,
                "http://jwgl.xynu.edu.cn/jwglxt/XTGL/LOGIN_SLOGIN.HTML",
                ""
            )
        )
        // 大写 body 标签与字段
        assertTrue(
            "大写 <FORM> 与 LOGIN_SLOGIN 也应判定为过期",
            SessionDetector.isExpired(
                SessionKind.JW,
                "http://jwgl.xynu.edu.cn/jwglxt/cjcx/result.html",
                "<FORM ACTION='/jwglxt/xtgl/LOGIN_SLOGIN.html'><INPUT NAME='yhm'></FORM>"
            )
        )
        // OPAC 混合大小写
        assertTrue(
            "混合大小写的 CheckLogin.jsp / PASSWORD 也应判定为过期",
            SessionDetector.isExpired(
                SessionKind.LIB,
                "http://222.21.62.201:8011/opac/mylibrary",
                "<form action='/pages/include/CheckLogin.jsp'><input name='PASSWORD'></form>"
            )
        )
        assertTrue(
            "大写 /OPAC/LOGIN 也应判定为过期",
            SessionDetector.isExpired(
                SessionKind.LIB,
                "http://222.21.62.201:8011/OPAC/Login?referer=/opac/mylibrary",
                ""
            )
        )
        // 反向：大写也不能把正常公开页误判成过期
        assertFalse(
            "公开页即便含大写登录链接，也不该被判为会话过期",
            SessionDetector.isExpired(
                SessionKind.JW,
                "http://jwc.xynu.edu.cn/jwdt.htm",
                "<A HREF='/xtgl/LOGIN_SLOGIN.html'>登录</A>"
            )
        )
    }
}
