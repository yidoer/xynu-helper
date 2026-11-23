package com.xynu.campus.library

import com.xynu.campus.library.model.BindState
import com.xynu.campus.library.net.LibraryHostAllowList
import com.xynu.campus.library.parser.AesKeyExtractor
import com.xynu.campus.library.parser.HallMapParser
import com.xynu.campus.library.net.LandingPageClassifier
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Base64

/**
 * 闸口0 冒烟：用真实抓取的 fixtures 验证 M0 的解析器与门禁判定。
 *
 * 全部断言对齐任务书 §6 验收标准 1–4、9。
 */
class LibraryFixtureTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")?.use {
            it.reader(Charsets.UTF_8).readText()
        } ?: error("fixture missing: $name")

    // ---------- 验收 1：hall_map 15 项还原 ----------

    @Test
    fun `hall parser restores all entries`() {
        val entries = HallMapParser.parse(fixture("hall.html"))
        // 任务书 §6.1 写「15 项」，2026-11-21 实测 hall.html 为 16 项（服务端已增删）。
        // 断言按实测值，并在 docs/probe_findings.md 记录差异——解析器本身不硬编码数量。
        assertEquals("hall.html 实测应有 16 个 toUrl 入口", 16, entries.size)
        entries.forEach {
            assertTrue("名称不能为空: $it", it.name.isNotEmpty())
            assertTrue("URL 必须是 http(s): $it", it.url.startsWith("http"))
            assertTrue("itemId 必须为正: $it", it.itemId > 0)
        }
        assertEquals("itemId 不应重复", entries.size, entries.map { it.itemId }.toSet().size)
    }

    @Test
    fun `hall parser maps key entries to correct semantics`() {
        val byId = HallMapParser.parse(fixture("hall.html")).associateBy { it.itemId }

        // 28277 = 座位预约 → traceint，bind=0
        val seat = byId.getValue(28277)
        assertEquals("座位预约", seat.name)
        assertTrue("28277 必须指向 traceint", seat.url.contains("traceint.com"))
        assertFalse("座位预约不需要绑读者证", seat.isNeedBind)

        // 20302 = 借阅信息，bind=1
        val borrow = byId.getValue(20302)
        assertTrue("借阅信息必须要求绑定", borrow.isNeedBind)

        // 20301 = 读者证绑定，bind=1
        val bind = byId.getValue(20301)
        assertTrue("读者证绑定必须要求绑定", bind.isNeedBind)

        // 20290 = 读者证解绑，bind=1
        assertTrue(byId.getValue(20290).isNeedBind)

        // 20304 = 馆藏查询，bind=0
        assertFalse("馆藏查询不需要绑定", byId.getValue(20304).isNeedBind)
    }

    @Test
    fun `hall parser extracts decodable from_code`() {
        val raw = HallMapParser.extractFromCode(fixture("hall.html"))
        assertNotNull("必须能从 hall 页提取 from_code", raw)

        // 实测形态是 URL 编码的 base64，须先 URL 解码再 base64 解码
        val urlDecoded = URLDecoder.decode(raw!!, "UTF-8")
        val bytes = Base64.getDecoder().decode(urlDecoded)
        assertTrue("base64 解码后应有内容", bytes.isNotEmpty())

        // 不能硬编码：内容不得是空串或占位
        assertTrue("from_code 不应为空", raw.isNotBlank())
    }

    @Test
    fun `traceint entry url comes from hall map not hardcode`() {
        val entries = HallMapParser.parse(fixture("hall.html"))
        val url = HallMapParser.traceintEntryUrl(entries)
        assertNotNull("必须能定位 traceint 入口", url)
        assertTrue(url!!.contains("reserve/index.html"))
        assertTrue("入口 URL 必须带 from_code", url.contains("from_code="))
    }

    // ---------- 验收 2：门禁检测按内容特征 ----------

    @Test
    fun `gate pages are detected as missing openid`() {
        assertEquals(
            "gate.html 是缺 openid 的门禁页",
            BindState.MISSING_OPENID,
            LandingPageClassifier.classify(fixture("gate.html")),
        )
        assertEquals(
            "traceint_gate.html 同理",
            BindState.MISSING_OPENID,
            LandingPageClassifier.classify(fixture("traceint_gate.html")),
        )
    }

    @Test
    fun `real bind page is not misjudged as gate`() {
        assertEquals(
            "showUserBind.html 是真表单页，不能误判成门禁",
            BindState.UNBOUND_CARD,
            LandingPageClassifier.classify(fixture("showUserBind.html")),
        )
    }

    @Test
    fun `classifier is content based not size based`() {
        // 两页体积都在 1KB 左右但语义不同：证明判定不依赖字节数
        val gate = fixture("gate.html")
        val bind = fixture("showUserBind.html")
        assertTrue("两者不应被同一规则命中", gate != bind)
        assertEquals(BindState.MISSING_OPENID, LandingPageClassifier.classify(gate))
        assertEquals(BindState.UNBOUND_CARD, LandingPageClassifier.classify(bind))
    }

    @Test
    fun `classifier handles empty and unknown input`() {
        assertEquals(BindState.UNKNOWN, LandingPageClassifier.classify(null))
        assertEquals(BindState.UNKNOWN, LandingPageClassifier.classify(""))
        assertEquals(BindState.UNKNOWN, LandingPageClassifier.classify("<html></html>"))
    }

    // ---------- 验收 3：绑定状态机 ----------

    @Test
    fun `unbound page from openItemNeedBind lands on user bind title`() {
        assertEquals(
            "oinb_unbound.html 含 title=用户绑定，应为未绑读者证",
            BindState.UNBOUND_CARD,
            LandingPageClassifier.classify(fixture("oinb_unbound.html")),
        )
    }

    @Test
    fun `gate and unbound are distinct states not both failure`() {
        val gateState = LandingPageClassifier.classify(fixture("gate.html"))
        val unboundState = LandingPageClassifier.classify(fixture("oinb_unbound.html"))
        assertTrue("两者必须是不同状态，才能给不同引导", gateState != unboundState)
    }

    // ---------- 验收 4：AES best-effort ----------

    @Test
    fun `aes anchor and key extracted from showUserBind`() {
        val html = fixture("showUserBind.html")
        assertTrue("必须命中 getAesString 锚点", AesKeyExtractor.hasAnchor(html))

        val key = AesKeyExtractor.extractKey(html)
        assertNotNull("必须能提取 AES key", key)
        assertEquals("key 必须是 16 字符（AES-128）", 16, key!!.length)
    }

    @Test
    fun `aes extraction returns null instead of throwing on bad input`() {
        assertNull(AesKeyExtractor.extractKey(null))
        assertNull(AesKeyExtractor.extractKey(""))
        assertNull("无锚点的页面必须返回 null", AesKeyExtractor.extractKey(fixture("gate.html")))
        assertFalse(AesKeyExtractor.hasAnchor(fixture("gate.html")))
    }

    // ---------- 验收 9：白名单 ----------

    @Test
    fun `allow list accepts exactly the four hosts`() {
        assertEquals(
            "白名单只应有 4 个 host（web.traceint.com 已移除）",
            setOf(
                "mobilelib.wx.chaoxing.com",
                "mobilelibwx.chaoxing.com",
                "wechat.v2.traceint.com",
                "libopac.xynu.edu.cn",
            ),
            LibraryHostAllowList.ALL_HOSTS,
        )
    }

    @Test
    fun `allow list permits http only for mobilelib hosts`() {
        assertTrue(
            LibraryHostAllowList.isAllowed(
                "http://mobilelib.wx.chaoxing.com/weixin/hall/showNew".toHttpUrlOrNull()!!,
            ),
        )
        assertTrue(
            LibraryHostAllowList.isAllowed(
                "https://mobilelibwx.chaoxing.com/weixin/userBind/showUserBind.action".toHttpUrlOrNull()!!,
            ),
        )
    }

    @Test
    fun `allow list rejects http for traceint hosts`() {
        assertFalse(
            "traceint 只允许 https",
            LibraryHostAllowList.isAllowed(
                "http://wechat.v2.traceint.com/index.php/graphql/".toHttpUrlOrNull()!!,
            ),
        )
    }

    @Test
    fun `allow list rejects everything else`() {
        val blocked = listOf(
            "http://222.21.62.201:8011/opac/search",
            "https://libspace.xynu.edu.cn/h5/",
            "https://www.baidu.com/",
            "http://mobilelib.wx.chaoxing.com.evil.com/",
            "https://open.weixin.qq.com/connect/oauth2/authorize",
            "ftp://mobilelib.wx.chaoxing.com/",
        )
        blocked.forEach {
            val url = it.toHttpUrlOrNull()
            if (url != null) {
                assertFalse("必须拒绝: $it", LibraryHostAllowList.isAllowed(url))
            }
        }
    }
}
