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
