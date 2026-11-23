package com.xynu.campus.data

import android.util.Log

/**
 * 数据层「单项失败不影响整体」的异常处理。
 *
 * 解析器里有大量这种结构：一条记录字段坏掉就跳过这一条，让其余记录照常返回。
 * 原来这些位置写的是 `catch (_: Exception) { }` —— 行为上没错，但线上出问题时
 * logcat 里连「哪一行、什么异常」都没有，只能靠猜。
 *
 * 这里只补一行 warning，**不改变控制流**：异常仍然被吞掉，列表该跳过还是跳过。
 * 不记任何业务参数（课程名、书名等），避免把用户数据写进日志。
 *
 * 注意：不要在「必须向上传递」的位置用它 —— 那种地方应该显式 catch 后 rethrow。
 */
internal fun Exception.logSwallowed(tag: String, what: String) {
    Log.w(tag, "$what 单项失败（已跳过）：${javaClass.simpleName}: $message")
}
