package com.xynu.campus.library.net

import com.xynu.campus.library.parser.CatalogDetailParser
import com.xynu.campus.library.parser.CatalogSearchParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 馆藏数据源。
 *
 * 抽成接口只为一个原因：`CatalogViewModel` 的状态机（分页边界、失败保页、
 * 详情打开/清空）必须能在 JVM 单测里确定性验证，而真仓库硬编码了学校域名、
 * 走真实网络。有了接口就能塞假数据源，不用靠 MockWebServer 去骗白名单。
 */
interface CatalogDataSource {
    suspend fun search(
        keyword: String,
        pageIndex: Int = 1,
        searchtype: String = CatalogRepository.SEARCH_TITLE,
    ): CatalogRepository.Result<CatalogSearchParser.Page>

    suspend fun detail(bookId: String): CatalogRepository.Result<CatalogDetailParser.Detail>
}

/**
 * 馆藏检索（本校阿法迪 sms 端的 `/search` 系列接口）。
 *
 * 为什么不用现有的 `LibApi.searchBooks` / `getBookDetail`：
 * 那两个打的是 `222.21.62.201:8011`，**只对校园网开放**——用户连手机热点时
 * 15 秒超时（这正是他报的「馆藏搜索被修坏了」）。
 * 这里的 `/search/searchList` 与 `/search/bookDetail` 在 `libopac.xynu.edu.cn:8080`，
 * 与借阅同一台机器，**外网实测 200，稳定可用**。
 *
 * 也就是说：检索 + 书目 + 馆藏复本，这个仓库全部覆盖，不再依赖内网。
 */
class CatalogRepository(
    private val client: OkHttpClient = LibraryHttpClient.create(),
) : CatalogDataSource {


    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class NetworkError(val message: String) : Result<Nothing>
    }

    /**
     * 检索馆藏。
     *
     * [searchtype] 取值以入口页 `a.cur` 的 id 为准，实测只有这四个有意义：
     * - `题名`（默认，空串等价于题名）
     * - `作者`
     * - `主题词`
     * - `索书号`
     * 传未识别的值不会报错，但结果按题名解释（如「ISBN」实测返回 0 条）。
     */
    override suspend fun search(
        keyword: String,
        pageIndex: Int,
        searchtype: String,
    ): Result<CatalogSearchParser.Page> = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            return@withContext Result.NetworkError("请输入检索词")
        }

        val form = FormBody.Builder()
            .add("kw", kw)
            .add("xc", "3")
            .add("schoolid", LibraryHttpClient.SCHOOL_ID)
            .add("searchtype", searchtype)
            .add("campusType", "")
            .add("f_curlocal", "")
            .apply { if (pageIndex > 1) add("pageIndex", pageIndex.toString()) }
            .build()

        val req = Request.Builder()
            .url("${LibraryHttpClient.BASE_LIBOPAC}/search/searchList")
            .post(form)
            .build()

        post(req) { html -> CatalogSearchParser.parse(html, pageIndex) }
    }

    /**
     * 书目详情 + 馆藏复本。
     *
     * @param bookId 32 位十六进制书目 id，可从 [CatalogSearchParser.Book.detailUrl] 提取，
     *   也可用 [bookIdOf] 直接换算。
     */
    override suspend fun detail(bookId: String): Result<CatalogDetailParser.Detail> =
        withContext(Dispatchers.IO) {
            val id = bookId.trim()
            if (id.isEmpty()) {
                return@withContext Result.NetworkError("缺少书目编号")
            }

            // 服务端认的是 detailParam 这个 JSON 字符串，不是裸 id。
            // 里面的 url 只是「原始详情页地址」，外网不可达也没关系——
            // 服务端只取其中的 id 来查库。
            val detailParam = """{"url":"$LEGACY_DETAIL_PREFIX$id"}"""

            val form = FormBody.Builder()
                .add("xc", "3")
                .add("schoolid", LibraryHttpClient.SCHOOL_ID)
                .add("f_curlocal", "")
                .add("detailParam", detailParam)
                .build()

            val req = Request.Builder()
                .url("${LibraryHttpClient.BASE_LIBOPAC}/search/bookDetail")
                .post(form)
                .build()

            post(req) { html -> CatalogDetailParser.parse(html) }
        }

    private inline fun <T> post(
        req: Request,
        crossinline parse: (String) -> T,
    ): Result<T> = try {
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Result.NetworkError("馆藏接口返回 ${resp.code}")
            } else {
                val html = resp.body?.string().orEmpty()
                // 空响应体不能当"正常解析出空结果"。
                //
                // 以前 `Result.Ok(parse(html.orEmpty()))` 直接吞下去：三个解析器
                // 对空输入都返回「空结果」，于是上游换模板/网关 200 空体时，
                // 用户看到的是「没有找到「xxx」」并建议「换个检索字段」——
                // 把"解析失效"说成"你的关键词不对"，用户会一直试错。
                if (html.isBlank()) {
                    Result.NetworkError("馆藏接口返回了空内容，可能是上游改版了")
                } else {
                    Result.Ok(parse(html))
                }
            }
        }
    } catch (e: HostNotAllowedException) {
        Result.NetworkError("host 不在白名单：${e.host}")
    } catch (e: IOException) {
        Result.NetworkError("馆藏接口不可达：${e.message}")
    } catch (e: RuntimeException) {
        // 解析器内部抛出的任何运行时异常都必须转成 NetworkError。
        //
        // 以前没有这个 catch：异常穿透进 viewModelScope.launch，
        // 而全仓没有任何 CoroutineExceptionHandler，
        // 结果 loading/detailLoading 永远停在 true，
        // UI 永久停在 LoadingHint()，只能杀进程。
        // 留一条日志便于定位（不带 body 内容）。
        com.xynu.campus.library.util.LibraryLog.w(
            "Catalog",
            "馆藏解析异常（${req.url.encodedPath}）：${e.javaClass.simpleName}: ${e.message}",
        )
        Result.NetworkError("馆藏内容解析失败：${e.message ?: e.javaClass.simpleName}")
    }

    companion object {
        const val SEARCH_TITLE = "题名"
        const val SEARCH_AUTHOR = "作者"
        const val SEARCH_SUBJECT = "主题词"
        const val SEARCH_CALLNO = "索书号"

        /** 详情参数里的原始地址前缀（服务端只用来取 id，外网不需要真能打开）。 */
        private const val LEGACY_DETAIL_PREFIX = "http://222.21.62.201:8011/opac/book/"

        private val ID_IN_URL = Regex("""/opac/book/([0-9a-fA-F]{16,})""")

        /** 从检索结果里的 `detailUrl` 抽出书目 id；抽不出就返回 null。 */
        fun bookIdOf(detailUrl: String?): String? =
            detailUrl?.let { ID_IN_URL.find(it)?.groupValues?.get(1) }
    }
}
