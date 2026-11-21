package com.xynu.campus.ui.jw

import java.util.Calendar

/**
 * 学期日历：统一「日期 ↔ 周次/星期」的换算。
 *
 * 之前 `currentWeek()` 与 `dateOfWeek()` 用了两套不同的周基准：
 *  - `currentWeek`：把开学日直接当作第 1 周第 1 天（不看得是星期几）
 *  - `dateOfWeek`：把开学日先对齐到所在周的周一
 * 只要开学日不是周一，两者就会错位，表现为「顶部写着第 N 周，表头日期却是第 N±1 周」。
 *
 * 本对象把基准统一为**开学日所在周的周一**（教务周次按自然周计，这也是课表表头
 * 展示「周一 03-10」所依赖的口径），并额外提供反查，让用户能直接选日期。
 */
object TermCalendar {

    private const val WEEK_LABELS = "一二三四五六日"

    private fun normalize(cal: Calendar): Calendar = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /** Calendar.DAY_OF_WEEK(SUNDAY=1) → 1=周一 … 7=周日 */
    private fun isoDayOfWeek(cal: Calendar): Int = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7
        else -> 1
    }

    /** 开学日所在周的周一（第 1 周第 1 天），是所有换算的共同基准 */
    fun mondayOf(firstWeekStartMillis: Long): Calendar {
        val start = normalize(Calendar.getInstance().apply { timeInMillis = firstWeekStartMillis })
        start.add(Calendar.DAY_OF_YEAR, -(isoDayOfWeek(start) - 1))
        return start
    }

    /**
     * 某个日期属于第几周。
     * 早于第 1 周返回 1（不返回 0，避免调用方出现「第 0 周」）。
     */
    fun weekOf(firstWeekStartMillis: Long, dateMillis: Long): Int {
        val monday = mondayOf(firstWeekStartMillis)
        val day = normalize(Calendar.getInstance().apply { timeInMillis = dateMillis })
        val diffDays = ((day.timeInMillis - monday.timeInMillis) / 86_400_000L).toInt()
        if (diffDays < 0) return 1
        return diffDays / 7 + 1
    }

    /** 某个日期是星期几（1=周一 … 7=周日） */
    fun dayOfWeekOf(dateMillis: Long): Int =
        isoDayOfWeek(Calendar.getInstance().apply { timeInMillis = dateMillis })

    /** 第 [week] 周第 [dayOfWeek] 天对应的日期；开学日期未设置时返回 null */
    fun dateOf(firstWeekStartMillis: Long?, week: Int, dayOfWeek: Int): Calendar? {
        if (firstWeekStartMillis == null) return null
        val monday = mondayOf(firstWeekStartMillis)
        monday.add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + (dayOfWeek - 1))
        return monday
    }

    /** 今天（按本机时区归一化） */
    fun today(): Calendar = normalize(Calendar.getInstance())

    /** 周一 … 周日 */
    fun weekdayLabel(dayOfWeek: Int): String =
        "周" + WEEK_LABELS[(dayOfWeek - 1).coerceIn(0, 6)]

    /** MM-dd */
    fun monthDay(cal: Calendar): String =
        "%02d-%02d".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

    /** yyyy-MM-dd */
    fun isoDate(cal: Calendar): String =
        "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )

    /**
     * 学期显示用的周次上限：课表常见的 20 周，若当前已超出则按当前周放大。
     * 用于「第 1..N 周」这类选择器的范围，避免临近期末时选不到当周。
     */
    fun maxWeek(currentWeek: Int?): Int = maxOf(currentWeek ?: 1, 20)

    /**
     * 开学日期为 null 时返回 null，否则给出该日期属于第几周。
     * 供 ViewModel 直接使用，避免每处都写一遍判空。
     */
    fun weekOfTerm(termStartMillis: Long?, dateMillis: Long = System.currentTimeMillis()): Int? =
        termStartMillis?.let { weekOf(it, dateMillis) }
}
