package com.xynu.campus.library

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归：**晚到的响应把用户拽回去**。
 *
 * ## 这个 bug 长什么样
 *
 * `SeatViewModel` 里每个网络调用都是 `viewModelScope.launch { ... }`，
 * 谁也不管谁。于是有两条真实的翻车路径：
 *
 * 1. 用户在 LIBRARIES 点「刷新」，列表请求还在飞 → 又点某个馆进座位图。
 *    列表请求后回来，`libraries` / `selectedSeatKey` 被覆盖，
 *    更糟的是 `loadCurrent` 后回来会直接写 `stage = CURRENT`，
 *    用户明明正在座位图上选座，却被"拽"到当前预约页。
 * 2. 在座位图页点「← 返回列表」，那次的 `libLayout` 才回来，
 *    把 `layout` 写回去 —— 用户看到自己刚离开的座位图又弹出来。
 *
 * ## 修法
 *
 * 代际计数器（`generation` / `nextGeneration()` / `isCurrent(gen)`）：
 * 每次发起会改主状态的请求前递增，响应落地前比对代际，对不上就整条丢弃。
 *
 * ## 为什么这个测试是读源码而不是跑代码
 *
 * 构造 `SeatViewModel` 需要真实 `Application` + `EncryptedSharedPreferences`，
 * 单测环境给不出来（和 `SeatViewModelInitOrderTest` 同样的限制）。
 * 但**这个 bug 的本质是"少了一个守卫"**，这类缺陷正好可以用静态断言锁住：
 * 真正会写 `_state` 的异步方法必须出现代际检查。
 *
 * 静态断言防不住"守卫写错"，但它能防住"以后加新方法时忘了写"——
 * 而那才是这个 bug 最常见的复发方式。
 */
class SeatRequestGenerationTest {

    private fun vmSource(): String {
        val candidates = listOf(
            File("src/main/java/com/xynu/campus/library/vm/SeatViewModel.kt"),
            File("app/src/main/java/com/xynu/campus/library/vm/SeatViewModel.kt"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到 SeatViewModel.kt，检查过：${candidates.map { it.absolutePath }}")
        return f.readText()
    }

    /**
     * 取一个函数的完整函数体（从 `fun name` 到下一个同缩进的 `fun` 或文件尾）。
     *
     * 够用的简化版：SeatViewModel 里的函数都是 4 空格缩进的成员，
     * 所以"下一个 `    fun `"就是边界。
     */
    private fun bodyOf(src: String, funName: String): String {
        val start = Regex("""^    fun $funName\(""", RegexOption.MULTILINE).find(src)
            ?: error("SeatViewModel 里找不到 fun $funName")
        val rest = src.substring(start.range.last + 1)
        val nextFun = Regex("""^    (?:private\s+)?fun """, RegexOption.MULTILINE).find(rest)
        // 也把 companion object 当边界
        val companion = Regex("""^    companion object""", RegexOption.MULTILINE).find(rest)
        val endOffsets = listOfNotNull(nextFun?.range?.first, companion?.range?.first)
        val end = endOffsets.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    /**
     * 这三个方法都会写"主状态"，并且都能把用户从一个页面拽到另一个页面。
     *
     * - `loadLibraries`：覆盖列表 + 清 `selectedSeatKey`
     * - `openLayout`：覆盖 `layout`，并停在 LAYOUT 页
     * - `loadCurrent`：**直接写 `stage = CURRENT`**，最典型的一处
     */
    private val asyncMethodsThatMoveUser = listOf(
        "loadLibraries",
        "openLayout",
        "loadCurrent",
    )

    @Test
    fun `state-writing async methods take a generation marker`() {
        val src = vmSource()
        val problems = mutableListOf<String>()

        asyncMethodsThatMoveUser.forEach { name ->
            val body = bodyOf(src, name)
            if (!body.contains("nextGeneration()")) {
                problems += "$name：没有取代际标记（nextGeneration()）," +
                    "晚到的响应会把用户拽回去"
            }
        }

        assertTrue(
            "这些方法会改主状态且可能替用户跳页，必须用代际守卫：\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `state-writing async methods check the generation before writing state`() {
        val src = vmSource()
        val problems = mutableListOf<String>()

        asyncMethodsThatMoveUser.forEach { name ->
            val body = bodyOf(src, name)
            if (!body.contains("isCurrent(gen)")) {
                problems += "$name：取了代际但从不比对，等于没做守卫"
            }
        }

        assertTrue(
            "取了代际就必须在写 _state 之前比对 isCurrent(gen)：\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /**
     * `loadCurrent` 里 `stage = CURRENT` 是全场唯一的"替用户跳页"写入，
     * 它前面必须有守卫。单独锁一条，因为这条出错的代价最高：
     * 用户选到一半被弹走，手上选的座位直接没了。
     */
    @Test
    fun `stage jump to CURRENT is guarded`() {
        val body = bodyOf(vmSource(), "loadCurrent")
        val stageJump = body.indexOf("stage = Stage.CURRENT")
        assertTrue("loadCurrent 里找不到 stage = Stage.CURRENT", stageJump >= 0)

        val guard = body.indexOf("isCurrent(gen)")
        assertTrue(
            "loadCurrent 写 stage = CURRENT 之前必须先判代际 —— " +
                "否则晚到的响应会把正在选座的用户弹到当前预约页",
            guard >= 0 && guard < stageJump,
        )
    }

    /**
     * `backToLibraries` 是"用户主动离开"，它必须作废所有在途请求。
     *
     * 不做的话：走了之后那次的 libLayout 回来，把 `layout` 又写回去，
     * 页面自己弹回座位图 —— 用户没做任何操作，却像被撤销了返回。
     */
    @Test
    fun `leaving the layout page invalidates in-flight requests`() {
        val body = bodyOf(vmSource(), "backToLibraries")
        assertTrue(
            "backToLibraries 必须推进代际，否则在途的 libLayout 回来会" +
                "把 layout 写回去，页面自己弹回座位图",
            body.contains("nextGeneration()"),
        )
    }

    /**
     * `forgetSession` 清掉凭据之后，任何在途响应都不该再落地。
     *
     * 否则 `SessionInvalid` 分支会把 `sessionInvalid = true` 打进基础档页面，
     * 而基础档根本没有 Cookie 输入框 —— 用户看到一条"请重贴 Cookie"的横幅，
     * 却找不到重贴的地方。
     */
    @Test
    fun `forgetting the session invalidates in-flight requests`() {
        val body = bodyOf(vmSource(), "forgetSession")
        assertTrue(
            "forgetSession 必须推进代际，否则在途响应会把高级档状态写回基础档",
            body.contains("nextGeneration()"),
        )
    }

    /** 代际机制本身要存在，且 `isCurrent` 必须是纯比较（不能顺带改状态）。 */
    @Test
    fun `generation helpers exist and isCurrent only compares`() {
        val src = vmSource()
        assertTrue(
            "缺少 generation 字段",
            Regex("""private var generation = 0""").containsMatchIn(src),
        )
        assertTrue(
            "缺少 nextGeneration()",
            Regex("""private fun nextGeneration\(\): Int = \+\+generation""")
                .containsMatchIn(src),
        )

        val isCurrentBody = Regex(
            """private fun isCurrent\(gen: Int\)[^{]*\{(.*?)\}""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(src)?.groupValues?.get(1).orEmpty()

        assertTrue("找不到 isCurrent(gen: Int) 的实现", isCurrentBody.isNotBlank())
        assertTrue(
            "isCurrent 必须是纯比较：如果它顺带改状态，所有守卫的语义都会变得不可推理。\n" +
                "实际实现：$isCurrentBody",
            !isCurrentBody.contains("_state") && !isCurrentBody.contains("update"),
        )
    }
}
