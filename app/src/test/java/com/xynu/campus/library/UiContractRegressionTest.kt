package com.xynu.campus.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真机冒烟时发现的一类缺陷的**静态回归护栏**。
 *
 * 背景：在 emulator-5554 上手动点「图书馆助手」入口时 App 直接闪退，logcat 里是
 *
 * ```
 * Caused by: java.lang.NoSuchMethodException:
 *     com.xynu.campus.library.vm.BorrowViewModel.<init> []
 * ```
 *
 * 成因：`viewModel()` 无 factory 时会用反射去找**无参构造**，而 `BorrowViewModel`
 * 的构造参数是 `MobileLibRepository` + `IdentityStore`。这类问题有三层"防不住"：
 * - 编译期不报错（`viewModel()` 是泛型 reified 调用，语法完全合法）
 * - JVM 单测不报错（单测直接 `new`，根本不走 ViewModelProvider）
 * - Robolectric 也不一定报（有 factory 语义差异）
 * 只有真机点一次才会炸。所以用源码扫描把这条约束固定下来。
 *
 * 同一批还发现两处**用户可见**的显示缺陷，一并纳入护栏：
 * - Compose 的 `Text` 不解析 Markdown，字面 `**整体替换**` 会原样显示给用户
 * - 正文用 `colorScheme.outline` 在浅底卡片上对比度不足（截图几乎看不清）
 */
class UiContractRegressionTest {

    private val uiDir = File("src/main/java/com/xynu/campus/library/ui")
    private val vmDir = File("src/main/java/com/xynu/campus/library/vm")
    private val mainActivity = File("src/main/java/com/xynu/campus/MainActivity.kt")

    /** 剥掉注释，只留可执行代码——避免注释里的词造成误报。 */
    private fun codeOnly(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""^\s*\*[^\n]*""", RegexOption.MULTILINE), "")

    // ---------- 缺陷 1：ViewModel 反射构造 ----------

    @Test
    fun `view models needing constructor args must be built via the library factory`() {
        assertTrue("找不到 ViewModel 目录: ${vmDir.absolutePath}", vmDir.isDirectory)

        // 找出所有"构造参数不为空"的 ViewModel：它们都没法被反射无参构造
        val needsArgs = vmDir.listFiles { f -> f.extension == "kt" }
            ?.map { f -> f.name.removeSuffix(".kt") to codeOnly(f.readText()) }
            ?.filter { (_, code) ->
                val m = Regex("""class\s+(\w+ViewModel)\s*\(([^)]*)\)""").find(code)
                // 有参数列表且参数部分不是空的
                m != null && m.groupValues[2].isNotBlank()
            }
            ?.map { it.first }
            ?: emptyList()

        assertTrue(
            "预期至少有一个需要构造参数的 ViewModel（BorrowViewModel），实际：$needsArgs",
            needsArgs.isNotEmpty(),
        )

        val navSource = codeOnly(mainActivity.readText())

        // 逐个 VM 定位它自己的创建语句，而不是"全文件只要出现一次合规调用就算通过"。
        //
        // 旧写法是 Regex("viewModel\\(factory = libraryVmFactory\\)").containsMatchIn(navSource)，
        // 只要文件里有一处合规调用，循环里每个 VM 都会通过 —— 再加第四个有参 VM
        // 用裸 viewModel() 创建，这条测试依然全绿，而它想防的正是那件事。
        val factoryCall = Regex("""viewModel\s*\(\s*factory\s*=\s*libraryVmFactory\s*\)""")

        needsArgs.forEach { vmName ->
            // MainActivity 里的提升写法：
            //   val libraryBorrowVm: com.xynu.campus.library.vm.BorrowViewModel =
            //       viewModel(factory = libraryVmFactory)
            // 注意 VM 可能用全限定名，所以 vmName 前允许任意包前缀。
            val declaration = Regex(
                """val\s+\w+\s*:\s*[\w.]*\b$vmName\b\s*=\s*""",
            ).find(navSource)
                ?: error(
                    "$vmName 有构造参数，但 MainActivity 里找不到它的 Activity 作用域提升语句" +
                        "（形如 `val xxx: $vmName = viewModel(factory = libraryVmFactory)`）。\n" +
                        "要么它被某个 composable 用裸 viewModel() 创建（真机闪退），" +
                        "要么这条测试的定位正则需要跟着改。",
                )

            // 取声明处往后 200 字符（足够覆盖一次换行后的 viewModel(...) 调用），
            // 要求这段窗口里出现带 factory 的调用。
            val window = navSource.substring(
                declaration.range.last + 1,
                minOf(navSource.length, declaration.range.last + 1 + 200),
            )

            assertTrue(
                "$vmName 有构造参数，但 MainActivity 里它的创建语句没有传 factory：" +
                    "「${window.lines().take(3).joinToString(" ").trim()}」\n" +
                    "无 factory 的 viewModel() 会抛 NoSuchMethodException（真机崩溃日志已确认）。" +
                    "必须通过 LibraryViewModelFactory 创建。",
                factoryCall.containsMatchIn(window),
            )
        }
    }

    @Test
    fun `library factory rejects unknown view models instead of failing silently`() {
        val factory = File("src/main/java/com/xynu/campus/library/vm/LibraryViewModelFactory.kt")
        assertTrue("缺少 LibraryViewModelFactory", factory.isFile)

        val code = codeOnly(factory.readText())
        assertTrue(
            "factory 必须对未知 VM 显式抛错，否则会掩盖接线遗漏",
            code.contains("IllegalArgumentException"),
        )
        assertTrue(
            "factory 必须能创建 BorrowViewModel（有构造参数的那个）",
            code.contains("BorrowViewModel"),
        )
    }

    // ---------- 缺陷 2：Compose 不解析 Markdown ----------

    @Test
    fun `user visible strings contain no literal markdown emphasis`() {
        assertTrue("找不到 UI 目录", uiDir.isDirectory)
        val offenders = mutableListOf<String>()

        uiDir.listFiles { f -> f.extension == "kt" }?.forEach { f ->
            val code = codeOnly(f.readText())
            // 在字符串字面量里找 **...**
            Regex(""""[^"\n]*\*\*[^"\n]+?\*\*[^"\n]*"""").findAll(code).forEach {
                offenders += "${f.name}: ${it.value.take(60)}"
            }
        }

        assertTrue(
            "Compose 的 Text 不解析 Markdown，字面 ** 会直接显示给用户" +
                "（实机截图确认）。改用 buildAnnotatedString + withStyle：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    // ---------- 缺陷 3：正文色对比度 ----------

    @Test
    fun `body text does not use outline as a foreground color`() {
        assertTrue("找不到 UI 目录", uiDir.isDirectory)
        val offenders = mutableListOf<String>()

        uiDir.listFiles { f -> f.extension == "kt" }?.forEach { f ->
            val code = codeOnly(f.readText())
            // outline 是描边/分隔线语义，当**文字色**用对比度不够。
            // 当背景色用是合法的（例如步骤序号圆点），所以只查 color = ... 的用法。
            Regex("""color\s*=\s*MaterialTheme\.colorScheme\.outline""").findAll(code)
                .forEach { offenders += f.name }
        }

        assertTrue(
            "正文文字不要用 colorScheme.outline（实机截图上几乎看不清），" +
                "应使用 onSurfaceVariant：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `wizard html has no unescaped markdown either`() {
        val wizardDir = File("src/main/assets/library")
        if (!wizardDir.isDirectory) return
        val offenders = mutableListOf<String>()
        wizardDir.listFiles { f -> f.extension == "html" }?.forEach { f ->
            if (Regex("""\*\*[^*\n]+\*\*""").containsMatchIn(f.readText())) {
                offenders += f.name
            }
        }
        assertTrue("HTML 里不应出现 Markdown 强调标记：$offenders", offenders.isEmpty())
    }

    // ---------- 缺陷 4：界面直接打印凭据 ----------

    @Test
    fun `ui does not render credential bearing urls verbatim`() {
        assertTrue("找不到 UI 目录", uiDir.isDirectory)
        val offenders = mutableListOf<String>()

        uiDir.listFiles { f -> f.extension == "kt" }?.forEach { f ->
            val code = codeOnly(f.readText())
            // 借阅落地地址的 query 里是 openid + sn，两者合起来就是会话凭证。
            // 实机截图里出现过「数据来源：http://…?openid=oK…&sn=6EA1…」把凭证摊在屏幕上，
            // 会被截图/录屏/旁人看走。展示时必须只留 host。
            //
            // 只认一种安全写法：整行里带 hostOf(...)。
            code.lineSequence().forEach { line ->
                if (line.contains("数据来源") && line.contains("$") && !line.contains("hostOf")) {
                    offenders += "${f.name}: ${line.trim().take(70)}"
                }
            }
        }

        assertTrue(
            "界面不能把带 openid/sn 的完整地址显示出来（实机截图确认），" +
                "应只显示 hostOf(url)：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `borrow screen redacts the landing url down to its host`() {
        val screen = File(uiDir, "BorrowScreen.kt")
        assertTrue("缺少 BorrowScreen.kt", screen.isFile)
        val code = codeOnly(screen.readText())

        assertTrue(
            "「数据来源」必须调用 hostOf() 去掉 path/query，否则 openid 与 sn 会被显示出来",
            code.contains("hostOf"),
        )
        // hostOf 自身必须是「只取 host[:port]」，不能退化成原样返回
        assertTrue(
            "hostOf 必须使用 URI 解析并只取 host，不能直接返回入参",
            code.contains("URI(url)") && code.contains("u.host"),
        )
    }

    // ---------- 顶栏一致性 ----------

    /**
     * 所有二级页顶栏都必须走 `AppTopBar`，且 `AppTopBar` 的尺寸要跟主页对上。
     *
     * 用户的原话是「所有的顶栏都和主页对齐」。之前 `AppTopBar` 是
     * 8dp 红点 / 16sp 标题 / 6dp 内边距，主页是 9dp / 17sp / 14dp：
     * 单看每屏都正常，来回切两次就能看出标题在左右跳、字号差一档。
     * 这个测试把三组数字钉在两边，任何一处改动都会红。
     */
    @Test
    fun `all secondary screens use the shared top bar`() {
        // 这些屏曾经各自手搓 Surface+Row 顶栏，后来统一到 AppTopBar。
        val screens = listOf(
            "BorrowScreen.kt",
            "SeatScreen.kt",
            "MigrationScreen.kt",
            "BorrowWizardScreen.kt",
            "CatalogScreen.kt",
        )
        val offenders = mutableListOf<String>()
        screens.forEach { name ->
            val f = File(uiDir, name)
            if (!f.isFile) return@forEach
            val code = codeOnly(f.readText())
            if (!code.contains("AppTopBar(")) offenders += "$name 没用 AppTopBar"
        }
        assertTrue("二级页必须共用 AppTopBar，避免顶栏各写一套：$offenders", offenders.isEmpty())
    }

    @Test
    fun `shared top bar matches the home bar metrics`() {
        val components = File("src/main/java/com/xynu/campus/ui/common/Components.kt")
        val home = File("src/main/java/com/xynu/campus/ui/home/HomeScreen.kt")
        assertTrue("缺少 Components.kt", components.isFile)
        assertTrue("缺少 HomeScreen.kt", home.isFile)

        // 注意：不能用 codeOnly 剥注释，因为这些数字本身就在注释里被解释过，
        // 剥了反而不好定位；直接比对可执行代码里的字面量。
        val barCode = components.readText()
        val homeCode = home.readText()

        val metrics = listOf(
            "size(9.dp)" to "品牌红点 9dp",
            "fontSize = 17.sp" to "标题 17sp",
            "padding(horizontal = 14.dp)" to "左右内边距 14dp",
            "height(48.dp)" to "顶栏高度 48dp",
        )
        metrics.forEach { (token, label) ->
            assertTrue("AppTopBar 必须与主页一致：$label（缺 $token）", barCode.contains(token))
            assertTrue("主页必须保持 $label（缺 $token）", homeCode.contains(token))
        }
    }

    @Test
    fun `catalog screen passes favorites into both list and detail`() {
        // 收藏系统要接到新检索模块上：列表行和详情页顶栏都得能收藏。
        val screen = File(uiDir, "CatalogScreen.kt")
        assertTrue("缺少 CatalogScreen.kt", screen.isFile)
        val code = codeOnly(screen.readText())

        assertTrue(
            "列表页要接收 favoriteIds 才能画出实心/空心心形",
            code.contains("favoriteIds"),
        )
        assertTrue(
            "列表页要有 onToggleFavorite 回调",
            code.contains("onToggleFavorite"),
        )
        assertTrue(
            "详情页也要能收藏（顶栏 action 里的心形）",
            code.contains("isFavorite"),
        )
    }
}
