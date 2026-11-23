package com.xynu.campus.data.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * 契约测试：`JwApi.hosts` 与 `network_security_config.xml` 的明文白名单必须一致。
 *
 * ## 为什么需要这个测试
 *
 * 这两份清单**必须同步但没有任何机制强制同步**：
 *
 * - `JwApi.hosts` 里的每条线路都是 `http://`（校内教务系统只有 http，无法改 https）；
 * - 而 `network_security_config.xml` 的 `<base-config cleartextTrafficPermitted="false" />`
 *   默认禁止明文，只有列进 `<domain-config>` 的 host 才放行；
 * - `JwApi.login()` 是 `for (host in hosts)` 依次尝试，**每条都用 `catch (e: Exception)`
 *   吞掉异常**再试下一条。
 *
 * 三者叠加的后果：只要有一条线路没进白名单，系统就会在 `ConnectInterceptor`
 * 之前抛 `CleartextNotPermittedException`，而这个异常被 `catch` 当成"线路不可达"，
 * 最终对外只说 `"所有教务线路均无法连接，请检查网络"`。
 *
 * **错误信息指向网络，真实原因却是配置** —— 这类缺陷极难排查，
 * 而且它不会让任何一条现有测试变红。本项目真实发生过一次：
 * `.137/.138/.139/.140` 四条线路长期缺失，代码和配置各自看都没问题。
 *
 * ## 这个测试怎么防住它
 *
 * 不硬编码任何 host 列表，而是**从两个真实来源分别读取再对比**：
 *
 * 1. `JwApi(...).hosts` —— 通过实例读取，走的是运行时代码；
 * 2. `network_security_config.xml` —— 通过 `Resources.getXml()` 解析，
 *    走的是与系统相同的 `XmlPullParser`（拿到的是编译后的二进制 XML，
 *    所以**不能**用 `openRawResource` + DOM，那样会报 `Content is not allowed in prolog`）。
 *
 * 任何一边新增/删除线路而另一边没跟上，这个测试就会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JwHostCleartextContractTest {

    private data class Tag(val name: String, val attrs: Map<String, String>)

    /** 把 network_security_config.xml 解析成扁平的 (tag, attrs) 序列。 */
    private fun parseConfigTags(): List<Tag> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resId = context.resources.getIdentifier(
            "network_security_config", "xml", context.packageName
        )
        assertTrue(
            "找不到 network_security_config.xml —— 若被重命名，请同步更新本测试",
            resId != 0
        )
        val parser = context.resources.getXml(resId)
        val tags = mutableListOf<Tag>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val attrs = (0 until parser.attributeCount).associate {
                        parser.getAttributeName(it) to parser.getAttributeValue(it)
                    }
                    tags += Tag(parser.name.orEmpty(), attrs)
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return tags
    }

    /**
     * 收集所有 `cleartextTrafficPermitted="true"` 的 `<domain-config>` 下的 domain 文本。
     *
     * 用状态机而不是 XPath，是因为 `getXml()` 只给顺序事件流。
     * 只认 `cleartextTrafficPermitted="true"`，所以将来若有人新增
     * `<domain-config cleartextTrafficPermitted="false">` 也不会被误算进来。
     */
    private fun cleartextAllowedHosts(): Set<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resId = context.resources.getIdentifier(
            "network_security_config", "xml", context.packageName
        )
        val parser = context.resources.getXml(resId)
        val out = mutableSetOf<String>()
        var insideCleartextConfig = false
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "domain-config" ->
                            insideCleartextConfig =
                                parser.getAttributeValue(null, "cleartextTrafficPermitted") == "true"
                        "domain" -> if (insideCleartextConfig) {
                            parser.nextText()?.trim()?.lowercase()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { out += it }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "domain-config") {
                        insideCleartextConfig = false
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return out
    }

    private fun baseConfigValue(): String? {
        val base = parseConfigTags().firstOrNull { it.name == "base-config" }
        return base?.attrs?.get("cleartextTrafficPermitted")
    }

    private fun jwHosts(): List<String> =
        JwApi(okhttp3.OkHttpClient()).hosts.map { it.removePrefix("http://").removePrefix("https://") }

    @Test
    fun everyJwLineIsCleartextAllowed() {
        val allowed = cleartextAllowedHosts()
        val blocked = jwHosts()
            .map { it.trimEnd('/').lowercase() }
            .filterNot { isCovered(it, allowed) }

        assertEquals(
            "JwApi.hosts 里这些线路没有出现在 network_security_config.xml 的明文白名单中，" +
                "运行时会被系统拦掉并被 login() 当成\"线路不可达\"。\n" +
                "缺失的 host: $blocked\n" +
                "当前白名单: ${allowed.sorted()}",
            emptyList<String>(),
            blocked
        )
    }

    /**
     * 反向断言：`210.43.24.x` 这一段必须**双向一致**。
     *
     * 只查这个子集，是因为整份白名单还服务于图书馆等其它模块，
     * 全量比对会变成"第二份需要手工维护的清单"。
     */
    @Test
    fun noStaleExtraJwIpInWhitelist() {
        val jwIpHosts = jwHosts()
            .filter { it.startsWith("210.43.24.") }
            .toSet()
        val allowedJwIps = cleartextAllowedHosts()
            .filter { it.startsWith("210.43.24.") }
            .toSet()

        assertEquals(
            "教务 IP 白名单与实际线路列表出现了漂移。" +
                "多出来的（白名单有、代码没用）: ${(allowedJwIps - jwIpHosts).sorted()}；" +
                "缺少的（代码要用、白名单没有）: ${(jwIpHosts - allowedJwIps).sorted()}",
            jwIpHosts,
            allowedJwIps
        )
    }

    @Test
    fun configIsPackagedAndExplicitlyDeniesCleartext() {
        val allowed = cleartextAllowedHosts()
        assertTrue("白名单解析出 ${allowed.size} 条，明显不对：$allowed", allowed.size >= 5)
        assertEquals(
            "base-config 必须显式 cleartextTrafficPermitted=false。" +
                "若缺失，明文策略会退回平台默认值，随 targetSdk 变化而漂移",
            "false",
            baseConfigValue()
        )
    }

    /**
     * 护栏的护栏：确认 `JwApi.hosts` 确实全是 `http://`。
     *
     * 如果哪天教务线路全改成 https 了，上面几条断言就失去了意义
     * （https 不受明文白名单约束）。这条测试会在那时提醒重新审视整套推理。
     */
    @Test
    fun jwHostsAreStillPlainHttp() {
        val hosts = JwApi(okhttp3.OkHttpClient()).hosts
        assertTrue("JwApi.hosts 为空，配置校验失去意义", hosts.isNotEmpty())
        assertTrue(
            "以下线路已不是 http://，明文白名单不再是它们的约束条件：" +
                hosts.filterNot { it.startsWith("http://") },
            hosts.all { it.startsWith("http://") }
        )
    }

    /** 与 Android 的 `Domain` 匹配语义对齐：完全相等，或作为子域后缀被 `includeSubdomains` 覆盖。 */
    private fun isCovered(host: String, allowed: Set<String>): Boolean =
        allowed.any { a -> host == a || host.endsWith(".$a") }
}
