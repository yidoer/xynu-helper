package com.xynu.campus.library.model

/**
 * 座位可见状态。
 *
 * **颜色由 `status` 门控，`seat_status` 只在 `status==true` 时才参与。**
 * 这段是 `ti_prereserve.js` 的原文（用 `render:function(t){return Array.isArray(t)?` 搜）：
 * ```js
 * 1 == t.type && (
 *   t.status
 *     ? ( t.defaultStyle.background = "#F36F6F",          // 红
 *         t.seat_status > 0 && (
 *           2 === t.seat_status && (bg = "#53B175"),     // 绿
 *           4 === t.seat_status && (bg = "#DADADA"),     // 灰
 *           5 === t.seat_status && (bg = "#FE9B4B")      // 橙
 *         ) )
 *     : ( bg = "#FFFFFF", border = "8rpx solid #F2F2F2", radius = "8rpx" )  // 白
 * )
 * ```
 *
 * **颜色与图例文字的对应关系**（同一 bundle 里的 `menuList`）：
 * ```js
 * menuList: [ {name:"有人",icon:a("2ad1")}, {name:"暂离",icon:a("96e2")},
 *             {name:"监督",icon:a("f300")}, {name:"可选",icon:a("bfa4")},
 *             {name:"已选",icon:a("0e77")}, {name:"入口",icon:a("d458")} ]
 * ```
 * 把上面 6 个 glyph 的 base64 解出来读 PNG 调色板（PLTE），主色是：
 *
 * | glyph  | 主色      | 图例文字 |
 * |--------|-----------|----------|
 * | `2ad1` | `#F36F6F` | 有人     |
 * | `96e2` | `#DADADA` | 暂离     |
 * | `f300` | `#FE9B4B`*| 监督     |
 * | `bfa4` | `#FFFFFF` | 可选     |
 * | `0e77` | `#53B175` | **已选** |
 * | `d458` | `#72C7F7` | 入口     |
 *
 * \* `f300` 的模块定义在线上 bundle 里被拆到了另一处，但 `render` 只用到 4 个
 * 非红色值，橙 `#FE9B4B` 是唯一没有被 `2ad1/96e2/0e77` 占用的一个，故对应「监督」。
 * 三处独立证据互相印证：颜色集合 {红,灰,橙,白,绿} == 图例词数 5（「入口」是 type>=2 的图标）。
 *
 * **所以绿色 `#53B175` 是「已选」而不是「可预约」**。
 * 真正可点的只有白底（`status==false`），见 [Seat.isFree]：
 * 上游 `clickSeat` 的门控是 `if(1==this.seatList[t].type){ if(this.seatList[t].status) return; ... }`，
 * 即**只看外层 `status`，完全不看 `seat_status`**。绿色座位点不动是正确行为。
 *
 * **曾经踩过的坑**：
 * 1. 早先只按 `seat_status` 映射，忽略了外层 `status` 门控。于是
 *    `status==true && seat_status<=1` 这一大类座位（红/灰/橙）被画成白色 ——
 *    明明占着却显示成可预约。
 * 2. 图例文案曾经把绿写成「可预约」、灰写成「不可用」、橙写成「暂离」，
 *    与上表全部错位。这不是颜色渲染 bug，是**文案 bug**：
 *    用户照图例去找绿色座位，点上去毫无反应，还以为是 App 坏了。
 */
enum class SeatVisual {
    /** `status==false`：白底 + 浅边框。上游图例「**可选**」，也是唯一可点的一档。 */
    FREE,

    /** `status==true` 且 `seat_status` 为 0/1/null：红 `#F36F6F`。上游图例「**有人**」。 */
    OCCUPIED,

    /**
     * `seat_status==2`：绿 `#53B175`。
     *
     * 上游图例写的是「**已选**」—— 这是**别人/本账号已选或已预约**的座位，
     * 不是可预约的。它 `status==true`，所以 [Seat.isFree] 为 false、点不动，
     * 这是与上游 `clickSeat` 一致的正确行为。
     *
     * 命名历史：此枚举曾叫 `AVAILABLE` 并被图例标成「可预约」，是错的。
     */
    SELECTED,

    /** `seat_status==4`：灰 `#DADADA`。上游图例「**暂离**」。 */
    AWAY,

    /** `seat_status==5`：橙 `#FE9B4B`。上游图例「**监督**」。 */
    SUPERVISED;

    /** 该状态是否用白字（深色底）。 */
    val darkBackground: Boolean get() = this != FREE && this != AWAY

    companion object {
        /**
         * 按上游三级逻辑映射。**先看 status，再看 seat_status**，顺序不能反。
         *
         * @param status 上游布尔，`false` = 空闲可预约（反向语义）
         * @param seatStatus 上游数字状态，可能是 null / 0 / 1
         */
        fun of(status: Boolean, seatStatus: Int?): SeatVisual {
            // status==false ⇒ 白底。上游此分支完全不看 seat_status。
            if (!status) return FREE
            return when (seatStatus) {
                2 -> SELECTED
                4 -> AWAY
                5 -> SUPERVISED
                // status==true 且 seat_status 为 0/1/null/未知 ⇒ 落到默认红
                else -> OCCUPIED
            }
        }
    }
}

/**
 * 座位。
 *
 * @param key 座位唯一键（`reserveSeat` 的 `seatKey`）
 * @param x 网格横坐标（1..max_x）
 * @param y 网格纵坐标（1..max_y）
 * @param type 座位类型（1 = 普通可预约座位）
 * @param status 上游布尔：`false` = 空闲可预约（**注意是反向语义**）
 * @param seatStatus 上游数字状态，映射到 [SeatVisual]
 */
data class Seat(
    val key: String,
    val name: String,
    val x: Int,
    val y: Int,
    val type: Int,
    val status: Boolean,
    val seatStatus: Int?,
) {
    /** 是否空闲可预约：`status == false`（上游语义就是反向的）。 */
    val isFree: Boolean get() = !status

    /** 是否普通座位（type==1 才画成座位块）。 */
    val isSeatBlock: Boolean get() = type == 1

    /** 可见状态（颜色）。依赖 status + seatStatus 两者，见 [SeatVisual.of]。 */
    val visual: SeatVisual get() = SeatVisual.of(status, seatStatus)

    /**
     * 非座位元素的图标语义（上游 `nowIcon`）。
     * 上游给 type 2..9 各配了一张图标；这里用字符代替，避免打包位图。
     */
    val furnitureGlyph: String?
        get() = when (type) {
            2 -> "▭"   // 桌子 desk.png
            3 -> "⇥"   // 大门 entrance.png
            4 -> "⚡"  // 插座 socket.png
            5 -> "🔒"  // 锁定/专属座位 lock.png
            6 -> "▮"   // 柱子 pillar.png
            7 -> "▤"   // 窗户 window.png
            8 -> "▥"   // 书架 bookrack.png
            9 -> "⊞"   // 插座桌子 desk_socket.png
            else -> null
        }

    /**
     * 非座位元素的人话名字。
     *
     * 名称**逐字取自上游 `tipList`**（`ti_prereserve.js`），不是我编的：
     * ```js
     * tipList:[{label:3,name:"大门"},{label:4,name:"插座(注意安全)"},
     *          {label:5,name:"锁定/专属座位"},{label:6,name:"柱子"},
     *          {label:7,name:"窗户"},{label:8,name:"书架"},{label:9,name:"插座桌子"}]
     * ```
     * 注意上游的 label 就是 type，且**没有 label 2**（桌子只有图标不带提示）。
     */
    val furnitureLabel: String?
        get() = when (type) {
            2 -> "桌子"          // 上游无 tipList 条目，按 desk.png 直译
            3 -> "大门"
            4 -> "插座(注意安全)"
            5 -> "锁定/专属座位"
            6 -> "柱子"
            7 -> "窗户"
            8 -> "书架"
            9 -> "插座桌子"
            else -> null
        }
}

/** 座位布局。 */
data class SeatLayout(
    val libId: Int,
    val libName: String,
    val maxX: Int,
    val maxY: Int,
    val seatsTotal: Int,
    val seatsBooking: Int,
    val seatsUsed: Int,
    val seats: List<Seat>,
) {
    /** 空闲座位数。 */
    val freeCount: Int get() = seats.count { it.isFree && it.isSeatBlock }

    /**
     * 上游口径的空闲数 = `seats_total - seats_used - seats_booking`。
     *
     * 两个口径**不一样**：[freeCount] 数的是当前返回的 `seats` 数组里 `status==false`
     * 的块数，而这个用服务端汇总字段。座位图可能有分页/裁剪，
     * 所以**以服务端这个为准**展示，`freeCount` 只在两者都缺时兜底。
     */
    val seatsFree: Int get() = (seatsTotal - seatsUsed - seatsBooking).coerceAtLeast(0)

    /** 是否拿到了可信的空闲数（三个汇总字段至少有一个非零）。 */
    val hasServerCounts: Boolean get() = seatsTotal > 0

    /**
     * 按 (y, x) 排成二维网格，缺位用 null 补。
     * 坐标系：x 为列、y 为行（与前端渲染一致）。
     */
    fun grid(): List<List<Seat?>> {
        if (maxX <= 0 || maxY <= 0) return emptyList()
        val byPos = seats.associateBy { it.y to it.x }
        return (1..maxY).map { y ->
            (1..maxX).map { x -> byPos[y to x] }
        }
    }

    fun seatAt(x: Int, y: Int): Seat? = seats.firstOrNull { it.x == x && it.y == y }
}

/** 图书馆条目（列表页）。 */
data class SeatLibrary(
    val libId: Int,
    val libName: String,
    val libFloor: String,
    val isOpen: Boolean,
    val seatsTotal: Int,
    val seatsUsed: Int,
    val seatsBooking: Int,
    val openTimeStr: String,
    val closeTimeStr: String,
) {
    /** 实时空闲座位数 = 总数 - 已用 - 已被预约。 */
    val seatsFree: Int get() = (seatsTotal - seatsUsed - seatsBooking).coerceAtLeast(0)
}

/** 图书馆规则提示。 */
data class SeatRule(
    val advanceBooking: Int,
    val seatTtlMinutes: Int,
    val holdTtlMinutes: Int,
    val renewMinutes: Int,
    val holdReason: String,
    val openTimeStr: String,
    val closeTimeStr: String,
    val validateTime: String,
) {
    /** 拼一句人话的规则提示。 */
    fun hint(): String = buildString {
        if (advanceBooking > 0) {
            append("可提前 $advanceBooking 天预约")
        }
        if (seatTtlMinutes > 0) {
            if (isNotEmpty()) append("；")
            append("单次可用 $seatTtlMinutes 分钟")
        }
        if (holdTtlMinutes > 0) {
            if (isNotEmpty()) append("；")
            append("超时未签到 $holdTtlMinutes 分钟自动释放")
        }
        if (openTimeStr.isNotBlank() && closeTimeStr.isNotBlank()) {
            if (isNotEmpty()) append("；")
            append("开放时间 $openTimeStr-$closeTimeStr")
        }
        if (isEmpty()) "暂无规则说明" else this@buildString
    }
}

/** 当前预约（含到期倒计时）。 */
data class CurrentReservation(
    val status: Int,
    val libId: Int,
    val libName: String,
    val seatKey: String,
    val seatName: String,
    val date: String,
    val expDate: Long,
    val expDateStr: String,
    val diffStr: String,
) {
    val isActive: Boolean get() = status > 0
}
