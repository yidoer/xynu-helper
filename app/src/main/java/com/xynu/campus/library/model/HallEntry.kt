package com.xynu.campus.library.model

/**
 * hall 页（`/weixin/hall/showNew`）解析出的一条功能入口。
 *
 * @param name     功能名，取 `<li><a><em/><span>名称</span></a></li>` 的 span 文本
 * @param url      目标地址，取 `toUrl('...',bind,itemId)` 第一个参数
 * @param isNeedBind 第二个参数：1 表示该项需要已绑定读者证，0 表示不需要
 * @param itemId   第三个参数：功能编号
 */
data class HallEntry(
    val name: String,
    val url: String,
    val isNeedBind: Boolean,
    val itemId: Int,
)

/**
 * 借阅身份的三种落地状态，由 mobilelib 的落地页内容特征判定
 * （禁止按字节数判定，服务端模板随时可能变）。
 */
enum class BindState {
    /** 缺 openid：命中 UA 门禁页（含 micromessenger / navigator.userAgent / isWeixin）。 */
    MISSING_OPENID,

    /** openid 已带但尚未绑读者证：落地到 `<title>用户绑定</title>` 表单页。 */
    UNBOUND_CARD,

    /** 已绑定：落地到借阅业务页。 */
    BOUND,

    /** 无法归类，按失败处理。 */
    UNKNOWN,
}
