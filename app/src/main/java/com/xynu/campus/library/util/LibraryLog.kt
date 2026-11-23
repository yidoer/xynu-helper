package com.xynu.campus.library.util

/**
 * 模块内统一日志出口。
 *
 * 为什么不直接用 `android.util.Log`：
 * 1. 红线要求「任何日志不得出现完整凭据」，需要一个**可断言**的出口来验证脱敏；
 * 2. 纯 JVM 单测里 android.util.Log 是空壳，直连会让业务代码被迫区分环境。
 *
 * 因此做成可替换 sink：生产写 android.util.Log，测试换成内存收集器。
 *
 * ## 为什么 sink 是 `internal set`
 *
 * 原来的 `var sink` 是公开可写的：模块内任何一行代码都能把它换成
 * 「什么也不做」的实现，而且**没有任何测试会发现**——测试自己也用这个
 * 入口替换 sink，替换完还是跑绿的。
 *
 * 结果就是：日志脱敏的断言基础可以被一行代码静默废掉，
 * 而 CI 完全无感。改成 `internal set` 后，写入端只在本包可见，
 * 与测试（同模块）一致，跨模块调用方只能读不能改。
 *
 * ## 为什么 DEBUG 默认关闭
 *
 * `DEBUG` 级日志的用途是开发期排查，内容最容易夹带请求细节。
 * release 包里也照写不误的话，等于把"要不要泄露"交给每一条调用点自觉。
 * 这里用 `BuildConfig.DEBUG` 统一门控：release 直接不产出 DEBUG 条目。
 * WARN / ERROR 不受影响（线上排障需要它们）。
 */
object LibraryLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(val level: Level, val tag: String, val message: String)

    private fun writeToAndroidLog(entry: Entry) {
        when (entry.level) {
            Level.DEBUG -> android.util.Log.d(entry.tag, entry.message)
            Level.INFO -> android.util.Log.i(entry.tag, entry.message)
            Level.WARN -> android.util.Log.w(entry.tag, entry.message)
            Level.ERROR -> android.util.Log.e(entry.tag, entry.message)
        }
    }

    /**
     * 是否放行 DEBUG 级。
     *
     * 用 `internal var` 而不读 `BuildConfig.DEBUG`：单测跑在 JVM 上，
     * 读 BuildConfig 会引入对 AGP 生成类的依赖；而且测试需要能断言
     * DEBUG 条目确实被产出。生产侧由 [installDefaultSink] 按
     * BuildConfig.DEBUG 设初值。
     */
    internal var debugEnabled: Boolean = false

    /** 生产实现写 android.util.Log；测试可替换。写入端限制在本模块内。 */
    var sink: (Entry) -> Unit = { writeToAndroidLog(it) }
        internal set

    fun d(tag: String, msg: String) {
        if (!debugEnabled) return
        sink(Entry(Level.DEBUG, tag, msg))
    }

    fun i(tag: String, msg: String) = sink(Entry(Level.INFO, tag, msg))
    fun w(tag: String, msg: String) = sink(Entry(Level.WARN, tag, msg))
    fun e(tag: String, msg: String) = sink(Entry(Level.ERROR, tag, msg))

    /**
     * 生产侧初始化：按构建类型决定是否放行 DEBUG。
     *
     * 由 `LibraryModuleInit` / Application 调一次；不调也不影响功能
     * （默认关闭 DEBUG，是安全的那一侧）。
     */
    fun installDefaultSink(releaseBuild: Boolean) {
        debugEnabled = !releaseBuild
        sink = { writeToAndroidLog(it) }
    }

    /** 测试用：恢复到默认 sink（DEBUG 关闭）。 */
    fun resetSink() {
        debugEnabled = false
        sink = { writeToAndroidLog(it) }
    }
}
