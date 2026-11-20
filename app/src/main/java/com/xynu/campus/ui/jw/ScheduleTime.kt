package com.xynu.campus.ui.jw

/**
 * 信阳师范大学作息时间表（谭山校区）。
 * 数据来源：校长办公室发布的夏/冬季作息时间表。
 *  - 夏季（5月上旬至10月上旬执行）
 *  - 冬季（10月上旬至次年5月上旬执行）
 *
 * 节次编号 1-12 与教务课表一致，仅用于展示每节课的起止时间。
 */
object ScheduleTimes {

    data class SectionTime(val start: String, val end: String)

    /** 夏季作息（谭山校区） */
    val summer = listOf(
        SectionTime("08:00", "08:45"),  // 1
        SectionTime("08:55", "09:40"),  // 2
        SectionTime("09:55", "10:40"),  // 3
        SectionTime("10:45", "11:30"),  // 4
        SectionTime("11:35", "12:20"),  // 5
        SectionTime("15:00", "15:45"),  // 6
        SectionTime("15:50", "16:35"),  // 7
        SectionTime("16:45", "17:30"),  // 8
        SectionTime("17:35", "18:20"),  // 9
        SectionTime("19:20", "20:05"),  // 10
        SectionTime("20:10", "20:55"),  // 11
        SectionTime("21:00", "21:45"),  // 12
    )

    /** 冬季作息（谭山校区） */
    val winter = listOf(
        SectionTime("08:00", "08:45"),  // 1
        SectionTime("08:55", "09:40"),  // 2
        SectionTime("10:00", "10:45"),  // 3
        SectionTime("10:55", "11:40"),  // 4
        SectionTime("14:30", "15:15"),  // 5
        SectionTime("15:25", "16:10"),  // 6
        SectionTime("16:20", "17:05"),  // 7
        SectionTime("17:15", "18:00"),  // 8
        SectionTime("19:00", "19:45"),  // 9
        SectionTime("19:55", "20:40"),  // 10
        SectionTime("20:45", "21:30"),  // 11
        SectionTime("21:35", "22:20"),  // 12
    )

    fun forSeason(isSummer: Boolean): List<SectionTime> = if (isSummer) summer else winter
}
