package com.xynu.campus.library.graphql

import com.xynu.campus.library.model.CurrentReservation
import com.xynu.campus.library.model.Seat
import com.xynu.campus.library.model.SeatLayout
import com.xynu.campus.library.model.SeatLibrary
import com.xynu.campus.library.model.SeatRule
import org.json.JSONArray
import org.json.JSONObject

/**
 * GraphQL 响应解析。
 *
 * traceint 的响应形态固定为 `{"data":{"userAuth":{...}}}`；
 * 失败时是 `{"errors":[{"msg":"...","code":40001}],"data":{...:null}}`。
 *
 * 因此所有解析都先判错误信封，再取 `data.userAuth`，**任何一层为 null 都当空结果**，
 * 不抛异常（避免一次坏响应崩页面）。
 */
object SeatResponseParser {

    /** 40001 = 会话失效（Access Denied），是唯一需要重新贴 Cookie 的错误码。 */
    const val CODE_SESSION_INVALID = 40001

    /** 错误信封提取：返回首个错误的 (code, msg)，没有错误则 null。 */
    fun firstError(json: String?): Pair<Int, String>? {
        val obj = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val errors = obj.optJSONArray("errors") ?: return null
        if (errors.length() == 0) return null
        val first = errors.optJSONObject(0) ?: return null
        return first.optInt("code", 0) to first.optString("msg", "")
    }

    /** 是否是会话失效（40001）。 */
    fun isSessionInvalid(json: String?): Boolean =
        firstError(json)?.first == CODE_SESSION_INVALID

    private fun userAuth(json: String?): JSONObject? = runCatching {
        JSONObject(json ?: return null)
            .optJSONObject("data")
            ?.optJSONObject("userAuth")
    }.getOrNull()

    /**
     * 结构探测：这份响应里**有没有** `data.userAuth` 这一层。
     *
     * 为什么需要：`parseLibraries` 对结构不匹配返回 `emptyList()`，
     * 而"上游真的返回了空数组"也返回 `emptyList()` —— 两者在类型上不可区分。
     * 结果是上游改版（比如把 `userAuth` 改名成 `user_auth`）时，用户看到的是
     * 一句「没有取到图书馆列表」，而不是「响应结构不对，可能上游改版了」。
     *
     * 判断口径：`true` = 有 `data.userAuth` 对象，说明协议主干没变，
     * 空列表就是**真的**空；`false` = 连主干都没有，属于结构性失败。
     *
     * 注意它**不判断** `reserve.libs` 在不在。`data.userAuth` 存在但 `reserve`
     * 缺失，可能是这个账号没有选座权限（上游就是这样表达的），
     * 那不该当作"解析失败"报错——那种情况下"空"是正确语义。
     */
    fun hasUserAuthEnvelope(json: String?): Boolean = userAuth(json) != null

    // ---------- list ----------

    /**
     * 解析图书馆列表。
     *
     * 结构：`data.userAuth.reserve.libs[]`。
     * 注意同一个响应里还有 `record.libs`（已收藏的馆），列表页只取 `reserve.libs`。
     */
    fun parseLibraries(json: String?): List<SeatLibrary> {
        val reserve = userAuth(json)?.optJSONObject("reserve") ?: return emptyList()
        val arr = reserve.optJSONArray("libs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                val rt = o.optJSONObject("lib_rt")
                SeatLibrary(
                    libId = o.optInt("lib_id", 0),
                    libName = o.optString("lib_name", ""),
                    libFloor = o.optString("lib_floor", ""),
                    // `is_open` 上游是按数值用的：`if (c.is_open > 0)`
                    // （ti_prereserve.js 的图书馆下拉就是这个判断）。
                    // org.json 的 optBoolean 只认真正的 Boolean.TRUE 和字符串 "true"，
                    // 遇到 1 会返回默认值 false —— 于是"开放中"的馆被显示成未开放。
                    // 所以先按数值读，再兜底布尔。
                    isOpen = o.optInt("is_open", 0) > 0 || o.optBoolean("is_open", false),
                    seatsTotal = rt?.optInt("seats_total", 0) ?: 0,
                    seatsUsed = rt?.optInt("seats_used", 0) ?: 0,
                    seatsBooking = rt?.optInt("seats_booking", 0) ?: 0,
                    openTimeStr = rt?.optString("open_time_str", "") ?: "",
                    closeTimeStr = rt?.optString("close_time_str", "") ?: "",
                )
            }
        }.filter { it.libId > 0 }
    }

    // ---------- libLayout ----------

    /**
     * 解析座位布局。
     *
     * 两个命名空间（`reserve.libs[0].lib_layout` 与 `prereserve.libLayout`）字段一致，
     * 所以这里做兼容：先找 `reserve.libs[0].lib_layout`，找不到再找 `prereserve.libLayout`。
     */
    fun parseLayout(json: String?, fallbackLibId: Int = 0): SeatLayout? {
        val ua = userAuth(json) ?: return null

        val fromReserve = ua.optJSONObject("reserve")
            ?.optJSONArray("libs")
            ?.optJSONObject(0)
            ?.optJSONObject("lib_layout")

        val layoutObj = fromReserve
            ?: ua.optJSONObject("prereserve")?.optJSONObject("libLayout")
            ?: ua.optJSONObject("goods")
                ?.optJSONObject("reserveseat")
                ?.optJSONObject("libLayout")

        if (layoutObj == null) return null

        val seatsArr = layoutObj.optJSONArray("seats") ?: JSONArray()
        val seats = (0 until seatsArr.length()).mapNotNull { i ->
            seatsArr.optJSONObject(i)?.let { o ->
                val key = o.optString("key", "")
                if (key.isBlank()) return@let null
                Seat(
                    key = key,
                    name = o.optString("name", ""),
                    x = o.optInt("x", 0),
                    y = o.optInt("y", 0),
                    type = o.optInt("type", 0),
                    // 关键：上游 status 是布尔且**反向**（false = 空闲）
                    status = o.optBoolean("status", true),
                    seatStatus = if (o.has("seat_status")) o.optInt("seat_status", 0) else null,
                )
            }
        }

        val libObj = ua.optJSONObject("reserve")?.optJSONArray("libs")?.optJSONObject(0)
        return SeatLayout(
            libId = libObj?.optInt("lib_id", fallbackLibId) ?: fallbackLibId,
            libName = libObj?.optString("lib_name", "") ?: "",
            maxX = layoutObj.optInt("max_x", 0),
            maxY = layoutObj.optInt("max_y", 0),
            seatsTotal = layoutObj.optInt("seats_total", 0),
            seatsBooking = layoutObj.optInt("seats_booking", 0),
            seatsUsed = layoutObj.optInt("seats_used", 0),
            seats = seats,
        )
    }

    // ---------- libRule ----------

    fun parseRule(json: String?): SeatRule? {
        val o = userAuth(json)
            ?.optJSONObject("reserve")
            ?.optJSONObject("libRule")
            ?: return null
        return SeatRule(
            advanceBooking = o.optInt("advance_booking", 0),
            seatTtlMinutes = o.optInt("lib_seat_ttl", 0),
            holdTtlMinutes = o.optInt("lib_hold_ttl", 0),
            renewMinutes = o.optInt("lib_renew_time", 0),
            holdReason = o.optString("hold_reason", ""),
            openTimeStr = o.optString("open_time_str", ""),
            closeTimeStr = o.optString("close_time_str", ""),
            validateTime = o.optString("lib_validate_time", ""),
        )
    }

    // ---------- reserveSeat ----------

    /** 预约结果。 */
    sealed interface ReserveOutcome {
        /** 成功：返回预约 token。 */
        data class Success(val token: String) : ReserveOutcome

        /** 需要图形验证码 → 按任务书降级为「请到微信完成本次预约」。 */
        data object CaptchaRequired : ReserveOutcome

        /** 会话失效（40001）→ 提示重新贴 Cookie。 */
        data object SessionInvalid : ReserveOutcome

        /** 其它业务失败，带上游原文供用户判断。 */
        data class Failed(val message: String) : ReserveOutcome
    }

    /**
     * 解析预约结果（上游方法名 `reserueSeat`）。
     *
     * 判定顺序（重要）：
     * 1. 40001 → SessionInvalid
     * 2. 响应中带 captcha 相关错误/字段 → CaptchaRequired
     * 3. `data.userAuth.reserve.<方法名>` 非空 → Success
     * 4. 否则 Failed
     *
     * 方法名有两种拼写：上游 schema 里 `reserveSeat` 和 `reserueSeat`
     * 并存（字段完全相同），真正被调用的是**拼错的那个**。这里两个都读，
     * 免得哪天上游改回正确拼写就静默解析失败。
     */
    fun parseReserveOutcome(json: String?): ReserveOutcome {
        if (json.isNullOrBlank()) return ReserveOutcome.Failed("服务器无响应")

        firstError(json)?.let { (code, msg) ->
            if (code == CODE_SESSION_INVALID) return ReserveOutcome.SessionInvalid
            // 上游需要验证码时的错误文案含 captcha / 验证码
            val lower = msg.lowercase()
            if (lower.contains("captcha") || msg.contains("验证码")) {
                return ReserveOutcome.CaptchaRequired
            }
            return ReserveOutcome.Failed(msg.ifBlank { "预约失败（code=$code）" })
        }

        val ua = userAuth(json)
        val reserve = ua?.optJSONObject("reserve")
        // 用 opt() 而不是 optJSONObject()：上游成功时既可能返回字符串 token，
        // 也可能返回对象。optJSONObject 会把字符串形态直接变成 null，
        // 于是下面「字符串 token」那条分支永远进不去（曾是死代码），
        // 明明预约成功却报「预约未返回结果」。
        val node: Any? = reserve?.opt("reserueSeat")
            ?.takeUnless { it == JSONObject.NULL }
            ?: reserve?.opt("reserveSeat")?.takeUnless { it == JSONObject.NULL }
        // 成功时可能是字符串 token，也可能是对象
        when {
            node == null -> {
                // 没有结果节点：检查是否是 data 为 null 的软失败
                val dataNull = runCatching {
                    JSONObject(json).opt("data") == JSONObject.NULL
                }.getOrDefault(false)
                return if (dataNull) ReserveOutcome.Failed("预约被服务器拒绝，请稍后重试")
                else ReserveOutcome.Failed("预约未返回结果")
            }

            node !is JSONObject -> {
                val token = node.toString()
                return if (token.isBlank() || token == "null") {
                    ReserveOutcome.Failed("预约未成功")
                } else {
                    ReserveOutcome.Success(token)
                }
            }

            else -> {
                val obj = node as JSONObject
                // 对象形态：可能在 need_captcha 返回 true
                if (obj.optBoolean("need_captcha", false) || obj.optBoolean("needCaptcha", false)) {
                    return ReserveOutcome.CaptchaRequired
                }
                val token = obj.optString("token", "")
                return if (token.isNotBlank()) ReserveOutcome.Success(token)
                else ReserveOutcome.Failed(obj.optString("msg", "预约未成功"))
            }
        }
    }

    // ---------- reserve（当前预约）----------

    fun parseCurrentReservation(json: String?): CurrentReservation? {
        val o = userAuth(json)
            ?.optJSONObject("reserve")
            ?.optJSONObject("reserve")
            ?: return null
        return CurrentReservation(
            status = o.optInt("status", 0),
            libId = o.optInt("lib_id", 0),
            libName = o.optString("lib_name", ""),
            seatKey = o.optString("seat_key", ""),
            seatName = o.optString("seat_name", ""),
            date = o.optString("date", ""),
            expDate = o.optLong("exp_date", 0L),
            expDateStr = o.optString("exp_date_str", ""),
            diffStr = o.optString("diff_str", ""),
        )
    }
}
