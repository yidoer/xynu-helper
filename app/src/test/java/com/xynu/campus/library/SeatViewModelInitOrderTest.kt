package com.xynu.campus.library

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 回归：**「配过选座 Cookie 之后，从后台划掉再打开必闪退」**。
 *
 * 根因是 Kotlin 的属性初始化顺序。`SeatViewModel` 的 `init` 块会调
 * `refreshTier()`，而高级档在那个位置会一路调到 `loadLibraries()`，
 * 后者读 `refreshThrottle.tryAcquire()`。如果 `refreshThrottle` 声明在
 * `init` 之后，那一刻它还是 `null`，读它就是 NPE —— ViewModel 构造失败，
 * 页面还没画就崩了。
 *
 * 为什么只有配过 Cookie 的人崩：走那条 `loadLibraries()` 需要 `tier == ADVANCED`，
 * 而高级档的前提是本机存着一个完整的选座会话。所以「一旦给 Cookie」就成了
 * 崩溃的必要条件，用户看到的正是「给完 Cookie，删后台再打开直接闪退」。
 *
 * 这类 bug 编译器不报、单测也不容易覆盖（构造 VM 要真 Android 环境），
 * 所以这里直接读源码，断言 `init` 里会被读到的那些 `val` 都声明在 `init` 之前。
 */
class SeatViewModelInitOrderTest {

    /**
     * 这些属性都会被 `init { refreshTier(); loadFromCode() }` 这条链间接读到。
     *
     * 加新属性时不用改这里 —— 除非它也在 `init` 链上被读。
     */
    private val fieldsReadFromInit = listOf(
        "store",
        "client",
        "mobileLib",
        "refreshThrottle",
        "_state",
    )

    private fun vmSource(): String {
        // 单测的工作目录是 app/（Gradle 的 test task 默认 workdir）
        val candidates = listOf(
            File("src/main/java/com/xynu/campus/library/vm/SeatViewModel.kt"),
            File("app/src/main/java/com/xynu/campus/library/vm/SeatViewModel.kt"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到 SeatViewModel.kt，检查过：${candidates.map { it.absolutePath }}")
        return f.readText()
    }

    private fun lineOfFirstMatch(src: String, pattern: String): Int {
        val re = Regex(pattern, RegexOption.MULTILINE)
        val m = re.find(src) ?: return -1
        return src.substring(0, m.range.first).count { it == '\n' } + 1
    }

    @Test
    fun `fields read from init are declared before init block`() {
        val src = vmSource()

        val initLine = lineOfFirstMatch(src, """^\s*init\s*\{""")
        assertTrue("SeatViewModel 里找不到 init 块", initLine > 0)

        val problems = mutableListOf<String>()
        fieldsReadFromInit.forEach { name ->
            // 匹配 "private val name" / "val name" / "private val name:"
            val declLine = lineOfFirstMatch(src, """^\s*(?:private\s+)?val\s+$name\b""")
            if (declLine < 0) {
                problems += "$name：没找到声明（是不是改名了？改名请同步更新这个测试）"
            } else if (declLine > initLine) {
                problems += "$name：在第 $declLine 行声明，但在第 $initLine 行的 init 之后" +
                    " → 冷启动会读到 null 并 NPE"
            }
        }

        assertTrue(
            "属性必须声明在 init 之前，否则 init 链上会读到 null：\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /**
     * 同一类陷阱的通用守卫：`init` 块里绝不能出现 `!!`。
     *
     * `init` 在构造期执行，任何非空断言失败都是「打开就闪退」，
     * 用户拿不到任何可读信息。这类代码不该出现在构造路径上。
     */
    @Test
    fun `init block contains no force unwrap`() {
        val src = vmSource()
        val re = Regex("""init\s*\{(.*?)\n\s*\}""", setOf(RegexOption.DOT_MATCHES_ALL))
        val initBody = re.find(src)?.groupValues?.get(1).orEmpty()
        assertTrue("取不到 init 块内容", initBody.isNotBlank())
        assertTrue(
            "init 块里不能有 !!（构造期崩溃＝闪退，用户看不到原因）：\n$initBody",
            !initBody.contains("!!"),
        )
    }
}
