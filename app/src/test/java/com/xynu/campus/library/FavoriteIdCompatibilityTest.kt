package com.xynu.campus.library

import com.xynu.campus.library.net.CatalogRepository
import com.xynu.campus.library.parser.CatalogSearchParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏系统的 id 兼容性。
 *
 * 背景：App 里存在两个「查书」入口——
 *   1. 老 `BookSearchScreen`（`LibParser` 解析 `222.21.62.201:8011`，只对校园网开放）
 *   2. 新 `CatalogScreen`（`CatalogSearchParser` 解析 `libopac`，内外网都通）
 *
 * 两者共用**同一份**收藏存储（DataStore 的 `favorite_books` 键），
 * 靠 `id` 判重。若两边算出的 id 形状不一致，同一本书会被收藏两次，
 * 或者在收藏书架里点开变成另一本书。
 *
 * 这个测试把「两边产出同一形状 id」钉死。
 */
class FavoriteIdCompatibilityTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
            ?.bufferedReader()?.readText()
            ?: error("fixture 缺失：$name")

    @Test
    fun `catalog detail url yields the same id shape as the legacy parser`() {
        // 老 LibParser 的取法：从 /opac/book/<id> 里取 id
        val legacyStyle = "/opac/book/8f3c1d2e4b5a6c7d8e9f0a1b2c3d4e5f"
        val legacyId = Regex("^/opac/book/([^/]+)")
            .find(legacyStyle.substringAfter("http://222.21.62.201:8011"))
            ?.groupValues?.get(1)

        // 新的取法：CatalogRepository.bookIdOf 吃完整 URL
        val catalogId = CatalogRepository.bookIdOf(
            "http://222.21.62.201:8011/opac/book/8f3c1d2e4b5a6c7d8e9f0a1b2c3d4e5f",
        )

        assertNotNull("新解析器必须能取出 id", catalogId)
        assertEquals("两边必须得到同一个 id，否则收藏会重复", legacyId, catalogId)
    }

    @Test
    fun `real search results all produce non blank ids`() {
        // 用真实响应验证：每一本可收藏的书都能算出 id 并用于判重。
        // 有一本算不出来，用户在列表里点了心形，收藏书架就会多一条点不开的记录。
        val books = CatalogSearchParser.parse(fixture("catalog_search.html")).books
        assertTrue("真实响应应有 10 条", books.size == 10)

        val ids = books.map { book ->
            CatalogRepository.bookIdOf(book.detailUrl)
                ?: error("书名《${book.title}》的详情地址取不出 id：${book.detailUrl}")
        }
        assertEquals("id 不该有重复", ids.size, ids.toSet().size)
        ids.forEach { assertTrue("id 不该为空：'$it'", it.isNotBlank()) }
    }

    @Test
    fun `id extraction tolerates http https and missing port`() {
        val id = "8f3c1d2e4b5a6c7d8e9f0a1b2c3d4e5f"
        val variants = listOf(
            "http://222.21.62.201:8011/opac/book/$id",
            "https://libopac.xynu.edu.cn:8080/opac/book/$id",
            "http://libopac.xynu.edu.cn/opac/book/$id",
            "/opac/book/$id",
        )
        variants.forEach { url ->
            assertEquals("应能从 $url 取出 id", id, CatalogRepository.bookIdOf(url))
        }
    }

    @Test
    fun `non book urls yield null so callers can fall back`() {
        // 取不出 id 时 UI 会用书名兜底（CatalogScreen.favoriteIdOf），
        // 所以这里必须返回 null 而不是抛异常或返回空串。
        assertNull(CatalogRepository.bookIdOf(null))
        assertNull(CatalogRepository.bookIdOf(""))
        assertNull(CatalogRepository.bookIdOf("http://libopac.xynu.edu.cn:8080/search/searchList"))
        assertNull(CatalogRepository.bookIdOf("http://example.com/opac/book/short"))
    }

    @Test
    fun `catalog ids are 32 hex chars`() {
        // 32 位十六进制是老 OPAC 的 sourceId 形状；两个解析器都产出它，
        // 收藏才能互相打开。
        val books = CatalogSearchParser.parse(fixture("catalog_search.html")).books
        val hex32 = Regex("^[0-9a-fA-F]{32}$")
        books.forEach { book ->
            val id = CatalogRepository.bookIdOf(book.detailUrl)!!
            assertTrue("id 应为 32 位十六进制，实际 '$id'", hex32.matches(id))
        }
    }
}
