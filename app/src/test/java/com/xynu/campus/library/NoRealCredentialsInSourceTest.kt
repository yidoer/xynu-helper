package com.xynu.campus.library

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 护栏：仓库里不能出现**真实凭据**。
 *
 * 背景（2026-11 上传 GitHub 前发现）：三处测试数据本来是"已脱敏"的注释，
 * 实际填的是真机抓到的真值 ——
 *
 * - `BorrowLandingRedirectTest` 里完整的 `openid` + 144 字符 `sn`
 * - `IdentityStoreTest` 里完整的 `wechatSESS_ID` / `Authorization` / `SERVERID`
 * - `fixtures/showUserBind.html`、`oinb_unbound.html` 里的 `openid`
 *
 * 这些都不是"看起来像"，而是账号本体：拿到 `openid` + `sn` 就能以本人名义
 * 查借阅、续借。而它们的注释都写着「已脱敏」—— **注释不是护栏**，
 * 靠人记着脱敏必然会漏，所以改成扫源码。
 *
 * 与 `scripts/scan_secrets.ps1` 的分工：
 * - 那个脚本扫**待提交的全部文件**（含 md / json / html），在 CI 上跑
 * - 这条测试只扫 **`src/` 下的 Kotlin 与 fixture**，在 `testDebugUnitTest` 里就拦
 * 两层都必要：脚本在 Windows 之外需要 PowerShell，而单测在任何平台都跑得起来。
 */
class NoRealCredentialsInSourceTest {

    private val sourceRoot = File("src")

    /** 允许的占位符特征：命中任一即视为假值。 */
    private val placeholderHints = listOf(
        "placeholder", "PLACEHOLDER", "oTEST", "oABCDEF", "oNEW", "oOLD",
        "oXXXX", "oYOUR", "YOUR_", "example", "EXAMPLE", "\"X\".repeat", "\"0\".repeat",
    )

    private fun collectFiles(): List<File> {
        assertTrue("找不到 src 目录: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        return sourceRoot.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension in setOf("kt", "html", "htm", "json", "js") }
            .toList()
    }

    private fun isPlaceholder(line: String): Boolean =
        placeholderHints.any { line.contains(it) } ||
            // 方法名也会撞上开头的 o + 长串（如 openidAndCookieNeverAppearInPlaintextOnDisk）。
            // 真实 openid 不会以 "openid" 这个单词本身开头，按此排除。
            Regex("""(?<![A-Za-z0-9])openid[A-Za-z0-9_-]{24,}""").containsMatchIn(line)

    // ---------- openid ----------

    @Test
    fun `no real openid literal is committed`() {
        // 微信 openid 形如 o + 27 位以上 [A-Za-z0-9_-]
        val rule = Regex("""(?<![A-Za-z0-9])o[A-Za-z0-9_-]{27,}(?![A-Za-z0-9_-])""")
        val hits = mutableListOf<String>()

        for (file in collectFiles()) {
            file.readLines().forEachIndexed { i, line ->
                if (rule.containsMatchIn(line) && !isPlaceholder(line)) {
                    hits += "${file.path}:${i + 1} -> ${line.trim().take(120)}"
                }
            }
        }

        assertTrue(
            "发现疑似真实 openid。" +
                "真实 openid 是账号本体，必须替换成等长占位符（如 oTEST… / \"X\".repeat(27)）：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    // ---------- 会话令牌 sn ----------

    @Test
    fun `no real sn token literal is committed`() {
        // 阿法迪借阅业务页的 sn 是服务端签发的会话令牌，实测 144 位十六进制
        val rule = Regex("""sn=[0-9A-Fa-f]{24,}""")
        val hits = mutableListOf<String>()

        for (file in collectFiles()) {
            file.readLines().forEachIndexed { i, line ->
                if (rule.containsMatchIn(line) && !isPlaceholder(line)) {
                    hits += "${file.path}:${i + 1} -> ${line.trim().take(120)}"
                }
            }
        }

        assertTrue(
            "发现疑似真实 sn 会话令牌（当次会话有效凭证），必须换成占位符：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    // ---------- 选座 Cookie ----------

    @Test
    fun `no real wechat session cookie is committed`() {
        val rules = listOf(
            Regex("""wechatSESS_ID\s*=\s*[0-9A-Fa-f]{16,}"""),
            Regex("""Authorization\s*=\s*[A-Za-z0-9]{20,}"""),
        )
        val hits = mutableListOf<String>()

        for (file in collectFiles()) {
            file.readLines().forEachIndexed { i, line ->
                for (rule in rules) {
                    if (rule.containsMatchIn(line) && !isPlaceholder(line)) {
                        hits += "${file.path}:${i + 1} [${rule.pattern}] -> ${line.trim().take(120)}"
                    }
                }
            }
        }

        assertTrue(
            "发现疑似真实的微信选座 Cookie 字段，必须换成占位符：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    // ---------- 扫描器自身必须存在 ----------

    @Test
    fun `the pre-commit secret scanner still exists`() {
        // 写死"必须存在"，防止有人嫌麻烦把脚本删掉，让 README 的指路失效。
        val scanner = File("../scripts/scan_secrets.ps1")
        assertTrue(
            "scripts/scan_secrets.ps1 不存在 —— README「参与贡献」指向它，CI 也跑它。",
            scanner.isFile,
        )
    }
}
