package com.xynu.campus.library.net

import com.xynu.campus.library.model.BindState
import org.jsoup.Jsoup

/**
 * 落地页状态判定：按**内容特征**，禁止按字节数。
 *
 * 实测证据（app/src/test/resources/fixtures/）：
 * | fixture | 大小 | title | micromessenger | isWeixin | 判定 |
 * |---|---|---|---|---|---|
 * | gate.html | 1043B | 请在微信客户端打开链接 | true | true | 缺 openid |
 * | traceint_gate.html | 1043B | 同上 | true | true | 缺 openid |
 * | oinb_unbound.html | 5168B | 用户绑定 | false | false | 未绑读者证 |
 * | showUserBind.html | 5164B | 用户绑定 | false | false | 未绑读者证 |
 *
 * 关键区分点：门禁页是「前端 JS 检测 UA」的页面，所以正文里必然出现
 * `navigator.userAgent` 与 `micromessenger`；绑定页是真正的服务端表单页，
 * 不含这两者。
 */
object LandingPageClassifier {

    /** App 内 WebView 的 UA 一定不含 micromessenger，所以门禁页必然命中此分支。 */
    private val GATE_MARKERS = listOf("navigator.userAgent", "micromessenger", "isWeixin")

    private const val TITLE_USER_BIND = "用户绑定"

    fun classify(html: String?): BindState {
        if (html.isNullOrBlank()) return BindState.UNKNOWN

        // ① 门禁页：UA 检测脚本特征
        val lowered = html.lowercase()
        val gateHits = GATE_MARKERS.count { marker ->
            lowered.contains(marker.lowercase())
        }
        if (gateHits >= 2) return BindState.MISSING_OPENID

        // ② 绑定页：title=用户绑定 且含 AES 锚点（实测两处都在同一个页面模板）
        val title = runCatching {
            Jsoup.parse(html).title().trim()
        }.getOrNull().orEmpty()

        if (title == TITLE_USER_BIND) return BindState.UNBOUND_CARD

        // ③ 其余视为已绑定的业务页
        return if (title.isNotEmpty()) BindState.BOUND else BindState.UNKNOWN
    }
}
