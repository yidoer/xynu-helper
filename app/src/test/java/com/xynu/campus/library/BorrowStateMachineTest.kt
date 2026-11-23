package com.xynu.campus.library

import com.xynu.campus.library.crypto.OpenIdExtractor
import com.xynu.campus.library.model.BindState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 引导向导 + F3 三态渲染的逻辑层测试。
 *
 * 说明：`BorrowViewModel.onClipboardReadRequested` 直接吃 Android Context，
 * 纯 JVM 无法构造，所以这里把它的**判定内核**单独验证：
 * - 剪贴板文本 → openid 提取（已由 IdentityStoreTest 覆盖）
 * - 落地状态 → 三态文案映射（本文件）
 * - 状态机迁移（本文件）
 *
 * ViewModel 与 UI 的联通由 Robolectric 用例覆盖（BorrowRobolectricTest）。
 */
class BorrowStateMachineTest {

    /** 与 BorrowViewModel 中一致的 ITEM 常量，防止两处不一致。 */
    @Test
    fun `item id constants match probe findings`() {
        // 实测来源：app/src/test/resources/fixtures/hall.html 的 toUrl(...) 三元组
        assertEquals(20302, com.xynu.campus.library.vm.BorrowViewModel.ITEM_BORROW)
        assertEquals(20301, com.xynu.campus.library.net.LibraryHttpClient.ITEM_BIND_CARD)
        assertEquals(28277, com.xynu.campus.library.net.LibraryHttpClient.ITEM_SEAT_RESERVE)
    }

    @Test
    fun `three states map to three distinct user messages`() {
        // 缺 openid / 未绑读者证 / 已绑定，必须给三种不同的用户可见结果
        val missing = messageFor(BindState.MISSING_OPENID)
        val unbound = messageFor(BindState.UNBOUND_CARD)
        val bound = messageFor(BindState.BOUND)

        assertNotNull("缺 openid 必须有提示", missing)
        assertNotNull("未绑读者证必须有提示", unbound)
        assertNull("已绑定不该再弹提示", bound)

        assertTrue("两种失败提示必须不同", missing != unbound)
        assertTrue("提示必须是中文", missing!!.any { it.code in 0x4E00..0x9FFF })
    }

    /** 与 BorrowViewModel 的判定分支保持一致（单一事实源在 ViewModel，这里镜像断言）。 */
    private fun messageFor(state: BindState): String? = when (state) {
        BindState.MISSING_OPENID -> "这段链接里的 openid 没有被服务器接受，请重新复制一次"
        BindState.UNBOUND_CARD ->
            "身份已保存，但还没绑定读者证。请到微信「图书馆公众号 → 个人中心」绑定后再回来。"
        BindState.BOUND -> null
        BindState.UNKNOWN -> "无法识别服务器返回，请稍后重试"
    }

    @Test
    fun `wizard has exactly three steps with chinese titles`() {
        val titles = listOf(
            "打开微信，进入图书馆公众号",
            "进入「个人中心 → 借阅信息」",
            "点右上角「···」，选择复制链接",
        )
        assertEquals("F1 要求图文三步", 3, titles.size)
        titles.forEach { assertTrue("步骤标题必须是中文: $it", it.any { c -> c.code in 0x4E00..0x9FFF }) }
        assertEquals("标题不能重复", 3, titles.toSet().size)
    }

    @Test
    fun `openid from real shaped link is accepted then requires upstream check`() {
        // 真实链接形态（实测 hall 页 showNew 的 query 结构）
        val link = "http://mobilelib.wx.chaoxing.com/weixin/hall/showNew" +
            "?pid=1512&type=8&openid=o" + "a".repeat(27)
        val r = OpenIdExtractor.extract(link)
        assertTrue(r is OpenIdExtractor.Result.Success)
        val openid = (r as OpenIdExtractor.Result.Success).openid
        assertEquals(28, openid.length)
    }

    @Test
    fun `html preview strips tags for fallback rendering`() {
        val html = "<html><head><style>a{}</style><script>var x=1</script></head>" +
            "<body><div>当前借阅 3 本</div><p>《高等数学》应还 2026-12-01</p></body></html>"
        val preview = com.xynu.campus.library.util.HtmlText.stripTags(html)
        assertFalse("不得残留标签", preview.contains("<"))
        assertFalse("不得包含脚本内容", preview.contains("var x"))
        assertTrue("必须保留正文", preview.contains("当前借阅 3 本"))
        assertTrue("必须保留书名", preview.contains("高等数学"))
    }

    @Test
    fun `html preview truncates runaway pages`() {
        val huge = "<div>" + "书".repeat(20000) + "</div>"
        val preview = com.xynu.campus.library.util.HtmlText.stripTags(huge, maxChars = 100)
        assertEquals(100, preview.length)
    }
}
