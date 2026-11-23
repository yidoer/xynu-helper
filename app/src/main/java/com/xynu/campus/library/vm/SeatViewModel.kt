package com.xynu.campus.library.vm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xynu.campus.library.graphql.SeatGraphQlClient
import com.xynu.campus.library.graphql.SeatResponseParser
import com.xynu.campus.library.model.CurrentReservation
import com.xynu.campus.library.model.SeatLayout
import com.xynu.campus.library.model.SeatLibrary
import com.xynu.campus.library.model.SeatRule
import com.xynu.campus.library.model.SeatSession
import com.xynu.campus.library.net.MobileLibRepository
import com.xynu.campus.library.parser.HallMapParser
import com.xynu.campus.library.store.IdentityStore
import com.xynu.campus.library.util.LibraryLog
import com.xynu.campus.library.util.RefreshThrottle
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * F4 选座 ViewModel：两档共用。
 *
 * 档位判定不看用户选了哪个菜单，只看 **Cookie 是否就绪**：
 * - 无 Cookie ⇒ 基础档（只有本地提醒，绝不发请求）
 * - 有 Cookie ⇒ 高级档（列表 → 规则 → 座位图 → 预约）
 *
 * 这样设计的好处：用户永远不可能"以为在自动抢座但其实没配置"。
 */
class SeatViewModel(app: Application) : AndroidViewModel(app) {

    /** 当前档位。 */
    enum class Tier {
        /** 基础档：零凭据、零网络，只有本地提醒。 */
        BASIC,

        /** 高级档：已配置 Cookie，可查询与预约。 */
        ADVANCED,
    }

    /** 页面阶段，避免用一个巨大 boolean 组合表达流程。 */
    enum class Stage {
        /** 选馆列表 */
        LIBRARIES,

        /** 座位图 */
        LAYOUT,

        /** 当前预约（含倒计时） */
        CURRENT,
    }

    data class UiState(
        val tier: Tier = Tier.BASIC,
        val stage: Stage = Stage.LIBRARIES,
        val loading: Boolean = false,
        val libraries: List<SeatLibrary> = emptyList(),
        val rule: SeatRule? = null,
        val layout: SeatLayout? = null,
        val current: CurrentReservation? = null,
        val selectedLibId: Int = 0,
        val selectedSeatKey: String = "",
        val message: String = "",
        /** 会话失效横幅：true 时显示"请重新粘贴 Cookie"。 */
        val sessionInvalid: Boolean = false,
        /** 基础档引导：提醒时间是否已配置。 */
        val reminderHour: Int = 7,
        val reminderMinute: Int = 50,
        val reminderEnabled: Boolean = false,
        /**
         * traceint 学校码（hall 页动态提取，**不硬编码**）。
         *
         * 这是「深链能不能直接落到本校选座页」的关键参数：不带 from_code 时
         * 微信只把用户扔到 traceint 的通用首页，还得自己找学校。空串表示还没取到，
         * 此时跳转链会降级成「只开微信 + 通用网页」，不会拼出错误的深链。
         */
        val fromCode: String = "",
        /** from_code 的领取状态，用于给用户一句可读的解释。 */
        val fromCodeState: FromCodeState = FromCodeState.IDLE,
    ) {
        val hasSession: Boolean get() = tier == Tier.ADVANCED

        /** 有 from_code 才能拼出「直接落到本校」的深链。 */
        val hasFromCode: Boolean get() = fromCode.isNotBlank()
    }

    /** from_code 领取状态。 */
    enum class FromCodeState {
        /** 还没试过。 */
        IDLE,

        /** 正在拉 hall 页。 */
        LOADING,

        /** 取到了，深链可用。 */
        READY,

        /** 拉不到（离线 / 上游改动）：降级为通用跳转，不算错误。 */
        UNAVAILABLE,
    }

    private val store = IdentityStore.get(app)
    private val client = SeatGraphQlClient()
    private val mobileLib = MobileLibRepository()

    /**
     * 刷新节流：两次上游请求之间至少隔 [RefreshThrottle.DEFAULT_MIN_INTERVAL_MS]。
     *
     * 任务书明确切掉了"高频轮询"，验收标准还要求「刷新间隔 <3s 一律被钳制」。
     * 放在 ViewModel 而不是 UI 层，是因为 UI 可能有多个入口（下拉刷新、按钮、重试），
     * 逐个加 debounce 迟早漏一个。
     *
     * 注意：**必须声明在 `init` 之前**。Kotlin 的属性按声明顺序初始化，
     * 而 `init` 里的 refreshTier() 在高级档会直接调到 loadLibraries()，
     * 此时若 refreshThrottle 还没初始化就是 null，读它就是 NPE。
     * 这曾经导致「配过 Cookie 之后冷启动必闪退」——因为只有高级档才会走那条路。
     */
    private val refreshThrottle = RefreshThrottle()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 请求代际（generation counter），用于丢弃"过期响应"。
     *
     * 场景：用户在 LIBRARIES 点「刷新」，请求还在飞；此时又点了某个馆进座位图。
     * 两个请求都在飞，谁先回来不确定 —— 如果列表请求后回来，它会把
     * `stage` 之外的状态（libraries / selectedSeatKey）覆盖掉，
     * 更糟的是 `loadCurrent` 后回来会直接把 `stage` 改成 CURRENT，
     * 用户明明在座位图上选好了座位，却被"拽"到当前预约页。
     *
     * 做法：每次发起**会改 stage 或覆盖主要数据**的请求前 `++`，
     * 捕获本次代际；响应回来时若代际已变，说明已被更新的请求取代，直接丢弃。
     *
     * 为什么不用 Job.cancel()：取消是"尽力而为"的——请求可能已经拿到响应、
     * 正在往上抛，`cancel()` 拦不住它写 `_state`（suspend 点之后还有代码）。
     * 代际检查是**确定性的**：代际对不上就一个字都不写。
     * 两者可以共存，但代际是必须的那一个。
     */
    private var generation = 0

    /** 起一次新请求：递增代际并返回本次的标记。 */
    private fun nextGeneration(): Int = ++generation

    /**
     * 这次响应还算不算数。
     *
     * 注意判的是 `this.generation`，不是 `_state` —— 代际只由"发起请求"推进，
     * 不受 UI 状态变化影响，所以在协程里读它是安全的。
     */
    private fun isCurrent(gen: Int): Boolean = generation == gen

    init {
        refreshTier()
        // from_code 与档位无关：基础档的「跳微信选座」也要用它拼深链，
        // 所以两条路都会走这里，不是只在高级档才拉。
        loadFromCode()
    }

    /**
     * 从 hall 页取本校的 traceint from_code。
     *
     * 为什么必须动态取：这是学校码，写死在代码里一旦学校换码就全体失效；
     * 而且任务书 §2 明确「禁止硬编码 itemId / from_code」，入口表也由 hall 页驱动。
     *
     * 失败（断网、上游改版）**不弹错**：from_code 只是让跳转更准，缺了也能用，
     * 用户不该为这个附件参数看到红色报错。
     */
    fun loadFromCode(force: Boolean = false) {
        val current = _state.value
        if (!force) {
            // 已经拿到过就不再重复请求：这页会反复进出，每次都拉一遍太浪费
            if (current.fromCodeState == FromCodeState.READY) return
            if (current.fromCodeState == FromCodeState.LOADING) return
        }
        _state.update { it.copy(fromCodeState = FromCodeState.LOADING) }
        viewModelScope.launch {
            when (val r = mobileLib.fetchHallPage()) {
                is MobileLibRepository.Result.Ok -> {
                    val code = HallMapParser.extractFromCode(r.value).orEmpty()
                    if (code.isNotBlank()) {
                        // 落盘：提醒通知由 BroadcastReceiver 发，那时没有 ViewModel，
                        // 只能从存储里把 from_code 读回来拼深链。
                        store.saveFromCode(code)
                    }
                    _state.update {
                        it.copy(
                            fromCode = code,
                            fromCodeState = if (code.isNotBlank()) {
                                FromCodeState.READY
                            } else {
                                FromCodeState.UNAVAILABLE
                            },
                        )
                    }
                    LibraryLog.i(
                        TAG,
                        "from_code ${if (code.isNotBlank()) "resolved (len=${code.length})" else "absent"}",
                    )
                }

                is MobileLibRepository.Result.NetworkError -> {
                    _state.update { it.copy(fromCodeState = FromCodeState.UNAVAILABLE) }
                    LibraryLog.w(TAG, "hall 页不可达，降级为通用跳转：${r.message}")
                }
            }
        }
    }

    /** 只读本地状态判定档位——不联网。 */
    fun refreshTier() {
        val session = store.seatSession()
        val advanced = session != null && session.isComplete
        // 已存过 from_code（可能来自上次的 hall 页，也可能来自迁移包）就直接用，
        // 不必等网络——否则用户一进页面点「去微信」会先降级一次再变好。
        val savedFromCode = session?.fromCode?.ifBlank { null } ?: store.fromCode()
        _state.update {
            it.copy(
                tier = if (advanced) Tier.ADVANCED else Tier.BASIC,
                fromCode = if (it.fromCode.isBlank()) savedFromCode else it.fromCode,
                fromCodeState = if (it.fromCode.isBlank() && savedFromCode.isNotBlank()) {
                    FromCodeState.READY
                } else {
                    it.fromCodeState
                },
            )
        }
        if (advanced) {
            // 进入高级档时顺手刷新一次列表（也顺带验活）
            loadLibraries()
        }
    }

    // ---------- Cookie 配置 ----------

    /**
     * 校验并保存粘贴的 Cookie。
     *
     * 任务书要求：**必须先用 `list` 验活，只有不返回 40001 才落盘**。
     * 否则用户会以为配置成功，实际一用就失效。
     */
    fun submitCookie(raw: String) {
        val parsed = SeatSession.parseRawCookie(raw)
        if (parsed == null) {
            _state.update {
                it.copy(
                    message = "Cookie 里没认出 wechatSESS_ID 和 Authorization 这两项。" +
                        "请回到微信重新复制完整 Cookie（整段粘进来就行，不用自己删字段）。",
                )
            }
            return
        }

        _state.update { it.copy(loading = true, message = "", sessionInvalid = false) }

        viewModelScope.launch {
            when (val r = client.verifySession(parsed)) {
                is SeatGraphQlClient.Res.Ok -> {
                    store.saveSeatSession(parsed)
                    store.markSeatVerified()
                    LibraryLog.i(TAG, "seat session verified & saved (cookie=***)")
                    _state.update {
                        it.copy(
                            tier = Tier.ADVANCED,
                            loading = false,
                            message = "配置成功，已解锁自动选座。",
                            // 必须显式复位到第一级。
                            //
                            // 历史 bug：原来只置 tier=ADVANCED，stage 保留旧值。
                            // 用户在座位图页「清除 Cookie」→ 回基础档 → 重新粘贴有效
                            // Cookie 验活成功后，stage 仍是 LAYOUT 而 layout 已被置 null，
                            // 界面直接显示「座位图没有取到。」，无原因、无重试入口。
                            stage = Stage.LIBRARIES,
                            selectedLibId = 0,
                            selectedSeatKey = "",
                            layout = null,
                            rule = null,
                        )
                    }
                    // force=false 会让节流把这次刷新静默拦掉（见 loadLibraries 的注释），
                    // 刚验活成功这一次必须无条件取。
                    loadLibraries(force = true)
                }

                is SeatGraphQlClient.Res.SessionInvalid -> {
                    // 40001：不落盘，提示重新复制
                    _state.update {
                        it.copy(
                            loading = false,
                            message = "这个 Cookie 已失效（40001）。请重新在微信里打开图书馆，再复制一次。",
                        )
                    }
                    LibraryLog.w(TAG, "cookie rejected: 40001 (cookie=***)")
                }

                is SeatGraphQlClient.Res.NetworkError -> _state.update {
                    it.copy(loading = false, message = "网络不通，无法验证 Cookie：${r.message}")
                }

                is SeatGraphQlClient.Res.Failed -> _state.update {
                    it.copy(loading = false, message = "验证失败：${r.message}")
                }
            }
        }
    }

    fun forgetSession() {
        store.clearSeatSession()
        // 凭据已经清掉了，任何在途请求都不该再落地 —— 否则它的响应会把
        // 「已回到基础档」的状态又写回高级档数据（尤其是 SessionInvalid 分支
        // 会把 sessionInvalid=true 打到基础档页面上，用户看着一条"请重贴 Cookie"
        // 的横幅，而基础档根本没有 Cookie 输入框）。
        nextGeneration()
        _state.update {
            it.copy(
                tier = Tier.BASIC,
                // stage / selectedLibId / selectedSeatKey / loading 都必须一起复位。
                //
                // 历史 bug：这里只清了 tier 和列表类字段。用户在座位图页点「清除 Cookie」
                // → stage 仍是 LAYOUT、layout 被置 null → 重新粘贴有效 Cookie 并验活成功后
                // stage 还是 LAYOUT → 页面直接显示「座位图没有取到。」，
                // 没有原因、没有重试入口，唯一出口是左上「← 返回列表」。
                // loading 不复位还会让 LoadingBox 顶满整屏。
                stage = Stage.LIBRARIES,
                selectedLibId = 0,
                selectedSeatKey = "",
                loading = false,
                libraries = emptyList(),
                layout = null,
                rule = null,
                current = null,
                sessionInvalid = false,
                message = "已清除 Cookie，回到基础档。",
            )
        }
    }

    // ---------- 列表 ----------

    fun loadLibraries(force: Boolean = false) {
        val session = store.seatSession() ?: return
        if (!force && !refreshThrottle.tryAcquire()) {
            val waitMs = refreshThrottle.remainingMs()
            LibraryLog.i(TAG, "loadLibraries throttled (remaining=${waitMs}ms)")
            // 不能裸 return：静默拦掉会让 UI 落到「没有取到图书馆列表。可能是 Cookie
            // 已过期…」——把"被自己的节流拦了"说成"Cookie 可能过期"，方向完全错了。
            _state.update {
                it.copy(
                    loading = false,
                    message = "刷新太快了，请等 ${(waitMs / 1000) + 1} 秒再试。",
                )
            }
            return
        }
        _state.update { it.copy(loading = true, message = "") }
        val gen = nextGeneration()
        viewModelScope.launch {
            when (val r = client.listLibraries(session)) {
                is SeatGraphQlClient.Res.Ok -> {
                    if (!isCurrent(gen)) {
                        LibraryLog.d(TAG, "loadLibraries 响应已过期，丢弃")
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            libraries = r.value,
                            sessionInvalid = false,
                            // 列表是重新取的，旧选中指向的座位已不可信。
                            selectedSeatKey = "",
                        )
                    }
                }

                is SeatGraphQlClient.Res.SessionInvalid -> if (isCurrent(gen)) onSessionInvalid()
                is SeatGraphQlClient.Res.NetworkError -> if (isCurrent(gen)) _state.update {
                    it.copy(loading = false, message = "网络不通：${r.message}")
                }

                is SeatGraphQlClient.Res.Failed -> if (isCurrent(gen)) _state.update {
                    it.copy(loading = false, message = r.message)
                }
            }
        }
    }

    /** 40001 统一处理：横幅 + 保留数据不清空，只提示重贴。 */
    private fun onSessionInvalid() {
        LibraryLog.w(TAG, "session invalid (40001), asking user to re-paste (cookie=***)")
        _state.update {
            it.copy(
                loading = false,
                sessionInvalid = true,
                // 这里不设 message：SeatScreen 在 sessionInvalid=true 时会跳过 MessageCard
                // （有意去噪，横幅本身已经说明了问题）。之前设了 message 也永远不显示，
                // 同一句话存在两份实现，改文案容易只改一份。
            )
        }
    }

    // ---------- 座位图 ----------

    fun openLayout(lib: SeatLibrary) {
        val session = store.seatSession() ?: return
        _state.update {
            it.copy(
                stage = Stage.LAYOUT,
                selectedLibId = lib.libId,
                loading = true,
                message = "",
                selectedSeatKey = "",
                // 进新馆之前先清掉旧馆的规则卡片，避免取不到时串台显示。
                rule = null,
            )
        }

        val gen = nextGeneration()
        viewModelScope.launch {
            // 规则提示与座位图真正并行取。
            //
            // 历史 bug：这里原本写的是 `val ruleDeferred = client.libRule(...)`，
            // 变量名叫 Deferred、注释写"并行取"，但 libRule 是普通 suspend fun，
            // 直接调用就 await 了 —— 两次上游往返被串行化，进座位图页的耗时
            // 是"规则 + 布局"之和而不是最大值，而这段全程 loading=true 顶满屏。
            // 改成真的 async 之后才是并行。
            val (ruleRes, layoutRes) = coroutineScope {
                val rule = async { client.libRule(session, lib.libId) }
                val layout = client.libLayout(session, lib.libId)
                rule.await() to layout
            }

            // 用户可能在等待期间已经返回列表或进了别的馆：这份布局不再是当前页要的。
            if (!isCurrent(gen)) {
                LibraryLog.d(TAG, "openLayout 响应已过期，丢弃（libId=${lib.libId}）")
                return@launch
            }

            // 规则失败不该阻断座位图（它只是提示卡片），但也不能静默：
            // 40001 要触发重贴横幅，其余写进 message 留痕。
            when (ruleRes) {
                is SeatGraphQlClient.Res.Ok -> Unit // 下面和 layout 一起写
                is SeatGraphQlClient.Res.SessionInvalid -> {
                    onSessionInvalid()
                    return@launch
                }
                is SeatGraphQlClient.Res.NetworkError -> LibraryLog.w(
                    TAG, "libRule 网络失败（座位图不受影响）：${ruleRes.message}",
                )
                is SeatGraphQlClient.Res.Failed -> LibraryLog.w(
                    TAG, "libRule 失败（座位图不受影响）：${ruleRes.message}",
                )
            }

            when (layoutRes) {
                is SeatGraphQlClient.Res.Ok -> {
                    _state.update {
                        it.copy(
                            loading = false,
                            layout = layoutRes.value,
                            rule = (ruleRes as? SeatGraphQlClient.Res.Ok)?.value,
                        )
                    }
                }

                is SeatGraphQlClient.Res.SessionInvalid -> onSessionInvalid()
                is SeatGraphQlClient.Res.NetworkError -> _state.update {
                    it.copy(loading = false, message = "网络不通：${layoutRes.message}")
                }

                is SeatGraphQlClient.Res.Failed -> _state.update {
                    it.copy(loading = false, message = layoutRes.message)
                }
            }
        }
    }

    fun selectSeat(seatKey: String) {
        _state.update { it.copy(selectedSeatKey = seatKey) }
    }

    fun backToLibraries() {
        // 主动作废在途请求：用户已经离开座位图，任何还没回来的 libLayout / libRule
        // 都不该再写 layout 或把它拽回 LAYOUT。代际 +1 是最省事的"全部作废"。
        nextGeneration()
        _state.update {
            it.copy(
                stage = Stage.LIBRARIES,
                layout = null,
                selectedSeatKey = "",
                // rule 也要清：否则新馆的 libLayout 失败时会显示
                // 「上一个馆的规则提示 + 座位图没有取到」，串台。
                rule = null,
            )
        }
    }

    // ---------- 预约 ----------

    fun reserveSelectedSeat() {
        val session = store.seatSession() ?: return

        // 先取快照再原子地"取出并清空"待预约座位。
        //
        // 历史问题：原来是 `val s = _state.value` 后在协程里继续用 s.selectedSeatKey，
        // 属于 check-then-act —— 同帧双点会发两次同座位预约请求，任何迟到路径
        // 也可能拿着已过期的 key 去预约。
        // 现在把 key/libId 在清空之前拷出来，后续一律只读这两个局部变量；
        // selectedSeatKey 一并清空，保证同一座位不会被重复提交。
        val s = _state.value
        if (s.selectedSeatKey.isBlank()) {
            _state.update { it.copy(message = "先点一个空闲座位。") }
            return
        }
        if (s.loading) return // 已有请求在途，忽略这次点击（去重）

        val key = s.selectedSeatKey
        val libId = s.selectedLibId
        _state.update { it.copy(loading = true, message = "", selectedSeatKey = "") }

        viewModelScope.launch {
            when (val r = client.reserveSeat(session, libId, key)) {
                is SeatGraphQlClient.Res.Ok -> handleReserveOutcome(r.value)
                is SeatGraphQlClient.Res.SessionInvalid -> onSessionInvalid()
                is SeatGraphQlClient.Res.NetworkError -> _state.update {
                    it.copy(loading = false, message = "网络不通：${r.message}")
                }

                is SeatGraphQlClient.Res.Failed -> _state.update {
                    it.copy(loading = false, message = r.message)
                }
            }
        }
    }

    private fun handleReserveOutcome(outcome: SeatResponseParser.ReserveOutcome) {
        when (outcome) {
            is SeatResponseParser.ReserveOutcome.Success -> {
                _state.update {
                    it.copy(
                        loading = false,
                        stage = Stage.CURRENT,
                        message = "预约成功！",
                        // 成功之后必须清掉选中：layout 仍持有旧座位图，
                        // selectedSeatKey 不清会让「预约这个座位」按钮重新可点，
                        // 可对同一座位重复发预约请求。
                        selectedSeatKey = "",
                    )
                }
                loadCurrent()
            }

            SeatResponseParser.ReserveOutcome.CaptchaRequired -> {
                // 任务书要求：需要图形验证码时**明确降级**，不尝试识别
                _state.update {
                    it.copy(
                        loading = false,
                        message = "这次预约需要图形验证码。请到微信「我去图书馆」完成本次预约" +
                            "（本应用不做验证码识别，避免影响你的账号）。",
                    )
                }
            }

            SeatResponseParser.ReserveOutcome.SessionInvalid -> onSessionInvalid()

            is SeatResponseParser.ReserveOutcome.Failed -> _state.update {
                it.copy(loading = false, message = outcome.message)
            }
        }
    }

    fun loadCurrent() {
        val session = store.seatSession() ?: return
        _state.update { it.copy(loading = true) }
        val gen = nextGeneration()
        viewModelScope.launch {
            when (val r = client.currentReservation(session)) {
                is SeatGraphQlClient.Res.Ok -> {
                    // 这个分支会写 `stage = CURRENT`，是全场唯一会"替用户跳页"的地方。
                    // 如果响应回来时用户已经在别的页面（比如正在座位图上选座），
                    // 无条件写就会把人拽走。代际对不上就整条丢弃。
                    if (!isCurrent(gen)) {
                        LibraryLog.d(TAG, "loadCurrent 响应已过期，不跳 Stage.CURRENT")
                        return@launch
                    }
                    _state.update {
                        it.copy(loading = false, current = r.value, stage = Stage.CURRENT)
                    }
                }

                is SeatGraphQlClient.Res.SessionInvalid -> if (isCurrent(gen)) onSessionInvalid()
                // 以前这里是 `else ->` 把 NetworkError 和 Failed 一起吞掉：
                // 在 CURRENT 页点「刷新」，网络断了只看到转一圈然后什么都没发生，
                // 用户无法区分"确实没有预约"和"请求失败"。
                is SeatGraphQlClient.Res.NetworkError -> if (isCurrent(gen)) _state.update {
                    it.copy(loading = false, message = "网络不通：${r.message}")
                }

                is SeatGraphQlClient.Res.Failed -> if (isCurrent(gen)) _state.update {
                    it.copy(loading = false, message = r.message)
                }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = "") }
    }

    companion object {
        private const val TAG = "SeatVM"
    }
}
