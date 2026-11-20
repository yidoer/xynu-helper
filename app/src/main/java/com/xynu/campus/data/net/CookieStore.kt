package com.xynu.campus.data.net

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存 Cookie 存储（按域名隔离）。
 * 关键：saveFromResponse 必须按 cookie 名合并，而不是整体覆盖——
 * 正方教务登录时会话中多次下发不同 Cookie（JSESSIONID / route / 加密token 等），
 * 若简单覆盖会丢失关键会话 Cookie，导致"登录成功但后续查询会话失效"。
 */
object InMemoryCookieStore {
    val map = ConcurrentHashMap<String, MutableMap<String, Cookie>>()
}

object AppCookieJar : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val store = InMemoryCookieStore.map.getOrPut(url.host) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            if (cookie.expiresAt > now) {
                store[cookie.name] = cookie
            } else {
                store.remove(cookie.name)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val store = InMemoryCookieStore.map[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        val valid = store.values.filter { it.expiresAt > now }
        if (valid.size != store.size) {
            store.keys.retainAll(valid.map { it.name })
        }
        return valid
    }

    /** 获取某域名当前有效的 Cookie 头片段（供 WebView CookieManager 同步）。
     *  兼容精确域名 + 父域名回退：cookie 可能按 jwgl.xynu.edu.cn 或 .xynu.edu.cn 存储。 */
    fun cookieHeaderFor(host: String): String? {
        // 精确匹配 + 父域名聚合（例如 host=xynu.edu.cn 也匹配 .xynu.edu.cn 域）
        val parts = mutableListOf<String>()
        val now = System.currentTimeMillis()
        val exact = InMemoryCookieStore.map[host]
        if (exact != null) {
            exact.values.filter { it.expiresAt > now }.forEach { parts += "${it.name}=${it.value}" }
        }
        // 父域名 cookie：domain 可能以 . 开头
        val parent = host.split('.').drop(1).joinToString(".")
        if (parent.isNotEmpty()) {
            val store = InMemoryCookieStore.map[parent]
            if (store != null) {
                store.values.filter { it.expiresAt > now }.forEach { parts += "${it.name}=${it.value}" }
            }
        }
        return parts.distinct().takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}