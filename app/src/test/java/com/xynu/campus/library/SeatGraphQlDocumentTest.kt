package com.xynu.campus.library

import com.xynu.campus.library.graphql.SeatGraphQlDocuments
import com.xynu.campus.library.graphql.SeatResponseParser
import com.xynu.campus.library.model.SeatVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 验收：GraphQL 文档与上游 bundle **逐字一致**。
 *
 * 这是任务书 §6.5 的核心断言：字段名/顺序/参数名只要有一处不同，
 * 服务端就会报错或返回不全的数据，而且这种错误在真机上极难定位。
 * 因此直接从抓取的 bundle fixture 里把文档原文捞出来比对。
 */
class SeatGraphQlDocumentTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")?.use {
            it.reader(Charsets.UTF_8).readText()
        } ?: error("fixture missing: $name")

    /**
     * 从 bundle 里按 `key:"<doc>"` 形态取出文档原文，并把 `\n` 反转义。
     *
     * 注意：bundle 是**压缩后的 JS 对象字面量**（不是 JSON），键名没有引号：
     * `{list:"query list {\n  userAuth ..."}`。所以 marker 不带前导引号。
     */
    private fun docFromBundle(bundle: String, key: String, startsWith: String): String? {
        val marker = "$key:\"$startsWith"
        val start = bundle.indexOf(marker)
        if (start < 0) return null
        // start 指向键名首字符；+key.length 是冒号，+2 是文档首字符（字符串内容紧跟开引号）
        val docStart = start + key.length + 2
        var i = docStart
        val sb = StringBuilder()
        while (i < bundle.length) {
            val c = bundle[i]
            if (c == '\\' && i + 1 < bundle.length) {
                when (bundle[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(bundle[i + 1])
                }
                i += 2
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c)
            i++
        }
        return null
    }

    private fun normalize(doc: String): String =
        doc.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private fun assertSameDoc(expected: String, actual: String?, label: String) {
        assertNotNull("$label 未能从 bundle 取出", actual)
        assertEquals(
            "$label 必须与上游 bundle 逐字一致（字段名/顺序/参数都不能改）",
            normalize(expected),
            normalize(actual!!),
        )
    }

    // ---------- 来自 seatMap.chunk.js ----------

    @Test
    fun `list document matches upstream bundle`() {
        val bundle = fixture("seatMap.chunk.js")
        assertSameDoc(
            SeatGraphQlDocuments.LIST,
            docFromBundle(bundle, "list", "query list {"),
            "list",
        )
    }

    @Test
    fun `libLayout document matches upstream bundle`() {
        val bundle = fixture("seatMap.chunk.js")
        assertSameDoc(
            SeatGraphQlDocuments.LIB_LAYOUT,
            docFromBundle(bundle, "libLayout", "query libLayout("),
            "libLayout",
        )
    }

    @Test
    fun `libRule document matches upstream bundle`() {
        val bundle = fixture("seatMap.chunk.js")
        assertSameDoc(
            SeatGraphQlDocuments.LIB_RULE,
            docFromBundle(bundle, "libRule", "query libRule("),
            "libRule",
        )
    }

    /**
     * 预约定座。
     *
     * 上游 bundle 里 `reserveSeat` 和 `reserueSeat` 两份文档**并存**，
     * 字段完全相同，只差方法名的 `a`/`u`。但上游前端真正调用的是
     * **拼错的那个** `reserueSeat`（`seatMap.chunk.js` 里
     * `reserveSeat:function(t){… i.default.reserueSeat({...})}`），
     * 所以我们跟它对齐。
     *
     * 这条断言同时保证：如果哪天有人"出于好心"把拼写改回正确形式，
     * 测试会立刻失败并指向这段说明。
     */
    @Test
    fun `reserueSeat mutation matches upstream bundle`() {
        val bundle = fixture("seatMap.chunk.js")
        assertSameDoc(
            SeatGraphQlDocuments.RESERVE_SEAT,
            docFromBundle(bundle, "reserueSeat", "mutation reserueSeat("),
            "reserueSeat",
        )
        // operationName 必须与文档内的方法名一致，否则服务端会按未知操作处理
        assertEquals("reserueSeat", SeatGraphQlDocuments.OP_RESERVE_SEAT)
    }

    @Test
    fun `reserveseat layout document matches upstream bundle`() {
        val bundle = fixture("seatMap.chunk.js")
        assertSameDoc(
            SeatGraphQlDocuments.RESERVE_LAYOUT,
            docFromBundle(bundle, "reserveseat", "query reserveseat("),
            "reserveseat",
        )
    }

    // ---------- 来自 ti_prereserve.js ----------

    @Test
    fun `prereserve document matches upstream bundle`() {
        val bundle = fixture("ti_prereserve.js")
        assertSameDoc(
            SeatGraphQlDocuments.PRERESERVE,
            docFromBundle(bundle, "prereserve", "query prereserve"),
            "prereserve",
        )
    }

    @Test
    fun `getStep0 document matches upstream bundle`() {
        val bundle = fixture("ti_prereserve.js")
        assertSameDoc(
            SeatGraphQlDocuments.GET_STEP0,
            docFromBundle(bundle, "getStep0", "query getStep0"),
            "getStep0",
        )
    }

    @Test
    fun `getStep document matches upstream bundle`() {
        val bundle = fixture("ti_prereserve.js")
        assertSameDoc(
            SeatGraphQlDocuments.GET_STEP,
            docFromBundle(bundle, "getStep", "query getStep {"),
            "getStep",
        )
    }

    @Test
    fun `getStep0 and getStep are different queries`() {
        // 两者极易混淆：一个查验证码需求，一个查流程状态
        assertTrue(SeatGraphQlDocuments.GET_STEP0.contains("getNum"))
        assertTrue(SeatGraphQlDocuments.GET_STEP.contains("queeUrl"))
        assertFalse(
            "getStep0 不应含 getStep 的字段",
            SeatGraphQlDocuments.GET_STEP0.contains("queeUrl"),
        )
    }

    @Test
    fun `reserve query document matches upstream bundle`() {
        val bundle = fixture("ti_prereserve.js")
        assertSameDoc(
            SeatGraphQlDocuments.RESERVE,
            docFromBundle(bundle, "reserve", "query reserve"),
            "reserve",
        )
    }

    @Test
    fun `prereserve libLayout document matches upstream bundle`() {
        val bundle = fixture("ti_prereserve.js")
        assertSameDoc(
            SeatGraphQlDocuments.PRERESERVE_LAYOUT,
            docFromBundle(bundle, "libLayout", "query libLayout("),
            "prereserve.libLayout",
        )
    }

    @Test
    fun `setStep1 mutation matches upstream bundle`() {
        val bundle = fixture("ti_prereserve.js")
        assertSameDoc(
            SeatGraphQlDocuments.SET_STEP1,
            docFromBundle(bundle, "setStep1", "mutation setStep1"),
            "setStep1",
        )
    }

    @Test
    fun `setStep2 mutation matches upstream bundle`() {
        val bundle = fixture("ti_prereserve.js")
        assertSameDoc(
            SeatGraphQlDocuments.SET_STEP2,
            docFromBundle(bundle, "setStep2", "mutation setStep2"),
            "setStep2",
        )
    }

    @Test
    fun `autoReserve mutation matches upstream bundle`() {
        val bundle = fixture("ti_prereserve.js")
        assertSameDoc(
            SeatGraphQlDocuments.AUTO_RESERVE,
            docFromBundle(bundle, "autoReserve", "mutation autoReserve("),
            "autoReserve",
        )
    }

    // ---------- 语义断言：文档里必须有的硬约束 ----------

    @Test
    fun `list document uses libType minus one literal`() {
        assertTrue(
            "list 必须以字面量 libs(libType: -1) 请求，这是实测值",
            SeatGraphQlDocuments.LIST.contains("libs(libType: -1)"),
        )
        assertFalse(
            "libType 不能变量化——上游对变量形式返回空列表",
            SeatGraphQlDocuments.LIST.contains("libs(libType: \$libType)"),
        )
    }

    @Test
    fun `layout documents declare both max_x and max_y`() {
        listOf(
            SeatGraphQlDocuments.LIB_LAYOUT to "libLayout",
            SeatGraphQlDocuments.PRERESERVE_LAYOUT to "prereserve.libLayout",
            SeatGraphQlDocuments.RESERVE_LAYOUT to "reserveseat.libLayout",
        ).forEach { (doc, name) ->
            assertTrue("$name 必须查 max_x", doc.contains("max_x"))
            assertTrue("$name 必须查 max_y", doc.contains("max_y"))
            assertTrue("$name 必须查 seats", doc.contains("seats {"))
        }
    }

    @Test
    fun `layout documents query seat_status and status for grid rendering`() {
        listOf(SeatGraphQlDocuments.LIB_LAYOUT, SeatGraphQlDocuments.PRERESERVE_LAYOUT).forEach {
            assertTrue("网格渲染需要 seat_status", it.contains("seat_status"))
            assertTrue("可预约判定需要 status", it.contains("status"))
            assertTrue("座位键需要 key", it.contains("key"))
            assertTrue("坐标需要 x/y", it.contains("x") && it.contains("y"))
        }
    }

    @Test
    fun `reserveSeat declares captcha as required string`() {
        assertTrue(
            "captcha 必须是 String! 必填（无验证码时传空串）",
            SeatGraphQlDocuments.RESERVE_SEAT.contains("\$captcha: String!"),
        )
        assertTrue(
            "captchaCode 是可空参数",
            SeatGraphQlDocuments.RESERVE_SEAT.contains("\$captchaCode: String,"),
        )
    }

    @Test
    fun `reserve query exposes expiry fields for countdown`() {
        listOf("exp_date", "exp_date_str", "diff_str", "seat_name", "lib_name").forEach {
            assertTrue("当前预约倒计时需要 $it", SeatGraphQlDocuments.RESERVE.contains(it))
        }
    }

    // ---------- 40001 语义 ----------

    @Test
    fun `real 40001 fixture is recognised as session invalid`() {
        val json = fixture("graphql_anon.json")
        assertTrue(
            "实测 40001 响应必须被判定为会话失效",
            SeatResponseParser.isSessionInvalid(json),
        )
        val err = SeatResponseParser.firstError(json)
        assertNotNull(err)
        assertEquals(40001, err!!.first)
        assertEquals("Access Denied!", err.second)
    }

    @Test
    fun `success response is not treated as session invalid`() {
        val ok = """{"data":{"userAuth":{"reserve":{"libs":[]}}}}"""
        assertFalse(SeatResponseParser.isSessionInvalid(ok))
        assertNull(SeatResponseParser.firstError(ok))
    }

    @Test
    fun `session invalid causes reserve failure mapping`() {
        val outcome = SeatResponseParser.parseReserveOutcome(fixture("graphql_anon.json"))
        assertTrue(
            "40001 必须映射为 SessionInvalid 而非普通失败",
            outcome is SeatResponseParser.ReserveOutcome.SessionInvalid,
        )
    }

    // ---------- 座位状态映射 ----------

    /**
     * 上游 `ti_prereserve.js` 的原文逻辑是**三级**的：
     * ```js
     * t.status
     *   ? ( bg = "#F36F6F",
     *       t.seat_status > 0 && (
     *         2 === t.seat_status && (bg = "#53B175"),
     *         4 === t.seat_status && (bg = "#DADADA"),
     *         5 === t.seat_status && (bg = "#FE9B4B") ) )
     *   : ( bg = "#FFFFFF", ... )
     * ```
     * 早先的实现只按 `seat_status` 映射、忽略外层 `status` 门控，
     * 导致 `status==true && seat_status<=1`（"有人/暂离/监督"）被画成白色 ——
     * 占着的座位显示成可预约，用户看到的就是"颜色没渲染上"。
     *
     * 这组断言把三级结构逐条钉住。
     */
    @Test
    fun `seat visual mapping follows upstream three-tier status gate`() {
        // 第一级：status==false ⇒ 一律白，**完全不看 seat_status**
        assertEquals(SeatVisual.FREE, SeatVisual.of(status = false, seatStatus = null))
        assertEquals(SeatVisual.FREE, SeatVisual.of(status = false, seatStatus = 0))
        assertEquals(SeatVisual.FREE, SeatVisual.of(status = false, seatStatus = 1))
        assertEquals(SeatVisual.FREE, SeatVisual.of(status = false, seatStatus = 2))
        assertEquals(SeatVisual.FREE, SeatVisual.of(status = false, seatStatus = 4))
        assertEquals(SeatVisual.FREE, SeatVisual.of(status = false, seatStatus = 5))

        // 第二级：status==true 且 seat_status 有具体值 ⇒ 按颜色区分
        // 2 → 绿 = 上游图例「已选」（不是"可预约"！）；4 → 灰 =「暂离」；5 → 橙 =「监督」
        assertEquals(SeatVisual.SELECTED, SeatVisual.of(status = true, seatStatus = 2))
        assertEquals(SeatVisual.AWAY, SeatVisual.of(status = true, seatStatus = 4))
        assertEquals(SeatVisual.SUPERVISED, SeatVisual.of(status = true, seatStatus = 5))

        // 第三级（曾经漏掉的那级）：status==true 但 seat_status 为 0/1/null/未知
        // ⇒ 落到上游默认红 #F36F6F，绝不能是白色
        assertEquals(SeatVisual.OCCUPIED, SeatVisual.of(status = true, seatStatus = 0))
        assertEquals(SeatVisual.OCCUPIED, SeatVisual.of(status = true, seatStatus = 1))
        assertEquals(SeatVisual.OCCUPIED, SeatVisual.of(status = true, seatStatus = null))
        assertEquals(SeatVisual.OCCUPIED, SeatVisual.of(status = true, seatStatus = 99))
    }

    @Test
    fun `occupied seats are never rendered as free`() {
        // 回归护栏：任何 status==true 的座位都不能拿到 FREE（白/可预约）
        listOf(null, 0, 1, 2, 4, 5, 99).forEach { ss ->
            assertNotEquals(
                "status=true, seat_status=$ss 不能渲染成白色可预约",
                SeatVisual.FREE,
                SeatVisual.of(status = true, seatStatus = ss),
            )
        }
    }

    @Test
    fun `status false means free not busy`() {
        val free = com.xynu.campus.library.model.Seat(
            key = "k1", name = "1", x = 1, y = 1, type = 1,
            status = false, seatStatus = 2,
        )
        val taken = free.copy(status = true)
        assertTrue("status==false 才是空闲（上游反向语义）", free.isFree)
        assertFalse("status==true 是已占", taken.isFree)
    }

    @Test
    fun `seat visual semantics match upstream menuList labels`() {
        // 这条钉的是"枚举语义 == 上游图例文字"，不是颜色。
        //
        // 曾经的真实 bug：SeatVisual 有个成员叫 AVAILABLE（"可预约"），图例也写
        // 「可预约」，但它对应 seat_status==2 → 上游绿 #53B175 → menuList 里的词是
        // 「已选」。用户照着图例去找绿色可预约座位，点上去没反应（因为
        // status==true ⇒ isFree==false ⇒ 不可点），看起来像 App 坏了。
        //
        // 修法不是改门控（门控与上游 clickSeat 一致、是对的），是改名字和图例文案。
        // 这里用"成员名 + 与 isFree 的关系"把语义固定住，防止有人改回 AVAILABLE。
        assertTrue(
            "seat_status==2 的座位上游是「已选」，不能可点",
            !com.xynu.campus.library.model.Seat(
                key = "k", name = "1", x = 1, y = 1, type = 1,
                status = true, seatStatus = 2,
            ).isFree,
        )

        // 只有 FREE 一档可以有 darkBackground == false 的"浅色"，其余都是深底白字。
        // 绿(#53B175)/橙(#FE9B4B)/红(#F36F6F) 都是深底；灰(#DADADA) 是浅底。
        assertFalse("白色是浅底", SeatVisual.FREE.darkBackground)
        assertFalse("灰色 #DADADA 是浅底", SeatVisual.AWAY.darkBackground)
        assertTrue("红色 #F36F6F 是深底", SeatVisual.OCCUPIED.darkBackground)
        assertTrue("绿色 #53B175 是深底", SeatVisual.SELECTED.darkBackground)
        assertTrue("橙色 #FE9B4B 是深底", SeatVisual.SUPERVISED.darkBackground)
    }
}
