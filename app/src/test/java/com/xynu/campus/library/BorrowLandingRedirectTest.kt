package com.xynu.campus.library

import com.xynu.campus.library.model.BindState
import com.xynu.campus.library.net.MobileLibRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：openItemNeedBind 的 302 语义。
 *
 * 背景（真机复现，2026-11，见 docs/probe_findings.md §I）：
 * 用户反馈「图书馆助手没办法识别链接」。根因不是 openid 正则，而是
 * `classifyRedirect` 只认 `open.weixin.qq.com` 与 `showUserBind.action`
 * 两种 302，**成功**的那一种（302 → 阿法迪借阅业务页）落到空 body 上
 * 被判成 UNKNOWN，UI 于是显示「无法识别服务器返回」。
 *
 * 实测链路：
 *   GET http://mobilelib.wx.chaoxing.com/weixin/userBind/openItemNeedBind.action
 *       ?wxProductId=1512&itemId=20302&openid=<openid>
 *   → 302 Location:
 *       http://libopac.xynu.edu.cn:8080/sms/opac/user/lendStatus.action
 *       ?schoolid=968&openid=<openid>&sn=<服务端新签发的 144 字符令牌>
 *
 * 关键点：`sn` 由服务端每次重新签发；用户从微信复制到的旧 sn 会过期并
 * 显示「登录失效」，所以只保存 openid、每次实时换 sn 才是正确做法。
 */
class BorrowLandingRedirectTest {

    private val repo = MobileLibRepository()

    /**
     * 真实抓到的 Location 的**形状**（学校/产品/端口是真的，凭据是假的）。
     *
     * `openid` 与 `sn` 已替换为等长占位符：这两个字段是账号级凭据，
     * 任何长度相等的 `o…` 串都在微信侧可能是真实用户，`sn` 更是当次会话的有效令牌。
     * 测试只关心「能不能从 URL 里认出成功链路」，不关心串的具体取值，
     * 所以占位符与真值完全等价 —— 别为了「更真实」把真值填回来。
     */
    private val realSuccessLocation =
        "http://libopac.xynu.edu.cn:8080/sms/opac/user/lendStatus.action" +
            "?schoolid=968&openid=o" + "X".repeat(27) +
            "&sn=" + "0".repeat(144)

    // ---------- ① 成功路径：必须判为 BOUND（本次 bug 的核心）----------

    @Test
    fun `302 to libopac lendStatus is classified as BOUND`() {
        assertEquals(BindState.BOUND, repo.classifyRedirect(realSuccessLocation, ""))
    }

    @Test
    fun `302 to sms opac path is classified as BOUND even without lendStatus`() {
        val loc = "http://libopac.xynu.edu.cn:8080/sms/opac/book/history.action?openid=x"
        assertEquals(BindState.BOUND, repo.classifyRedirect(loc, ""))
    }

    @Test
    fun `successful redirect exposes a landing url with a fresh sn`() {
        val url = repo.borrowLandingUrl(realSuccessLocation)
        assertNotNull("成功 302 必须能交出业务页地址", url)
        assertTrue("必须是阿法迪业务页", url!!.contains("/sms/opac/"))
        assertTrue("必须带服务端新签发的 sn", url.contains("sn="))
        assertTrue("必须带 schoolid", url.contains("schoolid=968"))
        assertTrue("openid 应被保留", url.contains("openid="))
    }

    // ---------- ② 非成功路径不能被误判成 BOUND ----------

    @Test
    fun `302 to weixin oauth is MISSING_OPENID`() {
        val loc = "https://open.weixin.qq.com/connect/oauth2/authorize?appid=wx123&redirect_uri=x"
        assertEquals(BindState.MISSING_OPENID, repo.classifyRedirect(loc, ""))
    }

    @Test
    fun `302 to showUserBind is UNBOUND_CARD`() {
        val loc = "http://mobilelib.wx.chaoxing.com/weixin/userBind/showUserBind.action?wxProductId=1512"
        assertEquals(BindState.UNBOUND_CARD, repo.classifyRedirect(loc, ""))
    }

    @Test
    fun `oauth and gate are never treated as a borrow landing url`() {
        assertNull(repo.borrowLandingUrl("https://open.weixin.qq.com/connect/oauth2/authorize?appid=x"))
        assertNull(repo.borrowLandingUrl("http://mobilelib.wx.chaoxing.com/weixin/userBind/showUserBind.action"))
        assertNull(repo.borrowLandingUrl(null))
        assertNull(repo.borrowLandingUrl(""))
    }

    @Test
    fun `blank location falls back to content classification`() {
        // 没有 Location 时退回内容判定，不能凭空给 BOUND
        assertEquals(BindState.UNKNOWN, repo.classifyRedirect(null, ""))
        assertEquals(BindState.UNKNOWN, repo.classifyRedirect("", ""))
    }

    // ---------- ③ 内容判定兜底 ----------

    @Test
    fun `libopac host is whitelisted so borrow detail can render in app`() {
        // 2026-11 用户明确「功能性至上」后，libopac 已加入模块白名单，
        // 借阅明细在应用内即可渲染，不必跳外部浏览器。
        // （该域名本来就在基线 network_security_config 的明文名单里。）
        assertTrue(realSuccessLocation.contains("libopac.xynu.edu.cn"))
        assertTrue(
            "libopac 必须在模块白名单内，否则拿不到借阅正文",
            com.xynu.campus.library.net.LibraryHostAllowList.isAllowed(realSuccessLocation),
        )
    }

    @Test
    fun `intranet only opac host is not whitelisted`() {
        // 借阅数据在 222.21.62.155:8080，馆藏检索老接口在 222.21.62.201:8011，
        // 是两台不同机器；后者只对校园网开放，不进白名单。
        assertTrue(
            !com.xynu.campus.library.net.LibraryHostAllowList.isAllowed(
                "http://222.21.62.201:8011/opac/book/abc",
            ),
        )
    }

    @Test
    fun `landing url host differs from opac search host`() {
        // 借阅数据在 222.21.62.155，馆藏搜索在 222.21.62.201，是两台不同的机器。
        // 这条测试防止有人把两个 IP 当成同一个来排查。
        val landing = repo.borrowLandingUrl(realSuccessLocation)!!
        assertTrue(landing.contains("libopac.xynu.edu.cn"))
        assertTrue("借阅业务页端口是 8080", landing.contains(":8080"))
        assertTrue(
            "不含馆藏搜索那台机器的 8011 端口",
            !landing.contains(":8011"),
        )
    }
}
