package com.xynu.campus.library.vm

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xynu.campus.library.crypto.OpenIdExtractor
import com.xynu.campus.library.model.BindState
import com.xynu.campus.library.net.LibraryHttpClient
import com.xynu.campus.library.net.MobileLibRepository
import com.xynu.campus.library.parser.BorrowSummaryParser
import com.xynu.campus.library.store.IdentityStore
import com.xynu.campus.library.util.LibraryLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * F1 引导向导 + F3 借阅查询页的共用状态机。
 *
 * 三态（对应任务书 §1 的落地页状态机）：
 * - 缺 openid → 走引导向导
 * - 未绑读者证 → 提示去微信绑定
 * - 已绑定 → 展示借阅业务页
 */
class BorrowViewModel(
    private val repo: MobileLibRepository,
    private val store: IdentityStore,
) : ViewModel() {

    data class UiState(
        /** 是否已完成首次引导（有 openid）。 */
        val hasOpenId: Boolean = false,
        /** 当前身份状态。 */
        val bindState: BindState = BindState.UNKNOWN,
        /** 引导向导当前步骤：0=未开始，1/2/3=三步图文。 */
        val wizardStep: Int = 0,
        /**
         * 三步是否全部完成。
         *
         * 单独一个字段而不是把 wizardStep 写成 4：
         * wizardStep 是**步骤序号**（1..3 对应三步），让它取 4 会让
         *   - UI 的 `wizardStep > step` 把三步全标成 ✓ 而没有任何一步是 current，
         *     卡片配色退化成一体，用户看不出"已经全部完成"这个独立状态；
         *   - `currentWizardTitle` 的 `wizardStep - 1` 越界后走 `last()` 兜底，
         *     把 step=4 标成第 3 步的标题。
         * 完成态是**另一个维度**，就该有另一个字段。
         */
        val wizardFinished: Boolean = false,
        /** 给用户看的中文提示（失败原因、进度）。 */
        val message: String? = null,
        /** 是否正在请求上游。 */
        val loading: Boolean = false,
        /** 已绑定时的落地 URL，供 WebView 展示。 */
        val landingUrl: String? = null,
        /** 已绑定时的 HTML 原文（解析失败时的兜底展示）。 */
        val landingHtml: String? = null,
        /** 解析出的借阅摘要 + 逐本明细；解析不出来就是 null。 */
        val summary: BorrowSummaryParser.Summary? = null,
        /** openid 是否存在，用于 UI 显示「已绑定」徽标。 */
        val openidMasked: String? = null,
    ) {
        /** 引导向导的文案，三步固定文案放这里方便测试。 */
        val wizardTitles: List<String> = listOf(
            "打开微信，进入图书馆公众号",
            "进入「个人中心 → 借阅信息」",
            "点右上角「···」，选择复制链接",
        )

        /** 当前步骤的标题（步骤从 1 开始展示）。 */
        val currentWizardTitle: String
            get() = wizardTitles.getOrElse(wizardStep - 1) { wizardTitles.last() }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val identity = store.borrowIdentity()
        _state.update {
            it.copy(
                hasOpenId = identity != null,
                openidMasked = identity?.let { id -> OpenIdExtractor.mask(id.openid) },
            )
        }
        // 本地有身份就立刻去上游确认一次。
        // 不自动查的话页面会停在 bindState=UNKNOWN，UI 只能显示「还没有借阅身份」，
        // 而下方又显示「本机保存的身份：openK***」——同一屏自相矛盾，
        // 用户会以为身份没保存成功。
        if (identity != null) refresh()
    }

    /** F1 步骤推进。 */
    fun nextWizardStep() {
        _state.update {
            it.copy(
                wizardStep = (it.wizardStep + 1).coerceAtMost(WIZARD_LAST_STEP),
                message = null,
            )
        }
    }

    fun previousWizardStep() {
        _state.update {
            it.copy(
                wizardStep = (it.wizardStep - 1).coerceAtLeast(0),
                // 回退即视为"未完成"，否则用户会看到"已完成 + 停在第一步"的矛盾态。
                wizardFinished = false,
                message = null,
            )
        }
    }

    /**
     * 用户点「我复制好了」：读剪贴板 → 提取 → 上游校验 → 入库。
     *
     * 注意 Android 10+ 的后台剪贴板限制：**必须在前台由用户点击触发**，
     * 所以这个方法只能从 UI 的 onClick 调用。
     */
    fun onClipboardReadRequested(ctx: Context) {
        val clipboard = runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
        }.getOrNull()

        submitLink(clipboard)
    }

    /**
     * 直接提交用户**手输/长按粘贴**的链接文本。
     *
     * 为什么需要这条路：Android 12+ 起后台读剪贴板被系统拒绝，Android 15 上
     * adb 也写不进剪贴板——导致「我复制好了」这条主路径没法在真机上端到端验证，
     * 用户在剪贴板权限被系统收紧的机型上也会卡住。粘贴框让这条路永远有兜底：
     * 用户长按输入框手贴一次，后续流程（提取 → 校验 → 入库）完全相同。
     */
    fun onSubmitLinkText(raw: String) {
        submitLink(raw)
    }

    private fun submitLink(clipboard: String?) {
        when (val extracted = OpenIdExtractor.extract(clipboard)) {
            is OpenIdExtractor.Result.Failed -> {
                _state.update { it.copy(message = extracted.reason.message) }
            }

            is OpenIdExtractor.Result.Success -> {
                verifyAndSave(extracted.openid)
            }
        }
    }

    private fun verifyAndSave(openid: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = "正在校验身份…") }

            when (val r = repo.probeBindState(ITEM_BORROW, openid)) {
                is MobileLibRepository.Result.NetworkError -> {
                    LibraryLog.w(TAG, "probe failed: ${r.message}")
                    _state.update {
                        it.copy(loading = false, message = "网络不通：${r.message}，请稍后重试")
                    }
                }

                is MobileLibRepository.Result.Ok -> {
                    val probe = r.value
                    when (probe.state) {
                        // 缺 openid 说明 openid 本身没被接受 → 不算成功
                        BindState.MISSING_OPENID -> {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    message = "这段链接里的 openid 没有被服务器接受，请重新复制一次",
                                )
                            }
                        }

                        // 未绑读者证：openid 有效，可以先入库，再引导去微信绑定
                        BindState.UNBOUND_CARD -> {
                            store.saveBorrowIdentity(openid)
                            LibraryLog.i(TAG, "openid accepted, card not bound: ${OpenIdExtractor.mask(openid)}")
                            _state.update {
                                it.copy(
                                    loading = false,
                                    hasOpenId = true,
                                    bindState = BindState.UNBOUND_CARD,
                                    openidMasked = OpenIdExtractor.mask(openid),
                                    // 三步走完了 ⇒ wizardStep 停在最后一步（3）+ 完成标记。
                                    // 不再写 4。
                                    wizardStep = WIZARD_LAST_STEP,
                                    wizardFinished = true,
                                    message = "身份已保存，但还没绑定读者证。请到微信「图书馆公众号 → 个人中心」绑定后再回来。",
                                )
                            }
                        }

                        BindState.BOUND -> {
                            store.saveBorrowIdentity(openid)
                            LibraryLog.i(TAG, "openid bound: ${OpenIdExtractor.mask(openid)}")
                            _state.update {
                                it.copy(
                                    loading = false,
                                    hasOpenId = true,
                                    bindState = BindState.BOUND,
                                    openidMasked = OpenIdExtractor.mask(openid),
                                    wizardStep = WIZARD_LAST_STEP,
                                    wizardFinished = true,
                                    landingHtml = probe.html.takeIf { h -> h.isNotBlank() },
                                    landingUrl = probe.landingUrl,
                                    summary = BorrowSummaryParser.parse(probe.html),
                                    message = null,
                                )
                            }
                        }

                        BindState.UNKNOWN -> {
                            _state.update {
                                it.copy(loading = false, message = "无法识别服务器返回，请稍后重试")
                            }
                        }
                    }
                }
            }
        }
    }

    /** F3：刷新借阅页当前状态。 */
    fun refresh() {
        val openid = store.borrowIdentity()?.openid ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            when (val r = repo.probeBindState(ITEM_BORROW, openid)) {
                is MobileLibRepository.Result.NetworkError -> _state.update {
                    it.copy(loading = false, message = "网络不通：${r.message}")
                }

                is MobileLibRepository.Result.Ok -> {
                    val probe = r.value
                    _state.update {
                        // 完成态必须跟着最新的 bindState 重算。
                        //
                        // 以前 refresh() 从不碰 wizardStep：服务端侧解绑读者证之后
                        // 再回到向导页，仍然是"三步全 ✓ + 无 current"的陈旧态。
                        it.copy(
                            loading = false,
                            bindState = probe.state,
                            landingHtml = probe.html.takeIf { h -> h.isNotBlank() },
                            landingUrl = probe.landingUrl ?: ITEM_LANDING,
                            summary = BorrowSummaryParser.parse(probe.html),
                            wizardStep = if (probe.state == BindState.BOUND) {
                                WIZARD_LAST_STEP
                            } else {
                                it.wizardStep
                            },
                            wizardFinished = probe.state == BindState.BOUND,
                        )
                    }
                }
            }
        }
    }

    /** 解绑本地身份（不等于上游解绑，上游解绑去微信做）。 */
    fun forgetIdentity() {
        store.clearBorrowIdentity()
        _state.update {
            it.copy(
                hasOpenId = false,
                bindState = BindState.UNKNOWN,
                openidMasked = null,
                wizardStep = 0,
                wizardFinished = false,
                landingHtml = null,
                landingUrl = null,
                summary = null,
                message = "已清除本机保存的身份",
            )
        }
    }

    companion object {
        private const val TAG = "BorrowVM"

        /** 向导最后一步的序号（三步图文，故为 3）。 */
        const val WIZARD_LAST_STEP = 3

        /** 借阅信息 itemId（实测 bind=1）。 */
        const val ITEM_BORROW = 20302
        const val ITEM_LANDING =
            "http://libopac.xynu.edu.cn:8080/sms/opac/user/lendStatus.action?schoolid=968"
    }
}
