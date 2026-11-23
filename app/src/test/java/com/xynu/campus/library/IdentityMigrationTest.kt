package com.xynu.campus.library

import com.xynu.campus.library.migrate.IdentityMigration
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.store.IdentityStore
import com.xynu.campus.library.testutil.FakeSharedPreferences
import com.xynu.campus.library.util.LibraryLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.util.Base64

/**
 * 验收标准 11：迁移 export → clear → import 能恢复**两个模块**。
 *
 * 这条最容易做成"看起来能用但其实丢了一半"：
 * 只测 openid 恢复、不测 Cookie，或反之，都会在用户真换机时才发现问题。
 * 所以这里逐字段比对。
 */
class IdentityMigrationTest {

    private lateinit var store: IdentityStore

    private val openid = "oABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
    private val sessId = "SESS_ID_VALUE_1234567890"
    private val auth = "eyJhbGciOiJIUzI1NiJ9.PAYLOAD.SIGNATURE"
    private val fromCode = "FC_BASE64_ISH"

    @Before
    fun setUp() {
        LibraryLog.resetSink()
        store = IdentityStore.forTest(FakeSharedPreferences())
    }

    @After
    fun tearDown() = LibraryLog.resetSink()

    private fun seedBoth() {
        store.saveBorrowIdentity(openid, boundAt = 1700000000000L)
        store.saveSeatSession(
            SeatSession(sessId, auth, fromCode, version = "5.5"),
            verifiedAt = 1700000001000L,
        )
    }

    // ---------- 验收标准 11 主路径 ----------

    @Test
    fun `export clear import restores both modules`() {
        seedBoth()

        val payload = IdentityMigration.export(store)
        assertNotNull("有身份时必须能导出", payload)
        assertTrue(payload!!.hasBorrow)
        assertTrue(payload.hasSeat)

        // 清空：模拟"新手机"
        store.clearAll()
        assertFalse(store.hasBorrowIdentity())
        assertFalse(store.hasSeatSession())

        val result = IdentityMigration.import(store, payload.text)
        assertTrue("导入应成功，实际: $result", result is IdentityMigration.ImportResult.Success)

        // 两个模块都必须恢复
        val b = store.borrowIdentity()
        assertNotNull("借阅身份必须恢复", b)
        assertEquals(openid, b!!.openid)
        assertEquals(1700000000000L, b.boundAt)

        val s = store.seatSession()
        assertNotNull("选座 Cookie 必须恢复", s)
        assertEquals(sessId, s!!.wechatSessId)
        assertEquals(auth, s.authorization)
        assertEquals(fromCode, s.fromCode)
        assertEquals("5.5", s.version)
    }

    @Test
    fun `imported seat session is not marked verified`() {
        seedBoth()
        val payload = IdentityMigration.export(store)!!
        store.clearAll()
        IdentityMigration.import(store, payload.text)

        // 导入的 Cookie 来自别的设备/时间点，本机没验活过，verifiedAt 应为 0
        assertEquals(
            "导入的 Cookie 未验活，verifiedAt 必须为 0 以便 UI 提示重新验证",
            0L,
            store.seatSession()!!.verifiedAt,
        )
    }

    // ---------- 导出边界 ----------

    @Test
    fun `export returns null when nothing stored`() {
        assertNull(IdentityMigration.export(store))
    }

    @Test
    fun `export works with only openid`() {
        store.saveBorrowIdentity(openid)
        val p = IdentityMigration.export(store)!!
        assertTrue(p.hasBorrow)
        assertFalse(p.hasSeat)
        assertTrue(p.summary.contains("openid"))
        assertFalse(p.summary.contains("Cookie"))
    }

    @Test
    fun `export works with only seat session`() {
        store.saveSeatSession(SeatSession(sessId, auth, fromCode))
        val p = IdentityMigration.export(store)!!
        assertFalse(p.hasBorrow)
        assertTrue(p.hasSeat)
    }

    @Test
    fun `exported payload is base64 of json with marker fields`() {
        seedBoth()
        val p = IdentityMigration.export(store)!!
        val decoded = String(Base64.getDecoder().decode(p.text))
        val json = org.json.JSONObject(decoded)
        assertEquals("xynu-campus", json.getString("app"))
        assertEquals("library", json.getString("module"))
        // v 从 1 升到 2：新增 sum 完整性字段。
        assertEquals(2, json.getInt("v"))
        // 新版导出必须带校验和，否则「抄错一个字符」仍然会被当成导入成功。
        assertTrue("新版迁移码必须带 sum 字段", json.optString("sum").isNotEmpty())
    }

    // ---------- 校验和（防"抄错"） ----------

    @Test
    fun `import rejects payload whose checksum does not match`() {
        seedBoth()
        val p = IdentityMigration.export(store)!!
        val json = org.json.JSONObject(String(Base64.getDecoder().decode(p.text)))
        // 模拟"抄错一个字符"：改 openid 但保留原 sum。
        json.put("openid", openid.dropLast(1) + "Z")
        val tampered = Base64.getEncoder().encodeToString(json.toString().toByteArray())

        val r = IdentityMigration.import(store, tampered)
        assertTrue("校验和不匹配必须拒绝，实际: $r", r is IdentityMigration.ImportResult.Invalid)
        assertTrue(
            "错误信息必须指向「抄错/不完整」而不是 Cookie 失效，实际: " +
                (r as IdentityMigration.ImportResult.Invalid).reason,
            r.reason.contains("校验和") || r.reason.contains("不完整"),
        )
    }

    @Test
    fun `rejected payload leaves existing identity untouched`() {
        // 关键回归：clearAll() 必须在所有校验之后。
        // 原来它在校验中途，任何后续 return 都会把用户原有身份删掉却什么都没导入。
        store.saveBorrowIdentity(openid, boundAt = 1700000000000L)

        val junkJson = """{"app":"xynu-campus","module":"library","v":2,""" +
            """"sum":"deadbeefdeadbeef","openid":"$openid"}"""
        val junk = Base64.getEncoder().encodeToString(junkJson.toByteArray())
        val r = IdentityMigration.import(store, junk)
        assertTrue(r is IdentityMigration.ImportResult.Invalid)

        // 原有身份必须还在。
        assertNotNull("坏包不能清掉本机已有身份", store.borrowIdentity())
        assertEquals(openid, store.borrowIdentity()!!.openid)
    }

    @Test
    fun `legacy payload without checksum is still accepted`() {
        // 兼容性：VERSION=1 的旧迁移包没有 sum 字段，不能因为升级就全部作废。
        store.clearAll()
        val json = org.json.JSONObject()
            .put("v", 1)
            .put("app", "xynu-campus")
            .put("module", "library")
            .put("openid", openid)
            .put("openid_bound_at", 1700000000000L)
        val text = Base64.getEncoder().encodeToString(json.toString().toByteArray())

        val r = IdentityMigration.import(store, text)
        assertTrue("旧包必须仍可导入，实际: $r", r is IdentityMigration.ImportResult.Success)
        assertEquals(openid, store.borrowIdentity()!!.openid)
    }

    // ---------- 导入错误路径 ----------

    @Test
    fun `import rejects empty text`() {
        val r = IdentityMigration.import(store, "   ")
        assertTrue(r is IdentityMigration.ImportResult.Invalid)
        assertTrue((r as IdentityMigration.ImportResult.Invalid).reason.contains("空"))
    }

    @Test
    fun `import rejects non base64 garbage`() {
        val r = IdentityMigration.import(store, "这不是迁移码")
        assertTrue(r is IdentityMigration.ImportResult.Invalid)
    }

    @Test
    fun `import rejects base64 of unrelated json`() {
        val junk = Base64.getEncoder()
            .encodeToString("""{"hello":"world"}""".toByteArray())
        val r = IdentityMigration.import(store, junk)
        assertTrue(r is IdentityMigration.ImportResult.Invalid)
        assertTrue((r as IdentityMigration.ImportResult.Invalid).reason.contains("不是"))
    }

    @Test
    fun `import rejects wrong module marker`() {
        val json = org.json.JSONObject()
            .put("app", "xynu-campus").put("module", "jw")
            .put("openid", openid)
        val text = Base64.getEncoder().encodeToString(json.toString().toByteArray())
        val r = IdentityMigration.import(store, text)
        assertTrue(r is IdentityMigration.ImportResult.Invalid)
    }

    @Test
    fun `import rejects payload with neither openid nor cookie`() {
        val json = org.json.JSONObject().put("app", "xynu-campus").put("module", "library")
        val text = Base64.getEncoder().encodeToString(json.toString().toByteArray())
        val r = IdentityMigration.import(store, text)
        assertTrue(r is IdentityMigration.ImportResult.Invalid)
    }

    @Test
    fun `import rejects malformed openid instead of silently accepting it`() {
        // 迁移码是纯 Base64、无签名：用户抄错一位，解出来仍然是结构合法的 JSON。
        // 若不校验形状，用户会看到「导入成功」，直到查询时才失败 ——
        // 而失败现象是 40001，看起来像「Cookie 失效」而不是「迁移码抄错了」。
        //
        // 这条与 JSBridge.saveIdentity 走同一个 OpenIdExtractor.isWellFormed 口径。
        listOf(
            "o" to "只有一个字符",
            "oSHORT" to "长度不足 21",
            "xABCDEFGHIJKLMNOPQRSTUVWXYZ" to "不是 o 开头",
            "oABC DEF GHIJKLMNOPQRSTUVWX" to "含空格（不在 [A-Za-z0-9_-] 内）",
        ).forEach { (badOpenid, why) ->
            val json = org.json.JSONObject()
                .put("app", "xynu-campus").put("module", "library")
                .put("openid", badOpenid)
            val text = Base64.getEncoder().encodeToString(json.toString().toByteArray())

            val r = IdentityMigration.import(store, text)
            assertTrue(
                "openid「$badOpenid」（$why）必须被拒绝，否则会写入一个永远用不了的凭据",
                r is IdentityMigration.ImportResult.Invalid,
            )
            assertTrue(
                "拒绝原因应指向 openid 格式，实际：${(r as IdentityMigration.ImportResult.Invalid).reason}",
                r.reason.contains("openid"),
            )
            assertNull("坏包不能留下任何凭据", store.borrowIdentity())
        }
    }

    @Test
    fun `import still accepts a well formed openid`() {
        // 上一条的对照组：格式正确必须能导入，否则校验会把正常迁移也拦掉。
        val json = org.json.JSONObject()
            .put("app", "xynu-campus").put("module", "library")
            .put("openid", openid)
        val text = Base64.getEncoder().encodeToString(json.toString().toByteArray())

        val r = IdentityMigration.import(store, text)
        assertTrue(
            "合法 openid 必须能导入，实际：$r",
            r is IdentityMigration.ImportResult.Success,
        )
        assertEquals(openid, store.borrowIdentity()?.openid)
    }

    @Test
    fun `import rejects incomplete cookie triplet`() {
        // 有 wechat_sess_id 但缺 authorization：坏包，必须整包拒绝
        val json = org.json.JSONObject()
            .put("app", "xynu-campus").put("module", "library")
            .put("wechat_sess_id", sessId)
            .put("from_code", fromCode)
        val text = Base64.getEncoder().encodeToString(json.toString().toByteArray())

        val r = IdentityMigration.import(store, text)
        assertTrue(r is IdentityMigration.ImportResult.Invalid)
        assertTrue((r as IdentityMigration.ImportResult.Invalid).reason.contains("不完整"))
        assertNull("坏包不能写入半套凭据", store.seatSession())
    }

    // ---------- 替换语义 ----------

    @Test
    fun `import replaces rather than merges`() {
        // 本机已有 A
        store.saveBorrowIdentity("oOLDOLDOLDOLDOLDOLDOLDOLDOL")
        store.saveSeatSession(SeatSession("OLD_SESS", "OLD_AUTH", "OLD_FC"))

        // 导入 B
        val other = IdentityStore.forTest(FakeSharedPreferences())
        other.saveBorrowIdentity("oNEWNEWNEWNEWNEWNEWNEWNEWNEW")
        other.saveSeatSession(SeatSession("NEW_SESS", "NEW_AUTH", "NEW_FC"))
        val payload = IdentityMigration.export(other)!!

        IdentityMigration.import(store, payload.text)

        assertEquals("oNEWNEWNEWNEWNEWNEWNEWNEWNEW", store.borrowIdentity()!!.openid)
        assertEquals("NEW_SESS", store.seatSession()!!.wechatSessId)
        // 旧值必须被完全覆盖，不能残留
        assertFalse(store.borrowIdentity()!!.openid.startsWith("oOLD"))
    }

    // ---------- 日志红线 ----------

    @Test
    fun `migration logs never contain full credentials`() {
        val logs = mutableListOf<String>()
        LibraryLog.sink = { logs.add("${it.tag}:${it.message}") }

        seedBoth()
        val payload = IdentityMigration.export(store)!!
        store.clearAll()
        IdentityMigration.import(store, payload.text)

        val all = logs.joinToString("\n")
        assertTrue("应该有日志（证明真的记了）", logs.isNotEmpty())
        assertFalse("日志不得含完整 openid", all.contains(openid))
        assertFalse("日志不得含完整 wechatSESS_ID", all.contains(sessId))
        assertFalse("日志不得含完整 JWT", all.contains(auth))
        assertFalse("日志不得含完整 FROM_CODE", all.contains(fromCode))
        assertTrue("应使用脱敏形式", all.contains("openid=oA***"))
        assertTrue("应使用脱敏形式", all.contains("cookie=***"))
    }

    // ---------- 版本号缺省值 ----------

    /**
     * `v_code` 缺失时，导入必须落成**空串**，而不是臆造的 `"5.5"`。
     *
     * ## 这条为什么要单独写
     *
     * 本文件原有的 `version` 断言全部是**显式**构造
     * `SeatSession(..., version = "5.5")`，也就是把 `"5.5"` 当输入喂进去、
     * 再断言读回来是 `"5.5"`。这种测试**永远走不到缺省分支**——
     * 缺省值改成任何东西它都不会红。
     *
     * 而缺省值恰恰是有争议的那个点：`SeatSession.DEFAULT_VERSION`
     * 专门论证过缺省必须为空串（“不发送”），
     * 因为 `5.5` 在上游全部 JS bundle 里出现 0 次，是臆造值。
     * `IdentityMigration` 曾两次写死 `optString("v_code", "5.5")`，与那个结论直接矛盾。
     *
     * 这里手工剥掉 `v_code` 字段再导入，直接钉住缺省值。
     */
    @Test
    fun `missing v_code imports as empty version not invented default`() {
        seedBoth()
        val payload = IdentityMigration.export(store)!!

        // 正常往返里 v_code 一定存在（导出侧无条件 put），
        // 所以要覆盖缺省分支，必须手工解包、删字段。
        val json = JSONObject(String(java.util.Base64.getDecoder().decode(payload.text)))
        json.remove("v_code")

        // 删字段后原校验和必然失效，所以连 sum 一起删，
        // 走“旧包跳过校验”的分支（这条路径本身也是合法输入）。
        json.remove("sum")

        val rebuilt = java.util.Base64.getEncoder()
            .encodeToString(json.toString().toByteArray())

        store.clearAll()
        val result = IdentityMigration.import(store, rebuilt)
        assertTrue("导入应成功，实际: $result", result is IdentityMigration.ImportResult.Success)

        val s = store.seatSession()
        assertNotNull("选座会话应恢复", s)
        assertEquals(
            "缺 v_code 时必须落成空串（＝不发送），不能用臆造的 5.5",
            SeatSession.DEFAULT_VERSION,
            s!!.version,
        )
        assertFalse(
            "空缺省值不应在 Cookie 头里造出 v=5.5",
            s.toCookieHeader().contains("v=5.5"),
        )
    }
}
