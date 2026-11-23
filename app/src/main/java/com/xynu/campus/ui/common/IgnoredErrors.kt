package com.xynu.campus.ui.common

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * 处理「失败了也不影响主流程」的异常（例如保存偏好、清理凭据失败）。
 *
 * 以前这些地方写的是 `catch (_: Exception) { }`，有两个问题：
 * 1. 失败完全无痕迹，出问题时 logcat 里什么都查不到；
 * 2. 协程被取消时抛出的 [CancellationException] 也被一起吞掉，
 *    协程会在「已取消」状态下继续往下跑。
 *
 * 用法：`catch (e: Exception) { e.ignoreOrRethrowCancellation(TAG, "保存学期") }`
 * 对用户可见的行为不变：非取消类异常仍然被忽略，只是多记一条警告日志。
 *
 * @param rethrowCancellation 清理类收尾代码（如退出登录后还要清 Cookie）传 false，
 *        保证即使协程正被取消，后面的同步清理也照常执行，和原来完全一致。
 */
internal fun Exception.ignoreOrRethrowCancellation(
    tag: String,
    what: String,
    rethrowCancellation: Boolean = true,
) {
    if (this is CancellationException) {
        if (rethrowCancellation) throw this
        return
    }
    // 只记异常类型和消息，不记任何业务参数，避免把账号等信息写进 logcat。
    Log.w(tag, "$what 失败（已忽略）：${javaClass.simpleName}: $message")
}
