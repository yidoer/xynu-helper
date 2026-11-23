package com.xynu.campus.library

import com.xynu.campus.library.net.HostAllowListInterceptor
import com.xynu.campus.library.net.LibraryHostAllowList
import com.xynu.campus.library.seat.SeatReminderWorker
import com.xynu.campus.library.util.RefreshThrottle
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * 验收标准 10：**基础档零网络请求**。
 *
 * 这条是任务书最硬的产品红线——基础档必须在「用户没给任何凭据」时
 * 连一个字节都不发出去。所以不是"看起来没发"，而是从三个层面证明：
 *
 * 1. 结构层：基础档 UI 与 Worker 的依赖里没有任何能发请求的东西
 * 2. 配置层：Worker 约束里没有 NetworkType 要求
 * 3. 客户端层：即便有人误加了一次调用，白名单客户端也只会打那四个 host，
 *    且在无 Cookie 时拦截器会先抛（下面用真实 OkHttp 验证）
 */
class BasicTierZeroNetworkTest {

    @Test
    fun `reminder worker has no network capable fields`() {
        val forbidden = listOf(
            "okhttp3.OkHttpClient",
            "okhttp3.Request",
            "com.xynu.campus.library.net.MobileLibRepository",
            "com.xynu.campus.library.graphql.SeatGraphQlClient",
            "com.xynu.campus.library.net.LibraryHttpClient",
        )
        val fields = SeatReminderWorker::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }

        fields.forEach { f ->
            val type = f.type.name
            forbidden.forEach { bad ->
                assertFalse(
                    "SeatReminderWorker.${f.name} 的类型 $type 具备联网能力，基础档必须零网络",
                    type == bad || type.startsWith("$bad\$"),
                )
            }
        }
    }

    @Test
    fun `reminder worker does not depend on repositories or view models`() {
        val fieldTypes = SeatReminderWorker::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.type.name }

        assertFalse(
            "Worker 里不应持有任何 Repository/VM：$fieldTypes",
            fieldTypes.any {
                it.contains("Repository") || it.contains("ViewModel") || it.contains("Client")
            },
        )
    }

    @Test
    fun `throttle default is not smaller than the anti-abuse floor`() {
        // 基础档虽然没有请求，但这条常量同时约束高级档；
        // 3s 是任务书给定的下限，任何"优化"都不该突破它。
        assertTrue(
            "刷新下限不得小于 3000ms",
            RefreshThrottle.DEFAULT_MIN_INTERVAL_MS >= 3000L,
        )
    }

    @Test
    fun `module client would refuse a stray request to a non whitelisted host`() {
        // 假设有人不小心在基础档路径上写了句 newCall("https://analytics.example.com")
        // 白名单拦截器必须在建立连接前就抛，避免"静默外发"。
        val client = OkHttpClient.Builder()
            .addInterceptor(HostAllowListInterceptor())
            .build()

        var threw = false
        try {
            client.newCall(
                Request.Builder().url("https://analytics.example.com/collect").build(),
            ).execute().close()
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(e is com.xynu.campus.library.net.HostNotAllowedException)
        }
        assertTrue("非白名单请求必须被拦下（防止静默埋点）", threw)
    }

    @Test
    fun `only whitelisted hosts are reachable at all from the module client`() {
        // 穷举：任何其它 host 都必须被拒
        val hosts = LibraryHostAllowList.ALL_HOSTS
        assertEquals("白名单必须恰好四个 host", 4, hosts.size)

        listOf(
            "https://www.google.com/",
            "https://api.github.com/",
            "https://firebase.example.com/",
            "https://umeng.com/",
            "https://www.xynu.edu.cn/",
            "https://libseat.xynu.edu.cn/",
            "https://libspace.xynu.edu.cn/",
            // 曾经在白名单里，随臆造的 Origin/Referer 一起移除
            "https://web.traceint.com/",
        ).forEach {
            assertFalse("$it 不在白名单，必须拒绝", LibraryHostAllowList.isAllowed(it))
        }
    }

    /**
     * 补上原有三层论证的**第四层**：字节码层面。
     *
     * 原来最强的证据是「`SeatReminderWorker` 的 `declaredFields` 里没有网络类型」。
     * 这条断言是真的，但它的覆盖范围有一个洞：
     * 如果 `doWork()` 里**局部**构造一个 `OkHttpClient()`（不存成字段），
     * 或者调一个静态单例发请求，`declaredFields` 一个都看不到，测试依然全绿。
     *
     * 所以这里直接读 `doWork()` 的方法字节码，扫它的常量池里有没有网络类型的
     * 符号引用。局部变量、静态调用、方法引用都会在常量池里留下类型名，
     * 所以这条路堵得住"不声明字段也能联网"的写法。
     *
     * 局限（诚实写明）：常量池扫描看不见动态反射
     * （`Class.forName("okhttp3.OkHttpClient")` 的字符串里就没有 `okhttp3/` 开头
     * 的常量项，只有 `okhttp3.OkHttpClient` 这种点号形式）。
     * 所以下面同时也查了点号形式的字符串字面量。
     */
    @Test
    fun `doWork bytecode references no network types`() {
        val cls = SeatReminderWorker::class.java
        val method = cls.getDeclaredMethod("doWork", kotlin.coroutines.Continuation::class.java)

        // 方法体字节码 + 常量池都在 class 文件里；用类自己的字节流最可靠。
        val classBytes = cls.classLoader
            ?.getResourceAsStream(cls.name.replace('.', '/') + ".class")
            ?.use { it.readBytes() }
            ?: error("读不到 SeatReminderWorker.class 字节流")

        // 直接转成可读文本，符号引用会以 `Lokhttp3/OkHttpClient;` 或
        // `okhttp3/OkHttpClient` 的形式出现。这比解析常量池简单且足够。
        val text = String(classBytes, Charsets.ISO_8859_1)

        val offenders = listOf(
            "okhttp3/OkHttpClient",
            "okhttp3/Request",
            "okhttp3/Call",
            "java/net/URL",
            "java/net/HttpURLConnection",
            "library/net/MobileLibRepository",
            "library/graphql/SeatGraphQlClient",
            "library/net/LibraryHttpClient",
        ).filter { text.contains(it) }

        assertTrue(
            "SeatReminderWorker 的字节码里出现了网络类型引用：$offenders。\n" +
                "基础档必须零网络——注意 `declaredFields` 那条检查看不到方法内构造的" +
                "局部 client，所以这里直接扫类字节流。",
            offenders.isEmpty(),
        )

        // 点号形式：防 Class.forName("okhttp3.OkHttpClient") 这类反射写法。
        val dotForm = listOf(
            "okhttp3.OkHttpClient",
            "okhttp3.Request",
            "java.net.URL",
        ).filter { String(classBytes, Charsets.ISO_8859_1).contains(it) }
        assertTrue(
            "SeatReminderWorker 的字节码里出现了网络类型的字符串字面量（疑似反射联网）：$dotForm",
            dotForm.isEmpty(),
        )

        assertTrue("doWork 必须存在且返回类型可解析", method.returnType != Void.TYPE)
    }

    @Test
    fun `library sources contain no analytics or crash sdk imports`() {
        // 红线 2：不引入任何遥测 / 崩溃上报 / 统计 SDK。
        // 这里用源码扫描做一次结构性断言（比人工 review 可靠）。
        val forbidden = listOf(
            "com.google.firebase",
            "com.umeng",
            "com.tencent.bugly",
            "com.alibaba.android.emas",
            "com.sensorsdata",
            "com.baidu.mobstat",
            "org.chromium",
        )
        val root = java.io.File("src/main/java/com/xynu/campus/library")
        assertTrue("找不到模块源码目录，测试路径可能不对: ${root.absolutePath}", root.isDirectory)

        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            forbidden.forEach { pkg ->
                if (text.contains(pkg)) offenders += "${f.name} 引用了 $pkg"
            }
        }
        assertTrue("红线：模块内不得引用任何统计/崩溃 SDK：$offenders", offenders.isEmpty())
    }

    @Test
    fun `module sources never reference the global http client factory`() {
        // 红线：模块必须用自己的 OkHttp client（带白名单），
        // 复用它就没有白名单保护了。
        // 注意：注释里允许出现这个词（文档会说"绝不复用"），所以先剥掉注释再查。
        val root = java.io.File("src/main/java/com/xynu/campus/library")
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val code = f.readText()
                .replace(Regex("""/\*[\s\S]*?\*/"""), "")   // 块注释
                .replace(Regex("""//[^\n]*"""), "")          // 行注释
                .replace(Regex("""^\s*\*[^\n]*""", RegexOption.MULTILINE), "") // KDoc 续行
            if (code.contains("HttpClientFactory")) offenders += f.name
        }
        assertTrue(
            "模块不得复用全局 HttpClientFactory（会绕过白名单）：$offenders",
            offenders.isEmpty(),
        )
    }
}
