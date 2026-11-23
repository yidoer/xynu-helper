package com.xynu.campus.library.util

/**
 * 极简 HTML 文本提取。
 *
 * 用途：在「借阅摘要解析器」拿到真实已绑定 fixture 之前，按任务书 F3 要求
 * 先展示落地页原文/摘要。这里只去标签、折叠空白，**不做任何语义解析**，
 * 避免"猜"出不准确的结构误导用户。
 */
object HtmlText {

    private val SCRIPT = Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
    private val STYLE = Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val BLANK = Regex("""\s+""")

    fun stripTags(html: String?, maxChars: Int = 4000): String {
        if (html.isNullOrBlank()) return ""
        return html
            .replace(SCRIPT, " ")
            .replace(STYLE, " ")
            .replace(TAG, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(BLANK, " ")
            .trim()
            .take(maxChars)
    }
}
