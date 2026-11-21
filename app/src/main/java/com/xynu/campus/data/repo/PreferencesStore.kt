package com.xynu.campus.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xynu.campus.data.model.FavoriteBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

private val Context.prefsStore by preferencesDataStore(name = "xynu_prefs")

/**
 * 应用偏好存储（非敏感）：
 *  - 开学日期（用于课表自动定位当前周）
 *  - 选中的学期（课表/成绩联动）
 *  - 收藏书架
 */
object PreferencesStore {

    private val KEY_TERM_START = longPreferencesKey("term_start_millis")
    private val KEY_SELECTED_TERM = stringPreferencesKey("selected_term")        // 课表学期
    private val KEY_SELECTED_SCORE_TERM = stringPreferencesKey("selected_score_term")  // 成绩学期
    private val KEY_FAVORITES = stringPreferencesKey("favorite_books")           // JSON 数组
    private val KEY_ROOMS_CAMPUS = stringPreferencesKey("rooms_campus_id")       // 空教室上次选中的校区

    // ================ 开学日期 ================

    /** 保存开学日期（本学期的第 1 周第 1 天，毫秒时间戳） */
    suspend fun setTermStart(ctx: Context, millis: Long) {
        ctx.prefsStore.edit { it[KEY_TERM_START] = millis }
    }

    fun termStart(ctx: Context): Flow<Long?> = ctx.prefsStore.data.map { it[KEY_TERM_START] }

    suspend fun termStartOnce(ctx: Context): Long? =
        ctx.prefsStore.data.map { it[KEY_TERM_START] }.first()

    /**
     * 计算当前是第几周（1 起）。开学日期未设置返回 null。
     *
     * 基准与 [com.xynu.campus.ui.jw.TermCalendar] 一致：开学日所在周的周一为
     * 第 1 周第 1 天。此前这里把开学日本身当作第 1 周第 1 天，而课表表头的
     * `dateOfWeek` 又按「对齐到周一」算，开学日不在周一时两者会错开一周。
     */
    fun currentWeek(termStartMillis: Long?, now: Calendar = Calendar.getInstance()): Int? {
        if (termStartMillis == null) return null
        return com.xynu.campus.ui.jw.TermCalendar.weekOf(termStartMillis, now.timeInMillis)
    }

    /** 当前是星期几（1=周一 … 7=周日），与 Course.dayOfWeek 对齐 */
    fun todayDayOfWeek(now: Calendar = Calendar.getInstance()): Int {
        return when (now.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
    }

    /**
     * 根据开学日期和当前周，给出本周第 N 天（dayOfWeek 1-7）的日期。
     * 用于课表顶部展示"周一 03-10"这样的行。基准统一由 [com.xynu.campus.ui.jw.TermCalendar] 提供。
     */
    fun dateOfWeek(termStartMillis: Long?, week: Int, dayOfWeek: Int): Calendar? =
        com.xynu.campus.ui.jw.TermCalendar.dateOf(termStartMillis, week, dayOfWeek)

    // ================ 学期选择 ================

    suspend fun setSelectedTerm(ctx: Context, xnm: String, xqm: String, label: String) {
        ctx.prefsStore.edit { it[KEY_SELECTED_TERM] = "$xnm|$xqm|$label" }
    }

    suspend fun selectedTerm(ctx: Context): Triple<String, String, String>? {
        val v = ctx.prefsStore.data.map { it[KEY_SELECTED_TERM] }.first() ?: return null
        val parts = v.split("|")
        return if (parts.size >= 3) Triple(parts[0], parts[1], parts[2]) else null
    }

    // ================ 成绩学期 ================

    suspend fun setSelectedScoreTerm(ctx: Context, xnm: String, xqm: String, label: String) {
        ctx.prefsStore.edit { it[KEY_SELECTED_SCORE_TERM] = "$xnm|$xqm|$label" }
    }

    suspend fun selectedScoreTerm(ctx: Context): Triple<String, String, String>? {
        val v = ctx.prefsStore.data.map { it[KEY_SELECTED_SCORE_TERM] }.first() ?: return null
        val parts = v.split("|")
        return if (parts.size >= 3) Triple(parts[0], parts[1], parts[2]) else null
    }

    // ================ 空教室校区 ================

    /**
     * 空教室页面上次选中的校区。
     *
     * 校区之间数据完全独立（各校区之和不等于「全校」的一个模糊范围，而是互不重叠），
     * 所以不提供「全校」选项；首次进入由调用方落到主校区，之后沿用用户的选择。
     */
    suspend fun setRoomsCampus(ctx: Context, campusId: String) {
        ctx.prefsStore.edit { it[KEY_ROOMS_CAMPUS] = campusId }
    }

    suspend fun roomsCampusOnce(ctx: Context): String? =
        ctx.prefsStore.data.map { it[KEY_ROOMS_CAMPUS] }.first()

    // ================ 收藏书架 ================

    fun favorites(ctx: Context): Flow<List<FavoriteBook>> = ctx.prefsStore.data.map { prefs ->
        val raw = prefs[KEY_FAVORITES] ?: return@map emptyList()
        parseFavorites(raw)
    }

    suspend fun favoritesOnce(ctx: Context): List<FavoriteBook> =
        ctx.prefsStore.data.map { prefs ->
            prefs[KEY_FAVORITES]?.let { parseFavorites(it) } ?: emptyList()
        }.first()

    suspend fun toggleFavorite(ctx: Context, book: FavoriteBook) {
        ctx.prefsStore.edit { prefs ->
            val raw = prefs[KEY_FAVORITES]
            val list = raw?.let { parseFavorites(it) }?.toMutableList() ?: mutableListOf()
            val idx = list.indexOfFirst { it.id == book.id }
            if (idx >= 0) list.removeAt(idx) else list.add(0, book)
            prefs[KEY_FAVORITES] = serializeFavorites(list)
        }
    }

    suspend fun isFavorite(ctx: Context, bookId: String): Boolean {
        val raw = ctx.prefsStore.data.map { it[KEY_FAVORITES] }.first() ?: return false
        return parseFavorites(raw).any { it.id == bookId }
    }

    private fun parseFavorites(raw: String): List<FavoriteBook> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FavoriteBook(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    author = o.optString("author"),
                    callNumber = o.optString("callNumber"),
                    addedAt = o.optLong("addedAt", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeFavorites(list: List<FavoriteBook>): String {
        val arr = JSONArray()
        list.forEach { b ->
            val o = JSONObject()
            o.put("id", b.id)
            o.put("title", b.title)
            o.put("author", b.author)
            o.put("callNumber", b.callNumber)
            o.put("addedAt", b.addedAt)
            arr.put(o)
        }
        return arr.toString()
    }
}
