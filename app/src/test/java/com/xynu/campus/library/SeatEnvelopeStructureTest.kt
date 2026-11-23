package com.xynu.campus.library

import com.xynu.campus.library.graphql.SeatResponseParser
import com.xynu.campus.library.model.SeatVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：**"解析失败"和"确实为空"被混成同一个结果**。
 *
 * ## 问题
 *
 * `parseLibraries` / `parseLayout` 都是"尽力解析"的纯函数：结构对不上就返回
 * `emptyList()` / `null`。这本身没错——它们不该抛异常。但调用方直接把结果
 * 当成功返回，于是：
 *
 * | 真实情况 | 类型上的结果 | 用户看到 |
 * |---|---|---|
 * | 上游改版，`userAuth` 改名 | `emptyList()` | 「没有取到图书馆列表」 |
 * | 账号没有选座权限，列表真为空 | `emptyList()` | 「没有取到图书馆列表」 |
 *
 * 两句一模一样。第一种是"客户端该更新了"，第二种是"你这账号就是没馆可选"，
 * 排查方向完全相反。`libLayout` 同理：`Res.Ok(null)` 之后 UI 只能显示
 * 「座位图没有取到。」这一句通用文案。
 *
 * ## 修法
 *
 * 加一个**结构探测** `SeatResponseParser.hasUserAuthEnvelope(json)`：
 * 判断 `data.userAuth` 这层在不在。判据刻意选得很保守：
 *
 * - `false` ⇒ 协议主干都不对，明确报「可能上游改版」；
 * - `true` 但列表空 ⇒ 是**真的**空，照常返回空列表。
 *
 * 注意它**不检查** `reserve` / `libs` 在不在 —— 上游用"没有 `reserve` 字段"
 * 表达"这个账号没有选座权限"，那种情况下"空"是正确语义，不该报解析失败。
 * 这条区别是这个测试最想固定住的东西。
 */
class SeatEnvelopeStructureTest {

    // ---------- 主干存在 ----------

    @Test
    fun `normal envelope is recognised`() {
        val json = """
            {"data":{"userAuth":{"reserve":{"libs":[
              {"lib_id":12,"lib_name":"南校区图书馆","is_open":1}
            ]}}}}
        """.trimIndent()
        assertTrue(
            "标准响应必须被认作结构正常",
            SeatResponseParser.hasUserAuthEnvelope(json),
        )
    }

    @Test
    fun `empty libs array is still a valid envelope`() {
        val json = """{"data":{"userAuth":{"reserve":{"libs":[]}}}}"""
        assertTrue(
            "libs 是空数组说明协议主干正常、上游就是没返回馆，" +
                "这不该被当成解析失败",
            SeatResponseParser.hasUserAuthEnvelope(json),
        )
    }

    @Test
    fun `missing reserve is still a valid envelope`() {
        val json = """{"data":{"userAuth":{"goods":{}}}}"""
        assertTrue(
            "没有 reserve 字段通常是「这个账号没有选座权限」，" +
                "属于合法语义而不是协议错乱",
            SeatResponseParser.hasUserAuthEnvelope(json),
        )
    }

    // ---------- 主干缺失 ----------

    @Test
    fun `renamed envelope is rejected`() {
        // 这是最现实的上游改版形态：把 userAuth 改成 user_auth
        val json = """{"data":{"user_auth":{"reserve":{"libs":[]}}}}"""
        assertFalse(
            "userAuth 被改名后必须判为结构不符，否则错误会被显示成「没有图书馆」",
            SeatResponseParser.hasUserAuthEnvelope(json),
        )
    }

    @Test
    fun `missing data layer is rejected`() {
        val json = """{"userAuth":{"reserve":{"libs":[]}}}"""
        assertFalse(
            "少了 data 这层也是结构不符",
            SeatResponseParser.hasUserAuthEnvelope(json),
        )
    }

    @Test
    fun `blank and malformed input are rejected`() {
        assertFalse("空串", SeatResponseParser.hasUserAuthEnvelope(""))
        assertFalse("null", SeatResponseParser.hasUserAuthEnvelope(null))
        assertFalse("非 json", SeatResponseParser.hasUserAuthEnvelope("<html>502</html>"))
        assertFalse("截断的 json", SeatResponseParser.hasUserAuthEnvelope("""{"data":{"userA"""))
        assertFalse("data 不是对象", SeatResponseParser.hasUserAuthEnvelope("""{"data":[]}"""))
        assertFalse("userAuth 是 null", SeatResponseParser.hasUserAuthEnvelope("""{"data":{"userAuth":null}}"""))
    }

    /**
     * 探测和解析必须**判据一致**：结构探测说"正常"时，解析器至少不该崩；
     * 说"不正常"时，解析器给的空结果不能当成功用。
     *
     * 这条把两个函数绑在一起——以后谁改了 `userAuth()` 的取法却漏改探测，
     * 这里会先炸。
     */
    @Test
    fun `probe and parser agree on the same envelope layering`() {
        val good = """{"data":{"userAuth":{"reserve":{"libs":[{"lib_id":7}]}}}}"""
        val bad = """{"data":{"user_auth":{"reserve":{"libs":[{"lib_id":7}]}}}}"""

        assertTrue(SeatResponseParser.hasUserAuthEnvelope(good))
        assertTrue(
            "探测说正常，解析就应当真的解析出东西",
            SeatResponseParser.parseLibraries(good).isNotEmpty(),
        )

        assertFalse(SeatResponseParser.hasUserAuthEnvelope(bad))
        assertTrue(
            "探测说不正常，解析给的空列表就不能被当成功结果用",
            SeatResponseParser.parseLibraries(bad).isEmpty(),
        )
    }

    /**
     * `parseLayout` 的 null 语义：它没有"合法的空布局"，null 一定是没取到。
     * 所以只要拿到 null，调用方就该报错而不是显示空座位图。
     */
    @Test
    fun `layout parse returns null when lib_layout is absent`() {
        val noLayout = """{"data":{"userAuth":{"reserve":{"libs":[{"lib_id":7}]}}}}"""
        assertTrue(
            "没有 lib_layout 时 parseLayout 必须返回 null",
            SeatResponseParser.parseLayout(noLayout, 7) == null,
        )
        assertTrue(
            "但结构探测仍应认为这是合法信封（主干在，只是这个馆没有布局）",
            SeatResponseParser.hasUserAuthEnvelope(noLayout),
        )
    }

    /**
     * `parseLayout` 的**成功路径**：字段逐个落位。
     *
     * ## 为什么单独补这一条
     *
     * 上一条只覆盖了 null 分支——也就是说 `parseLayout` 里那一整段
     * "把 seats[] 映射成 `Seat`" 的代码从未被任何断言触及。
     *
     * 实测证据：把 `status = o.optBoolean("status", true)` 的缺省值
     * 从 `true` 翻成 `false`（即缺字段时默认「可预约」而不是「有人」），
     * 全套 320 条用例 `BUILD SUCCESSFUL`，没有一条失败。
     * 而 `Seat.kt` 里那段长篇论证「上游 status 是布尔且反向」
     * 恰好就是这个字段——论证写得很仔细，落地代码却没有测试守着。
     *
     * 这条用例把 `status` / `seat_status` / 坐标 / 汇总字段全部钉住，
     * 任一字段解析错位都会红。
     */
    @Test
    fun `layout parse maps seat fields and keeps upstream boolean semantics`() {
        val json = """
            {"data":{"userAuth":{"reserve":{"libs":[{
              "lib_id": 12,
              "lib_name": "第一自习室",
              "lib_layout": {
                "max_x": 3, "max_y": 2,
                "seats_total": 6, "seats_used": 2, "seats_booking": 1,
                "seats": [
                  {"key":"1_1","name":"1排1座","x":1,"y":1,"type":1,"status":false},
                  {"key":"2_1","name":"1排2座","x":2,"y":1,"type":1,"status":true,"seat_status":0},
                  {"key":"3_1","name":"1排3座","x":3,"y":1,"type":1,"status":true,"seat_status":2},
                  {"key":"1_2","name":"2排1座","x":1,"y":2,"type":1,"status":true,"seat_status":4},
                  {"key":"2_2","name":"2排2座","x":2,"y":2,"type":1,"status":true,"seat_status":5},
                  {"key":"","name":"无 key","x":3,"y":2,"type":1,"status":false}
                ]
              }
            }]}}}}
        """.trimIndent()

        val layout = SeatResponseParser.parseLayout(json, fallbackLibId = 99)

        assertNotNull("结构完整时必须解析成功，不能返回 null", layout)
        layout!!

        // 汇总字段
        assertEquals(12, layout.libId)
        assertEquals("第一自习室", layout.libName)
        assertEquals(3, layout.maxX)
        assertEquals(2, layout.maxY)
        assertEquals(6, layout.seatsTotal)
        assertEquals(2, layout.seatsUsed)
        assertEquals(1, layout.seatsBooking)
        // 服务端口径的空闲数优先：6 - 2 - 1 = 3
        assertEquals(3, layout.seatsFree)
        assertTrue("seatsTotal>0 时以服务端汇总为准", layout.hasServerCounts)

        // key 为空的座位被丢弃，其余 5 个保留
        assertEquals("key 为空的座位必须丢弃", 5, layout.seats.size)

        val byKey = layout.seats.associateBy { it.key }

        // 上游反向语义：status=false ⇒ 空闲
        val free = byKey.getValue("1_1")
        assertFalse("status=false 是上游的『空闲』", free.status)
        assertTrue("status=false 必须判为 isFree", free.isFree)
        assertEquals(SeatVisual.FREE, free.visual)

        // status=true + seat_status=0 ⇒ 有人（红）
        assertEquals(SeatVisual.OCCUPIED, byKey.getValue("2_1").visual)
        // status=true + seat_status=2 ⇒ 已选（绿），不是可预约
        assertEquals(SeatVisual.SELECTED, byKey.getValue("3_1").visual)
        assertEquals(SeatVisual.AWAY, byKey.getValue("1_2").visual)
        assertEquals(SeatVisual.SUPERVISED, byKey.getValue("2_2").visual)

        // 缺 seat_status 字段 ⇒ null（不是 0），这是"取不到"与"取到 0"的区别
        assertNull(
            "没有 seat_status 字段时必须是 null 而不是 0",
            byKey.getValue("1_1").seatStatus,
        )
        assertEquals(0, byKey.getValue("2_1").seatStatus)

        // 坐标与网格
        assertEquals(1, free.x)
        assertEquals(1, free.y)
        val grid = layout.grid()
        assertEquals(2, grid.size)
        assertEquals(3, grid[0].size)
        assertEquals("1_1", grid[0][0]?.key)
        assertEquals("1_2", grid[1][0]?.key)
    }

    /**
     * 缺 `status` 字段时的缺省值必须是 `true`（当作有人）。
     *
     * 这是 fail-safe 方向：上游少给一个字段时，宁可把一个空位画成"有人"
     * （用户点不到，最多抱怨），也不能把一个占用的位置画成白色可点
     * （用户点了会失败，或更糟——以为能约到）。
     */
    @Test
    fun `seat without status field defaults to occupied`() {
        val json = """
            {"data":{"userAuth":{"reserve":{"libs":[{
              "lib_id": 1,
              "lib_layout": {"max_x":1,"max_y":1,"seats":[
                {"key":"1_1","name":"x","x":1,"y":1,"type":1}
              ]}
            }]}}}}
        """.trimIndent()

        val seat = SeatResponseParser.parseLayout(json)?.seats?.single()
        assertNotNull("座位应被解析出来", seat)
        assertTrue(
            "缺 status 字段时必须默认 true（有人），否则会画出可点的假空位",
            seat!!.status,
        )
        assertFalse("默认值下不可点才是 fail-safe", seat.isFree)
    }
}
