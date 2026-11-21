package com.xynu.campus.data.model

/** 教务系统登录结果 */
data class JwLoginResult(
    val success: Boolean,
    val message: String = "",
    val needCaptcha: Boolean = false
)

/** 教务课程 */
data class Course(
    val name: String,          // 课程名称
    val teacher: String,       // 教师
    val dayOfWeek: Int,        // 星期 1-7
    val startSection: Int,     // 开始节次
    val endSection: Int,       // 结束节次
    val startWeek: Int,        // 开始周
    val endWeek: Int,          // 结束周
    val location: String,      // 上课地点
    val weeksText: String,     // 原始周次描述
    val colorIndex: Int,       // 用于课程块的配色
    val isOddWeeks: Boolean,   // 是否单周上课
    val isEvenWeeks: Boolean   // 是否双周上课
)

/** 教务成绩 */
data class ScoreItem(
    val courseName: String,
    val credit: String,
    val score: String,        // 最终成绩
    val gpa: String,          // 绩点
    val courseNature: String, // 课程性质
    val examType: String,     // 考试性质
    val remark: String,       // 备注（补考/重修等）
    val semester: String      // 学年学期
)

/** 学期 */
data class TermOption(
    val xnm: String,  // 学年
    val xqm: String,  // 学期 3 或 12
    val label: String
)

/** 图书馆图书 */
data class BookItem(
    val id: String,           // 图书 id（用于馆藏详情）
    val title: String,
    val author: String,
    val callNumber: String,   // 索书号
    val isbn: String,
    val publisher: String,    // 出版信息
    val detailUrl: String,
    val holdingCount: String,
    val canBorrowCount: String
)

/** 图书详情（书目页） */
data class BookDetail(
    val id: String,
    val title: String,
    val author: String,
    val callNumber: String,
    val isbn: String,
    val publisher: String,
    val publishDate: String,
    val pages: String,        // 页数/载体
    val price: String,
    val subject: String,      // 主题词
    val summary: String,      // 内容简介
    val toc: String,          // 目录
    val language: String,
    val category: String,     // 中图分类
    val holdings: List<BookHolding> = emptyList()
)

/** 图书馆藏副本 */
data class BookHolding(
    val callNumber: String,
    val barcode: String,     // 条形码
    val location: String,    // 馆藏地址
    val status: String       // 书刊状态
)

/** 借阅记录（当前在借） */
data class BorrowItem(
    val title: String,
    val author: String,
    val borrowDate: String,
    val dueDate: String,
    val renewCount: String,
    val status: String,
    val barcode: String,
    val renewUrl: String? = null
)

/** 借阅历史（已归还） */
data class BorrowHistoryItem(
    val title: String,
    val author: String,
    val borrowDate: String,
    val returnDate: String,
    val location: String,
    val barcode: String
)

/** 图书馆读者统计 */
data class ReaderStats(
    val name: String = "",
    val borrowCount: Int = 0,        // 当前在借
    val historyCount: Int = 0,       // 历史借阅
    val overdueCount: Int = 0,       // 超期未还
    val soonDueCount: Int = 0        // 7 天内到期
)

/** 收藏的图书 */
data class FavoriteBook(
    val id: String,
    val title: String,
    val author: String,
    val callNumber: String,
    val addedAt: Long = System.currentTimeMillis()
)

/** 新闻资讯 */
data class NewsItem(
    val title: String,
    val date: String,
    val url: String,
    val source: String
)

/** 图书馆读者信息 */
data class ReaderInfo(
    val name: String = "",
    val borrowCount: Int = 0,
    val dueCount: Int = 0
)

/**
 * 空闲场地（正方 V9 `cdjy/cdjy_cxKxcdlb.html?doType=query` 返回的一条）。
 * 字段名与教务 JSON 的 items[] 一一对应，便于解析与排错。
 */
data class FreeRoom(
    val name: String,          // cdmc 场地名称，如「综教2101」
    val code: String,          // cdbh 场地编号，如「LZ2101」
    val building: String,      // cdjc / jxlmc 楼栋，如「综合教学楼2」
    val floor: String,         // lch 楼层
    val category: String,      // cdlbmc 场地类别，如「智慧教室」
    val seats: Int,            // zws 座位数
    val availableSeats: Int,   // kszws1 可容纳座位
    val campus: String,        // xqmc 校区
    val available: Boolean     // sfkjy == "是"
) {
    /** 列表副标题：楼栋 + 楼层 + 座位数 */
    val subtitle: String
        get() = listOfNotNull(
            building.takeIf { it.isNotBlank() },
            floor.takeIf { it.isNotBlank() }?.let { "$it 楼" },
            seats.takeIf { it > 0 }?.let { "$it 座" }
        ).joinToString(" · ")
}

/**
 * 空闲场地查询的校区选项。
 *
 * 教务页面默认只选中一个校区（本校为「理工学院」），直接照抄该值会漏查其它校区。
 * [id] 为空串时表示「不限校区」——服务端会返回全校汇总结果。
 */
data class CampusOption(
    val id: String,
    val name: String,
    val selected: Boolean = false
) {
    /** 是否代表「全校」 */
    val isAll: Boolean get() = id.isBlank()
}
