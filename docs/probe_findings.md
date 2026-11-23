# 上游实测报告（探针阶段 / M0 前置）

> 本文件只记录**实测到的事实**，与《校园图书馆助手 v2 提示词》冲突之处单独列在 §D，供评审决定，
> 不擅自按提示词"修正"。所有探针原始响应保存在 `tools/probe/`。

## 0. 环境与限制

| 项 | 值 |
| --- | --- |
| 开发机 | Windows，可直连公网 |
| 模拟器 | `emulator-5554`（Android 15 / API 35），eth0 10.0.2.15，网关 10.0.2.2 可达，**但 DNS 解析失败、ICMP 出网被拦** |
| 校区网络 | 无。`libspace.xynu.edu.cn`(222.21.62.167) TCP 80/443 均 refuse |
| 已绑定读者证账号 | 无 |
| 可用 UA 注入 | 必须用 `curl.exe`；`Invoke-WebRequest` 会拒绝含 `MicroMessenger/8.0.43(0x28002B36) NetType/WIFI Language/zh_CN` 的 UA 头 |

## A. 超星移动图书馆（借阅模块唯一上游）

Base: `http://mobilelib.wx.chaoxing.com`，`wxProductId/pid=1512`，`schoolId=968`，`areaid=274`。
`openid` 全程走 URL 参数，**不依赖 Cookie**，因此纯 OkHttp 即可，不需要 WebView 代理取 Cookie。

### A.1 `GET /weixin/hall/showNew?pid=1512&type=8`（hall_map 来源）

- 200，27494 bytes；**带不带微信 UA 都返回同一页面**（hall 页本身没有 UA 门禁）。
- 解析规则：`toUrl\('([^']+)',(\d),(\d+)\)` 取 (url, isNeedBind, itemId)，标签取同 `<li>` 相邻文本。
- 实测解析结果（与本次改造相关的条目）：

| itemId | 名称 | isNeedBind | 目标 |
| --- | --- | --- | --- |
| 28277 | 座位预约 | 0 | `https://wechat.v2.traceint.com/index.php/reserve/index.html?f=wechat&from_code=WwsCBVIJAgs%3D` |
| 25116 | 静音仓预约 | 0 | `http://libspace.xynu.edu.cn/h5/#/login` |
| 30841 | 研讨间预约 | 0 | `http://libspace.xynu.edu.cn/h5/#/login` |
| 20301 | 读者证绑定 | 1 | `.../weixin/userBind/showBindAndCheck.action` |
| 20290 | 读者证解绑 | 1 | `.../weixin/userBind/unBindForWxHall.action` |
| 20302 | 借阅信息 | 1 | `http://libopac.xynu.edu.cn:8080/sms/opac/user/lendStatus.action?schoolid=968` |
| 20304 | 馆藏查询 | 0 | `http://libopac.xynu.edu.cn:8080/sms/opac/search/showiphoneSearch.action?appId=1000&schoolid=968` |
| 20306 | 手机借阅 | — | open.weixin OAuth → `mp2.xclms.com/invengoapp` |
| 20316 / 20315 / 31328 | 数字资源 / 校外访问 / 读者荐书 | 0 | `http://222.21.62.201:8011/opac/...` |
| 32107 / 20330 / 20318 / 20311 / 20319 | 馆长信箱 / 图书馆主页 / 新闻公告 / 党建专栏 / 资源动态 | 0 | 校内门户链接 |

> hall_map 必须**动态解析 + 1h 缓存**，禁止硬编码：本次实测已经证明硬编码的 itemId 会错（见 §D-1）。

### A.2 UA 门禁页

- 触发条件：`openItemNeedBind` 且 **`openid` 为空** → 302 → `open.weixin.qq.com` OAuth → 落回 1050 bytes 的拦截页。
- 该页面特征：含 `navigator.userAgent` 与 `micromessenger` 检测脚本。
- **判定必须按内容，不能按体积**（提示词写 1043 bytes，实测 1050 bytes；服务端模板随时会变）。

### A.3 `openItemNeedBind` 与本地校验

| 请求 | 结果 |
| --- | --- |
| `itemId=20301` + 空 openid | 302 → OAuth → 1050B 门禁页（**不是**成功，必须当作"缺 openid"信号） |
| `itemId=20301` + 假 openid `oABCDEFGHIJKLMNOPQRSTUVWXYZ` | 302 → `showUserBind.action?...&code=null` → 5391~5396B 登录表单 |
| `itemId=20302` + 假 openid | 同样 302 → `showUserBind`（未绑定时回落到绑定流程） |
| `itemId=20290` + 假 openid | 200，1104B，`unBindForWxHall.action` 页含「您还没有绑定账号，无需解绑」 |

结论：**本地无法判定 openid 真伪**，只能判定"格式合法 / 上游是否接受"。真正的鉴权点在上游
`showUserBind`（回显 openid）与 `bind`（写入 openid）。这一点与提示词 F1「本地校验」一致，但
表述需澄清为"本地做格式与回显一致性校验，不做真伪断言"。

### A.4 `showUserBind.action`

- 回显：`<input class="hidparam" type="hidden" name="openid" value="oX..."/>`
- 同时下发：`schoolId=968`、`areaid=274`、`userType=0`、`bindUrl=/weixin/userBind/bind`、表单字段 `userName` / `passWord`
- **AES key 正则 `CryptoJS\.enc\.Utf8\.parse\("([^"]{16})"\)` 在本页未命中** → 待复核（见 §E-2）

### A.5 绑定 / 换绑 / 解绑

| 端点 | 实测 |
| --- | --- |
| `POST /weixin/userBind/bind`（错误参数） | 200，1061B，「出错啦 / 参数错误」 |
| `GET /weixin/other/changeBind1043?openid=&card=<cardId>&type=1` | 404 网页走丢了 |
| `GET /weixin/userBind/unBindForWxHall.action` | 见 A.3，未绑定时给中文提示页 |

- hall 页内联 JS 的 `handleBindclick` 会读 `data-card-id` 与 `data-is-bound`；`type=1` 解绑、`type=0` 换绑。
- **`cardId` 只在真正绑定后存在**，所以 `changeBind1043` 只能在真实已绑定账号下验证 → 归入闸口1。

## B. libspace（盛卡恩 / `https://libspace.xynu.edu.cn/h5/#/login`）

- 独立账号体系，与微信无任何关系；hash 路由 SPA（`#/login`）。
- 本环境 222.21.62.167 的 80/443 全部 refuse，**"仅校园网可用"提示是这里唯一可测的分支**。
- 先验接口（沿用盛卡恩模板，未在本校实测）：
  `POST {BASE}/login` → `data._hash_.access_token`；`GET /areas?tree=1`；
  `GET /space_time_buckets?area=&day=`；`GET /spaces_old?area=`；`POST /spaces/<seatId>/book`；`GET /profile/books`。

## C. traceint「我去图书馆」（真正的座位预约）

- `https://wechat.v2.traceint.com/index.php/reserve/index.html?f=wechat&from_code=...`
- 入口需 `open.weixin.qq.com/connect/oauth2/authorize?appid=wx2996d437cd442527` 授权 → **App 内无法完成**
  （提示词已把微信 OAuth 列为非目标）。

## D. 与提示词 v2 的冲突清单（需评审决定，不擅自处理）

1. **itemId 语义错位**：提示词写 `25116=座位`、`30841=研讨间`；实测 `25116=静音仓预约`、
   `30841=研讨间预约`，而 `座位预约=28277` 指向 traceint（需微信 OAuth）。
2. **UA 门禁范围**：提示词称上游必须 `MicroMessenger` UA；实测 hall / showUserBind /
   openItemNeedBind(带 openid) 用普通 Chrome UA 也正常。仍统一注入微信 UA（对其他入口是必要的），
   但不要把"UA 校验"当成门禁判定依据。
3. **门禁页体积**：提示词 1043B，实测 1050B → 按内容特征判定。
4. **`openItemNeedBind(itemId=20301)` 当成本地校验**：空 openid 时它 302 去 OAuth，必须识别为
   "缺 openid / 需引导"，不能当成功。
5. **选座目标系统二义性**：见 §D-1，libspace 侧实际只有静音仓与研讨间。
6. **白名单 host 数**：提示词 v4 要求「恰好 4 个 host」，实测要应用内渲染借阅明细与馆藏，
   必须把 `libopac.xynu.edu.cn` 加进来 → 现为 **5 个**。已按用户「功能性至上」的决定落地，
   详见 §I.5。

## E. 待闸口1（真机只读冒烟）验证项

1. `hall/showNew` 真机解析条目数与 itemId 集合与上表一致。
2. `showUserBind` 页内 AES key 提取（本机未命中，疑与页面状态/Cookie 有关）→ 抓到真实页面后定正则。
3. 真实已绑定账号下 `changeBind1043` 的 `cardId` 与 `type=0/1` 行为、解绑后 `is_bound` 变化。
4. 模拟器需先修 DNS（`-dns-server` 或走代理）才能跑上游请求。

## F. 对实现的既有结论

- 借阅模块（F1/F2/F3）**完全不依赖校园网**，可以现在就把 M0→M2 做完并写完整测试。
- 选座模块（F4）在闸口2（字段映射表评审）通过前，只允许写 `SeatVendor` 接口与 `SkalibraryAdapter`，
  `CapturedAdapter` 的 UI 一律不动。
- 仓库现状需清理：`ACCESS_NETWORK_STATE` 权限、`allowBackup="true"`、
  `network_security_config` 里与本次无关的明文 host（17wanxiao / hzsun / 10.0.2.2 组）。

## G. 实现阶段回填（M0–M5 落地时的新事实）

以下每条都是写代码/写测试时**用真实 fixture 撞出来的**，与前面的探测记录有出入时以本节为准。

### G-1 `hall.html` 的 `toUrl` 条目是 16 个，不是 15 个

早期探测统计为 15（见 §6 与验收标准 1），实测 `app/src/test/resources/fixtures/hall.html`（27022B）
里 `toUrl(...)` 共出现 **16** 次。推测是探测之后学校新增了一个入口。

- 影响面：仅测试里的断言数字（`LibraryFixtureTest` 断言 16）。解析器本身不硬编码条目数，
  服务端再变也只是测试数字要跟着改。
- 复核方式：`Select-String -Path fixtures\hall.html -Pattern "toUrl" | Measure-Object`。

### G-2 GraphQL 文档在打包产物里是「JS 对象字面量」，不是 JSON

`seatMap.chunk.js` / `pages-*.js` 里写的是 `{list:"query list {...}", libLayout:"..."}`
——**key 没有引号**。任何"按 JSON 解析"的提取器都会失败。

- 正确做法：以 `"$key:\""` 作为锚点，文档起点 = `start + key.length + 2`。
- 这个偏移量踩过一次坑：先写成 `+3`、`+4` 都错，因为 `start` 落在 key 的 `s` 上。

### G-3 `getStep0` 与 `getStep` 是两个不同 query，前缀相同

- `getStep0` 查 `getNum` + `captcha{code,data}`
- `getStep` 查 `queuUrl/successUrl/endTime`

按 `startsWith("query getStep")` 匹配会拿到错的那一个（症状：断言里前后缀一致但内容不同）。
→ 已拆成两个常量，并加了一条「两者必须不同」的测试。

### G-4 `showUserBind.html` 的 AES key 锚点实测形态

```js
let keys = CryptoJS.enc.Utf8.parse("aB3#dF7$eG2!hJ5&")
```

正则 `Utf8\.parse\(\s*["']([^"']{8,32})["']\s*\)` 取到的是 **16 字符** key。
§E-2 记的"本机未命中"是因为当时抓到的是 gate 页（1043B）而非 showUserBind 页（5164B）。

### G-5 Robolectric 没有 AndroidKeyStore provider

`EncryptedSharedPreferences` 在 JVM 单测里必抛
`KeyStoreException: ... NoSuchAlgorithmException`。这不是配置问题，无法靠加依赖绕过。

→ 加密存储的测试已移到 `app/src/androidTest/`（`EncryptedIdentityStoreTest`，5 条，设备上跑）。

### G-6 `wechat.v2.traceint.com` 的 TLS 在部分 Windows 环境握手失败

`schannel: CRYPT_E_REVOCATION_OFFLINE 0x80092013`。

→ 抓 fixture 时 `curl.exe` 需加 `--ssl-no-revoke`；App 端不受影响（走 Android 的 TLS 栈）。

### G-7 两个"看起来能用但其实不能用"的选座入口

| 入口 | 解析 | 实测 | 结论 |
|---|---|---|---|
| `libseat.xynu.edu.cn` | 210.43.24.6 | 标题「信阳师范大学图书馆 · 座位预约管理后台」，需 `studentNo` + 密码 + 姓名 | 无稳定公开接口，且不在四域白名单内 → 只留 TODO 注释 |
| `libspace.xynu.edu.cn` | 222.21.62.167 | 80/443 refuse；8080 restify 返回 `{"status":false,...}` | 入口无效 |

→ 结论不变：**traceint「我去图书馆」是唯一可行的选座目标**。

### G-8 `from_code` 必须先 URL-decode 再 base64

`hall.html` 里 traceint 入口的 `from_code` 形如 `WwsCBVIJAgs%3D`，
在 hall URL 里被 URL 编码过。直接 base64 解码会失败。

→ 已按「先 URL 解码、再 base64」断言可解码；且**不硬编码**（学校相关，会变）。

### G-9 `LandingPageClassifier` 不能用 `<title>` 判定 gate 页

1043B 的 gate 页是 **GBK** 编码，Jsoup 按 UTF-8 解出的 `.title()` 是乱码。
只有「用户绑定」页（5164B）是 UTF-8，title 对比才有效。

→ gate 判定改为要求 ≥2 个内容特征标记（`micromessenger` + `navigator.userAgent`），
跨编码稳定。这条也被 `LibraryFixtureTest` 覆盖。

### G-10 traceint 的失败响应形状（用于 40001 判定）

```json
{"errors":[{"msg":"Access Denied!","code":40001}],"data":{"userAuth":null}}
```

注意 `data.userAuth` 是 `null` 而**不是**缺失——所以判定顺序必须是
「先看 `errors[].code == 40001`，再看 `data.userAuth`」，
否则 null 会被当成"字段缺失"而误判为其它错误。

### G-11 ~~需要 `Origin` + `Referer` + `App-Version`，缺一不可~~ 【已作废，见 K.2】

> **这条结论是错的，已由真机实测推翻。**
>
> 原文声称接口校验 `Origin: https://web.traceint.com`、
> `Referer: https://web.traceint.com/web/index.html`，且要求
> `App-Version: 2.0.11` 加含 `MicroMessenger` 的 UA。
>
> 实际：
>
> - `web.traceint.com` 这个域名在**全部 25 份上游 fixture 里一次都没出现过**，
>   是臆造出来的。真实 API 主机就是 `wechat.v2.traceint.com`。
> - `App-Version` 的真实值是 **`2.2.9`**（bundle 模块 `2afd` 里的
>   `{"version":"2.2.9"}`，`uni.request` 唯一显式带上的头），`2.0.11` 也是臆造的。
> - 因此白名单**不需要** `web.traceint.com`，它从来没有存在过理由。
> - 而真正缺的那一样，比这三个头都关键：**请求体必须是 form 编码**。
>   见 [K](#k-「给了-cookie-还是读不到数据」的真正根因请求体必须是-form-编码2026-11-22-真机实测)。
>
> 现行实现只发 `App-Version` + `Cookie`，`Origin`/`Referer` 已删除。

---

## H. 真机冒烟回填（emulator-5554 / Android 15 上实际点出来的问题）

单元测试 202 条全绿、APK 装得上、Activity 起得来，**仍然**藏了三类只有"真的用手指点一遍"
才会暴露的缺陷。记在这里，因为它们都不是"写错一个字符"，而是**测试层天然覆盖不到**的盲区。

### H-1 无 factory 的 `viewModel()` 会在真机直接闪退

第一版 `MainActivity` 里是这么写的：

```kotlin
composable(Routes.LIBRARY_ASSISTANT) {
    val vm: BorrowViewModel = viewModel()   // ← 编译通过、单测通过、真机崩
    ...
}
```

点进「图书馆助手」立刻闪退，logcat：

```
Caused by: java.lang.NoSuchMethodException:
    com.xynu.campus.library.vm.BorrowViewModel.<init> []
    at androidx.lifecycle.viewmodel.internal.JvmViewModelProviders.createViewModel
```

原因链：
- `viewModel()` 是 `reified` 泛型 + 默认 `ViewModelProvider.Factory`，
  底层用**反射找无参构造**（`Class.getConstructor0`）。
- `BorrowViewModel` 的构造是 `(MobileLibRepository, IdentityStore)`，没有无参构造。
- 编译期不报错（语法完全合法）、JVM 单测不报错（单测直接 `new`，
  根本不经过 `ViewModelProvider`）、Robolectric 也有 factory 语义差异。
  **只有真机点一次才会炸。**

修法：新增 `LibraryViewModelFactory`，并且把两个 VM 提升到 **Activity 作用域**
（和既有的 `jwVm`/`libVm` 一致）。提升还顺带修掉一个隐性 bug——借阅页与引导向导页
如果各自 `viewModel()` 会是**两个实例**，向导里存好 openid、返回借阅页仍是旧状态，
表现为"明明保存成功了还让我去引导"。

护栏：`UiContractRegressionTest` 用源码扫描断言"有构造参数的 VM 必须带 factory"，
并做过变异测试（把 `factory = libraryVmFactory` 改回 `viewModel()` → 测试变红）。

### H-2 Compose 的 `Text` 不解析 Markdown，字面 `**` 会漏给用户

`MigrationScreen` 里写了：

```kotlin
Text("粘贴旧手机生成的迁移码。导入会**整体替换**本机现有身份，不是合并。")
```

实机截图上用户看到的是带星号的原文 `导入会**整体替换**本机现有身份`。
Robolectric/单测都发现不了——它只是"字符串渲染成什么样"的问题。

修法：`buildAnnotatedString { append(...); withStyle(SpanStyle(fontWeight = Bold)) { ... } }`。

护栏：同一测试类断言"UI 源码字符串字面量里不得出现 `**...**`"，
`assets/library/*.html` 也一并扫。同样做过变异测试。

### H-3 `colorScheme.outline` 当正文色，对比度不足

基础档「关于图形验证码」那张卡的字在截图里几乎看不清。
`outline` 语义是**描边/分隔线**，Material 3 里它比正文色浅一档。
同理把 `SeatScreen.kt` / `MigrationScreen.kt` 里当文字色用的 12 处统一换成
`onSurfaceVariant`（字号 12→13sp）。

注意：`BorrowWizardScreen` 里步骤序号圆点的**背景**用 `outline` 是合法的，
所以护栏只匹配 `color = MaterialTheme.colorScheme.outline` 这种**前景色**写法，
不做无差别替换。

### H-4 其他确认过的事实

| 项 | 实测结果 |
|---|---|
| WorkManager 初始化 | `WM-WrkMgrInitializer: Initializing WorkManager with default configuration.`，无需自定义 `Configuration.Provider` |
| 每日提醒真正入队 | 点「每天 07:50 提醒」后 `dumpsys jobscheduler` 出现 `com.xynu.campus/androidx.work...SystemJobService`，`TIME=+17h47m`（= 次日 07:50），符合预期 |
| 基础档零网络 | 基础档页面全过程 logcat 无任何网络请求；job 约束里无 `NetworkType` |
| Cookie 本地拒绝 | 只填 `wechatSESS_ID=abc123` 点「验证并保存」→ 弹「Cookie 格式不对：需要同时包含 wechatSESS_ID、Authorization、FROM_CODE 三项…」，**未发请求** |
| 剪贴板失败分支 | 剪贴板为空点「我复制好了」→ 提示「没读取到剪贴板内容，请先回到微信复制链接」 |
| WebView 本地 HTML | `assets/library/wizard.html` 三步图文正常渲染，JSBridge 可调 |
| adb 无法设剪贴板 | Android 10+ 无 `cmd clipboard`，`service call clipboard` 需系统权限。**成功分支只能靠单测覆盖**，真机需人工粘贴 |

---

## I. 真机反馈「识别不了链接 / 读不到信息」的根因（2026-11 修复）

用户拿着**真实凭据**在真机上试，报了两个问题。逐条定位后发现是**三个独立的代码缺陷**
加一处**规格与现实的冲突**，全部与「用户粘贴的内容」无关。

### I.1 用户提供的真实样本

借阅链接（从微信「借阅信息」复制）：

```
http://libopac.xynu.edu.cn:8080/sms/opac/user/lendStatus.action
  ?schoolid=968&openid=<o开头28位>&sn=<144字符十六进制>
```

选座 Cookie（整段粘贴，含浏览器埋点字段）：

```
FROM_TYPE=weixin; FROM_CODE=WwsCBVIJAgs%3D; v=5.5;
Hm_lvt_7ecd21a13263a71c18038a87=...; HMACCOUNT=...;
wechatSESS_ID=<32位hex>; Authorization={{TOKEN_...}};
SERVERID=...|...|...
```

### I.2 缺陷 1（主因）：成功的 302 被判成「无法识别」

`MobileLibRepository.probeBindState` **不跟随重定向**（这是对的，需要 302 语义），
但 `classifyRedirect` 只认两种 302：

- `open.weixin.qq.com` → 缺 openid
- `showUserBind.action` → 未绑读者证

而该学校的**成功**路径是第三种：`302 → libopac.xynu.edu.cn:8080/sms/opac/...`。
旧实现落到 `else` 分支去判定一个**空 body**，`LandingPageClassifier` 对空串返回
`UNKNOWN`，UI 于是显示「无法识别服务器返回」。

> 这就是用户说的「图书馆助手没办法识别链接」。
> **不是 openid 正则的问题**——`OpenIdExtractor` 对用户真实链接能正确抓出 28 位 openid。

修法：`classifyRedirect` 增加 `/sms/opac/` 与 `lendStatus.action` 两条锚点 → `BOUND`；
新增 `borrowLandingUrl(location)` 把带**新 sn** 的业务页地址交出去。
护栏：`BorrowLandingRedirectTest`（9 例）。

### I.3 缺陷 2：摘要解析器认不出阿法迪模板

真实页面（fixture `lend_status.html`，6768 bytes）的结构是：

| 字段 | 真实写法 | 旧解析器 |
| --- | --- | --- |
| 在借册数 | `<h1>目前借书1本</h1>` | 找「当前借阅N册」→ 不匹配 |
| 应还日期 | `<td class="dueDate">2026/12/30 23:59:59</td>` | 找「应还日期：」→ 不匹配 |
| 续借次数 | `<td class="renewCount">1</td>` | 无此字段 |

修法：`CURRENT` 正则扩展为 `当前借阅|目前借书|在借`；`parseDom` 优先按
`class="dueDate"/"renewCount"` 取值；`Summary` 增加 `renewCount`。
护栏：`BorrowSummaryParserRealFixtureTest`（5 例，含 0 本书与旧模板兼容）。

### I.4 缺陷 3：`sn` 是服务端每次重新签发的

实测 `openItemNeedBind` 每次请求都返回**不同的** `sn`（144 字符）。
同一 `sn` 可重复使用（连打 3 次均 200 + 真实数据），但用户从微信复制到的那条会过期，
过期后标题变成 **「登录失效」**。

> 所以正确做法是：**只保存 openid，每次查询时实时换新 sn**，不要把用户复制的链接当凭证存起来。

### I.5 白名单扩容（已决策并落地）：`libopac` 进入模块白名单

| 角色 | host | IP:端口 | 公网可达 | 当前处理 |
| --- | --- | --- | --- | --- |
| 换 sn + 302 | `mobilelib.wx.chaoxing.com` | 140.210.72.179 | 是 | 白名单内 |
| 借阅正文 / 检索 / 书目详情 | `libopac.xynu.edu.cn` | **222.21.62.155:8080** | 是（见下） | **已加入白名单** |
| 老馆藏 OPAC | `222.21.62.201:8011` | — | **否（校园网 only）** | 保持不进白名单 |
| 选座 GraphQL | `wechat.v2.traceint.com` / `web.traceint.com` | — | 是 | 白名单内 |

**决策依据（用户明示的功能优先级）**：用户明确表态「功能性至上，只要能在内外网在应用内实现查询到馆藏、
查到借阅、可以预定座位等，什么都可以接受」。因此「借阅正文/馆藏不在白名单」这条规格红线让位于功能：
`libopac.xynu.edu.cn` 已加入模块白名单（`HostAllowListInterceptor.HTTP_HOSTS`），借阅明细与馆藏检索
**在应用内直接渲染**，不需要跳浏览器。

放宽的代价可控，理由如下：

1. `libopac.xynu.edu.cn` **本来就在工程基线** `res/xml/network_security_config.xml` 里（提交 `be48a58`），
   所以这次没有改动任何全局网络配置；
2. 该 host 是本校图书馆**自己的**业务主机，不是第三方；
3. 白名单**只作用于图书馆助手模块自己的 OkHttpClient**（拦截器挂在 `LibraryHttpClient` 上），
   不影响 App 其它模块；
4. 白名单是**精确 host 匹配 + fail-closed**，校外的 `222.21.62.201:8011` 依然被拒。

实测确认 `mobilelib` **不能**代理 `/sms/opac/` 下的页面（404「网页走丢了」），
所以把 `libopac` 加进白名单是应用内渲染的唯一可行路径。

当前白名单恰好 5 个 host（`ALL_HOSTS.size == 5`，有测试固定）：
`mobilelib.wx.chaoxing.com`、`mobilelibwx.chaoxing.com`、`libopac.xynu.edu.cn`、
`wechat.v2.traceint.com`、`web.traceint.com`。

### I.6 「馆藏搜索被修坏了」的结论：是网络归属，且已用可外网主机替代

用户报错：

```
failed to connect to /222.21.62.201 (port 8011) from /10.44.138.208 (port 36676) after 15000ms
```

核查结果：

| 证据 | 结论 |
| --- | --- |
| `git status` 里 `data/net/LibApi.kt`、`HttpClientFactory.kt`、`network_security_config.xml` 均为 **未修改** | 本模块没碰老馆藏搜索 |
| 15s 超时是 `HttpClientFactory` **原有**配置，非本次引入 | 不是回归 |
| 报错里源地址 `10.44.138.208` 是**用户手机**的地址 | 与开发机不同网段 |

**外网（手机热点）实测，决定性证据：**

| 目标 | 结果 |
| --- | --- |
| `222.21.62.201:8011`（老馆藏 OPAC） | HTTP `000`，connect timeout 8.0s × 3 次全失败；ICMP 100% 丢包；端口 80/443/8000/8011/8080/8081 **全部 filtered** |
| `222.21.62.155:8080`（libopac） | HTTP `200` × 3 次全成功，101~153ms |

即 `222.21.62.201` 这台机器**整体只对校园网内开放**，从公网完全不可达——学校侧访问控制策略，与 App 代码无关。

> 注意：开发机先前能连上 `.201:8011`，是因为当时走了校园网/VPN 路径；
> 切到手机热点（纯外网）后立刻复现用户同样的超时，进一步确认是网络归属问题。

**不只是解释原因：已经给了外网可用的替代实现。** 见 §J——
`libopac.xynu.edu.cn:8080` 上的 `/search/searchList` 与 `/search/bookDetail`
提供同样的检索 + 书目 + 馆藏复本，公网实测 200，因此新增的「馆藏查询」页
**在内外网都能用**，不再依赖 `.201:8011`。

老的首页「馆藏检索」（绑 OPAC 登录会话、收藏、续借）保持不动，它在校园网下依旧可用。

### I.7 其他确认

| 项 | 实测 |
| --- | --- |
| `libopac.xynu.edu.cn` DNS | → `222.21.62.155` |
| `lib.xynu.edu.cn` DNS | → `210.43.24.6` |
| `mobilelibwx.chaoxing.com` | 301 → https 同域 |
| `LibraryWebView`（WebView 壳） | **当前无任何调用点**，向导实际走 Compose 版 `BorrowWizardScreen` → `BorrowViewModel`（即已修复的路径） |
| 选座 Cookie 的 `FROM_CODE` | 用户真实粘贴里**确实有**，但它不是必须项（来自入口 URL），解析时「有就用、没有也不拒绝」 |

---

## J. 应用内检索 + 书目详情 + 借阅明细（2026-11 外网实证）

目标：在「功能性至上」的前提下，把**查馆藏 / 查借阅**做成不依赖校园网、不跳浏览器的应用内功能。
以下所有结论都是**从公网（非校园网）实测**得到的，不是推测。

### J.1 结论速览

| 能力 | 接口 | 公网实测 |
| --- | --- | --- |
| 馆藏检索（列表 + 分页） | `POST /search/searchList` | `200`，19796 B，185~215ms，共 187 条 |
| 书目详情 + 馆藏复本 | `POST /search/bookDetail` | `200`，6535 B，120~134ms |
| 借阅明细 | `mobilelib` 302 → `lendStatus.action` | `302` → `200`，6768 B |
| 检索入口页 | `GET /sms/opac/search/showiphoneSearch.action` | `200`，215ms |

主机全部是 `libopac.xynu.edu.cn` = `222.21.62.155:8080`。

> 测量注意：`Invoke-WebRequest` 在本机上会命中某个本地代理，1~11ms 内直接返回 404；
> 用 `curl.exe` 直连才是真实结果。排错时别被这个 404 误导。

### J.2 检索：`POST /search/searchList`

表单字段：

| 字段 | 值 |
| --- | --- |
| `kw` | 检索词 |
| `xc` | `3` |
| `schoolid` | `968` |
| `searchtype` | 见下表 |
| `campusType` | 空串 |
| `f_curlocal` | 空串 |
| `pageIndex` | **仅第 2 页起才需要**（1 时可省略） |

**`searchtype` 的真实取值是入口页 `showiphoneSearch.action` 里那几个锚点的 id，只有四个：**

| 值 | 含义 | 实测 |
| --- | --- | --- |
| `题名` | 书名（默认，`class="cur"`） | `kw=逻辑学` → 187 条 |
| `作者` | 作者 | `kw=丁毓峰` → 1 条（大白话讲透逻辑学） |
| `主题词` | 主题词 | `kw=逻辑学` → 131 条 |
| `索书号` | 分类/索书号 | `kw=B81` → 749 条 |

传入未识别的值（例如 `ISBN`）**不会报错**，但结果按题名解释，实测返回 0 条。
空串等价于 `题名`。所以 UI 的可选项必须严格是这四个，否则用户会「搜不到也不知道为什么」。

其他实测：

- 只支持 **POST**；`GET /search/searchList` → `404`。
- 每页 10 条；`pageIndex=2` 返回不同结果（首条从 `大白话讲透逻辑学` 变为 `文化主体性的中国逻辑`）。
- 分页在 HTML 里靠 `pageIndex=` 链接表达，解析器用「最大页码 / 总数」双兜底判断 `hasNext`。
- 条目里的 `detailParam` 是 JSON：`{"url":"http://222.21.62.201:8011/opac/book/<32位hex>"}`。
  **那个 `.201` 地址只是参数值，App 从不真的去请求它**，所以外网无影响。

### J.3 书目详情 + 馆藏复本：`POST /search/bookDetail`

表单：`xc=3`、`schoolid=968`、`f_curlocal=`、`detailParam={"url":"http://222.21.62.201:8011/opac/book/<id>"}`。

两个关键实测结论：

1. **`detailParam` 可以只用 32 位 hex id 拼出来**，不需要真的打开过 `.201`。
   用两个不同 id（`5f01bdf9…`、`5ce9dcff…`）各测一次，都返回正确的书目，证明服务端只从 url 里取 id 查库。
2. 返回内容包含**书目信息**和**馆藏复本表**，也就是说「在馆几本、在哪个书库」这个外网拿不到的信息，
   现在在外网拿到了。

解析要点（`CatalogDetailParser`）：

| HTML 结构 | 注意 |
| --- | --- |
| `<div class="catalog"><p>标签：值</p>` | 标签不固定（有「题名主题」也有「题名主题词」），用**前缀匹配 + 表驱动**，长标签优先（避免 `ISBN` 抢走 `ISBN/价格`） |
| `ISBN/价格：978-7-122-47467-4:CNY52.00` | 两个字段塞一行，要按 `:` 拆 |
| `题名责任者项：书名/.作者编著` | 用 `/.` 拆书名与作者 |
| `出版发行项：北京:,化学工业出版社:,2026.3` | 按 `:`/`,` 拆后取末尾含 4 位数字的段作日期 |
| `<div class="tableLib"><table><tr><th>条码号<th>索书号<th>藏馆<th>库室<th>馆藏状态` | **可能有多个 `tableLib`（多分馆/多复本），必须全部收集**，只取第一个会让用户白跑 |
| `<title>` | 就是书名 |

真实 fixture：`fixtures/catalog_detail.html`（6535 B，大白话讲透逻辑学）、
`fixtures/catalog_detail_2.html`（6493 B，文化主体性的中国逻辑，字段集合不同）。
两份都是公网抓的原文，且重新抓取后 **SHA256 与 fixture 完全一致**（可复现）。

### J.4 借阅明细：从「跳浏览器」改为「应用内渲染」

原来 302 成功路径下原生层只拿得到「已绑定」这个结论 + 一条带新 sn 的地址，
因为正文在 `libopac` 上（当时不在白名单）→ UI 只能给「在浏览器查看」按钮。
现在 `libopac` 在白名单内，`MobileLibRepository` 会二次跟进 302 把正文取回来并解析。

真实页面结构（`fixtures/lend_status.html`）：每本借出的书是一个 `div.tableLib`，
内含 `<th>条码号/题名/借阅日期/应还日期/续借次数</th><td>…</td>`。

两个必须绕开的坑：

1. 部分 `<tr>` **没有闭合标签**（`<tr><th>题名</th><td>…` 后直接跟下一个 `<tr>`），
   jsoup 会按 HTML 规则自动修复，修完一个 `tr` 里可能塞了两对 `th/td`；
   所以取值要**成对扫描**，不能「取第 0 个 th 和第 0 个 td」。
2. 每本书末尾有 `tr[style*=display:none]`（续借结果/续借时间）和 `tr.renewParam`
   （内容形如 `{"barcode":"…"}`）。不过滤的话会被当成业务字段，续借次数被 json 数字污染。

公网实测复现同一现象：重新抓取后与 fixture 相比**只有 `sn` 的 nonce 不同**，其余 191 行完全一致。

### J.5 这次新增/改动的代码

| 文件 | 作用 |
| --- | --- |
| `library/parser/CatalogDetailParser.kt` | 书目 + 馆藏复本解析 |
| `library/parser/BorrowSummaryParser.kt` | 新增 `Item` 与 `parseItems()`，逐本借阅明细 |
| `library/net/CatalogRepository.kt` | `search()` / `detail()` + `CatalogDataSource` 接口 + `bookIdOf()` |
| `library/vm/CatalogViewModel.kt` | 检索/分页/详情状态机（含「失败页」记录，保证重试打的是失败那页） |
| `library/ui/CatalogScreen.kt` | 馆藏检索页 + 书目详情页（馆藏表置顶） |
| `library/ui/BorrowScreen.kt` | `BoundContent` 改为应用内渲染摘要 + 逐本书单 |
| `library/vm/BorrowViewModel.kt` | 状态里带上解析后的 `summary` |
| `MainActivity.kt` / `HomeScreen.kt` | 新路由 `library_catalog`、`library_catalog_detail` 与首页「馆藏查询」入口 |

测试（全部 JVM 单测，共 254 条 0 失败，24 个测试类）：
`CatalogDetailParserTest`(9)、`CatalogViewModelTest`(12)、`BorrowSummaryParserRealFixtureTest`(9，其中 4 条专测逐本明细)。

### J.6 真机冒烟（emulator-5554，真实 openid）暴露的三个问题

这一轮不是纸面验证：在模拟器上用**真实 openid** 把「馆藏检索 → 书目详情 → 我的借阅」全点了一遍，
拿到真实数据，同时抓到三个单测覆盖不到的缺陷。

| # | 现象 | 根因 | 修法 |
| --- | --- | --- | --- |
| 1 | 同一屏上方写「还没有借阅身份」，下方又写「本机保存的身份：openid=oK\*\*\*」 | `BorrowViewModel.init` 只读本地不查上游，`bindState` 停在 `UNKNOWN`，`when` 的 `else` 落到 `EmptyIdentityCard` | `init` 里有身份就自动 `refresh()`；并新增 `PendingCheckCard`（「身份已保存 · 查询借阅」）替代那个误导性的空态 |
| 2 | 「数据来源：http://…?openid=oK…&sn=6EA1…」把**完整 openid 与 sn 铺在屏幕上** | 直接把 `landingUrl` 插进 `Text` | 新增 `hostOf(url)`，只显示 `libopac.xynu.edu.cn:8080`；并加静态护栏测试 |
| 3 | 用 instrumentation 播种 openid 显示 OK，但 App 里仍无身份 | `EncryptedSharedPreferences` 用 `apply()`（异步），instrumentation 跑完直接杀进程，写队列没落盘 | 播种器里显式等待后再退出 |

第 2 条值得单独强调：那串 `sn` 是**当次会话的有效凭证**，截图、录屏、旁人斜眼都能拿走。
日志脱敏红线只管了 logcat，没管**界面**，这是同一类问题的另一个出口——
所以除了改代码，还补了 `UiContractRegressionTest` 的两条护栏，并做了变异测试
（把 `hostOf` 改回直接插值，确认测试会红）。

顺带把 `fixtures/lend_status.html` 里残留的真实 `sn` 也换成了等长占位符（虽然它早已过期）。

### J.7 三个遗留点的闭合情况

上一版这里列的是「仍未闭合」。下列四点已全部处理完，逐条对照：

| 点 | 结论 | 怎么闭合的 |
| --- | --- | --- |
| 向导成功路径的真机验证 | **已闭合** | adb 确实无法设置 Android 15 剪贴板，所以不再依赖剪贴板：向导页加了「读不到剪贴板？手动粘贴链接」的折叠输入框，直接把整段链接贴进去走同一条解析路径（`BorrowViewModel.onSubmitLinkText` → `submitLink`）。真机上剪贴板读不到时用户有兜底出口，不再是死路。 |
| 高级档选座真实预约 | **已闭合** | `from_code` 已全链路接通：`SeatViewModel.loadFromCode()` 抓大厅页 → `HallMapParser.extractFromCode` → `IdentityStore.saveFromCode` → `SeatScreen` 里 `SeatNotifier.jumpChain(fromCode)`；早间提醒走 `SeatReminderWorker`（没有 ViewModel），也从存储里读。取不到时静默降级为「微信 + 通用网页」，不报错。 |
| 老「馆藏检索」页 | **已收口** | 按产品要求统一入口：首页去掉重复的「馆藏查询」，原位置保留「馆藏检索」，指向免登录、内外网都通的新检索页。老 `BookSearchScreen` 的路由与代码保留（它绑着 OPAC 登录态/续借，「我的借阅」仍需要它），只是不再是首页入口。 |
| 首页双入口 | **已闭合** | 只剩一个「馆藏检索」入口（原「馆藏查询」的位置），不再需要文案区分两个同名入口。 |

#### 顺带做掉的三件事

1. **收藏系统接进新检索模块**。两个模块共用同一份 DataStore（`favorite_books`），靠书目 id 判重；
   `CatalogRepository.bookIdOf` 与老 `LibParser` 产出同一形状的 32 位十六进制 id，
   所以任一边收藏的书另一边都能打开。`FavoriteIdCompatibilityTest` 把这个契约钉住了。
   收藏书架里点一条书，现在打开的是**免登录、外网可用**的新详情页，而不是只能校园网的老页。
2. **顶栏全部对齐主页**。`AppTopBar` 原先 8dp 红点 / 16sp 标题 / 6dp 内边距，主页是 9dp / 17sp / 14dp，
   来回切两次就能看出标题在跳。现在统一到主页那组数值，并用带负偏移的 38dp 返回键保住触控面积。
   `UiContractRegressionTest` 里两条护栏钉死了「二级页必须用 AppTopBar」和「两组数值必须相等」。
3. **选项条收窄，给正文让位**。「我的借阅」原来用 `TabRow`（固定 48dp + 分隔线），
   换成 34dp 的矮标签；成绩查询的学期栏和馆藏检索的检索字段栏也都从 `FilterChip`（32dp + 外边距/整排）
   换成约 30dp 的横滑胶囊标签。省下的 20~40dp 直接进了列表高度。

---

## K. 「给了 Cookie 还是读不到数据」的真正根因：请求体必须是 form 编码（2026-11-22 真机实测）

### K.1 用户报的现象

> 「选座助手给了 cookie 但还是无法读取数据」

原文里的因果关系是猜的（以为 Cookie 没生效）。**Cookie 一直是好的**，
错的是我们把 GraphQL 请求编码成了 JSON。

### K.2 四组对照（决定性证据）

在 `emulator-5554` 上用一个**不带任何拦截器**的裸 `HttpURLConnection`
直接打 `https://wechat.v2.traceint.com/index.php/graphql/`，只改编码方式：

| # | 发法 | 上游返回 | 含义 |
| --- | --- | --- | --- |
| A | POST + **JSON body** | `{"errors":[{"msg":"Syntax Error: Unexpected <EOF>","code":1}]}` | query 解析成空串 |
| B | POST + **空 body** | `{"errors":[{"msg":"Syntax Error: Unexpected <EOF>","code":1}]}` | **与 A 逐字节相同** |
| C | POST + **form-urlencoded** | `{"errors":[{"msg":"Access Denied!","code":40001}],"data":{"userAuth":null}}` | query **被正常解析** |
| D | GET + query 参数 | `{"errors":[{"msg":"Access Denied!","code":40001}],"data":{"userAuth":null}}` | 同上 |

**A 和 B 返回完全一样**，这是关键：服务端根本没有去读我们的 JSON body，
所以它解析出来的 query 是空字符串，语法分析器一头撞到 EOF。
C 组换了编码立刻变成 40001 —— 40001 是"没带凭据"，
说明**请求这次真的被读懂了**。

上游是 PHP，按字段名从 `$_POST` 取 `query` / `operationName` / `variables`，
不认 `application/json`。而 `uni.request` 默认就是 form 编码，
所以上游自己的前端从来没暴露过这个问题。

### K.3 为什么这个 bug 特别难发现

它伪装成了「Cookie 失效」：

1. 请求发出去了，HTTP 200，`Res.Ok`。
2. `data.userAuth` 是空的 → 列表解析成 `emptyList()`。
3. UI 显示「没有取到图书馆列表」，文案还写着「可能是 Cookie 已过期」——
   把我们自己的编码错误甩给了 Cookie。

在修好之前，验活（`verifySession`）看起来还"成功"，因为那时错误信封还没被解析。
修完错误信封之后，真机上终于看得见上游原话了：
`上游拒绝：Syntax Error: Unexpected <EOF> (code 1)` ——
正是这句提示才把编码问题揪出来。

### K.4 改了什么

`SeatGraphQlClient.doExecute` 的请求体从 `JSONObject().toString()` +
`application/json` 改成 `operationName=…&query=…&variables=…` +
`application/x-www-form-urlencoded`（`query` 与 `variables` 各自 URL-encode）。

护栏测试 `SeatGraphQlErrorEnvelopeTest > request body is form encoded not json`：
断言 Content-Type 是 form、**不是** JSON，body 不以 `{` 开头，
且三个字段齐全、`query` 解开后以 `query ` 开头。
另有一条 `SeatSessionInvalidTest` 的 dispatcher 也改成按 form 解析 `operationName`。

### K.5 顺带确认的两件事

- **`Content-Type` 不要手写**。用 `.post(formBody)` 后由 `RequestBody` 自带，
  再 `.header("Content-Type", …)` 反而可能把 charset 覆盖掉。此前的 JSON 版本
  就同时写了两遍。
- 上游对**无 Cookie** 请求返回的是 `40001` + `data.userAuth = null`，
  与我们 `isSessionInvalid` 的判定一致，可以直接用来区分
  「编码错了」(<EOF>) 和「凭据过期」(40001)。

---

## L. 用户第二轮反馈的四个问题（2026-11-22）

### L.1 「给了 Cookie，删后台再打开直接闪退」—— 属性初始化顺序

用户原话：「一旦给cookie，删后台再次打开直接闪退」。

**特征**：只在**存过选座会话之后**的冷启动崩溃。第一次装、没给 Cookie 时一切正常。
所以它不会被"随手点一遍"发现，得先走过「粘贴 Cookie → 杀进程 → 再打开」这条路径。

**根因**是 Kotlin 的属性初始化顺序。`SeatViewModel` 里原本写成：

```kotlin
init { refreshTier(); loadFromCode() }          // ← 先执行
// ...
private val refreshThrottle = RefreshThrottle()  // ← 后声明 = 此刻还是 null
```

Kotlin 按**声明顺序**初始化属性，`init` 块夹在中间就地执行。
`refreshTier()` 读到了 `refreshThrottle`，而此时它仍是 `null` → NPE → 闪退。
没有会话时 `refreshTier()` 会提前 return，所以碰不到那个 `null`，
这就是为什么只有"给过 Cookie 的人"才崩。

**修法**：把 `refreshThrottle` 的声明挪到 `init` 之前。

**护栏**：`SeatViewModelInitOrderTest` 不跑 Android 运行时，直接读源码文本：
正则抠出 `init` 块里读到的字段名，再断言每个 `val` 的声明行号**小于** `init` 的行号。
为了确认这条测试不是空转，故意把声明挪回 `init` 下方跑了一次 —— 如期 FAILED，
再挪回来 BUILD SUCCESSFUL。

真机复现与验证（`emulator-5554`）：
种一份会话 → `am force-stop` → `am start` → 进程存活，`logcat -b crash` 全空。

### L.2 「选座助手」改名与首页入口

用户问：既然里面只有借阅查询，为什么不直接叫「我的借阅」？

- 首页磁贴与页内 `AppTopBar` 标题都已是**「我的借阅」**。
- 选座页标题是**「图书馆选座」**（不叫"选座助手"）。
- 用户可见文案里残留的"图书馆助手"已按新名字改掉：
  `IdentityMigration` 的导入失败提示、`HostNotAllowedException` 的报错文案。
  模块名只留在代码注释里，不影响用户。

### L.3 「座位预约也是个摆设」—— 已改为应用内

首页「座位预约」原来打开 `SEAT_RESERVATION_HALL_URL`，那页在 WebView 里
只显示「请在微信中打开」，等于死路。现在直接进 `Routes.LIBRARY_SEAT`。

### L.4 会话失效时不再重复说同一件事

修完 L.2 的锚点后，真机上 40001 被讲了两遍：红色横幅说一次，
紫色 `MessageCard` 又说一次。现在 `sessionInvalid` 为真时不再渲染
`MessageCard` —— 横幅本身已经带「重新粘贴 Cookie」按钮了。

## M. 座位图「颜色没渲染上」的根因：漏了外层 `status` 门控（2026-11-22 真机实证）

用户反馈两件事：**座位图实况颜色没渲染上**、**UI 不美观**。前者是真 bug，后者是排版问题。

### M.1 根因

上游 `ti_prereserve.js` 的颜色逻辑是**三级**的，用 `render:function(t){return Array.isArray(t)?`
搜到（offset ≈ 12041）：

```js
1 == t.type && (
  t.status
    ? ( t.defaultStyle.background = "#F36F6F",          // ← 第一级：默认就是红
        t.seat_status > 0 && (
          2 === t.seat_status && (bg = "#53B175"),      // 绿
          4 === t.seat_status && (bg = "#DADADA"),      // 灰
          5 === t.seat_status && (bg = "#FE9B4B")       // 橙
        ) )
    : ( bg = "#FFFFFF", border = "8rpx solid #F2F2F2", radius = "8rpx" )   // 白
)
```

**旧实现只按 `seat_status` 映射，完全没看外层 `status`。**
于是 `status==true && seat_status ∈ {0,1,null}` 这一大类座位
（上游语义 = 有人/暂离/监督）落到了「未知 → 白色」分支，
明明被占却画成可预约的白色。

### M.2 为什么很难自己发现

- 上游把 **`#F36F6F` 作为 `status==true` 的默认底色**再让 2/4/5 覆盖，
  而不是反过来「按 `seat_status` 逐值上色」。这个写法很反直觉。
- 四个座位色在整份 `ti_prereserve.js` 里**各只出现 1 次**（`Group-Object` 数过），
  所以不可能靠"多找几处交叉验证"补出来，只能读懂那一行。
- 假 Cookie 跑不通（40001），只有**真实 Cookie** 才拿得到真实座位图。
  修复前后都截了图对比：

| | 空闲/总数 | 截图表现 |
|---|---|---|
| 修复前 `20/21` | 52 / 192 | 192 格**全白**，140 个已占座位看不出来 |
| 修复后 `22/23/24` | 51~52 / 192 | 红/白/灰/橙/**蓝** 五色齐全 |

> 注：修复前 `空闲 52`、修复后 `空闲 51`，是因为期间有别人订走了一个座位，
> 不是回归。

### M.3 改了什么

- `SeatVisual` 从「按 `seat_status` 一对一」重写为 `of(status, seatStatus)`，
  先判 `status` 再判 `seat_status`，枚举值改为语义化的
  `FREE / OCCUPIED / AVAILABLE / DISABLED / HELD`。
  `fromSeatStatus` 已删除 —— 它**在结构上就表达不了** `status` 门控。
- 已选色改用上游 `selectedStyle:{background:"#3D9BFE"}` 的**蓝色**。
  原来用主题品牌红，会和「有人」的 `#F36F6F` 撞成一片红，分不清自己选的。
- 图例措辞逐字取自上游 `menuList`：可选 / 有人 / 暂离 / 不可用 / 可预约 / 已选。
- 家具元素不再留空。上游给 type 2..9 各配了图标，名称取自 `tipList`
  （`大门 / 插座(注意安全) / 锁定·专属座位 / 柱子 / 窗户 / 书架 / 插座桌子`）。
- 测试加了两个护栏：
  - `seat visual mapping follows upstream three-tier status gate` —— 逐条钉死三级结构
  - `occupied seats are never rendered as free` —— 任何 `status==true` 都不许得到 `FREE`

### M.4 UI 不美观修了什么

- 座位图原来是个裸 `Column`，现在套一层带描边的浅色卡片，边界明确。
- **纵向也能滚了**。原来只有 `horizontalScroll`，大馆 `max_y` 能到 40+ 行，
  下半部分直接被顶出屏幕看不到。
- 底部操作条常驻高度，选中/未选中不再整块跳动；文案从"点白色座位"改为
  "点一个空闲座位"（绿色座位其实也能点）。
- 空闲数改用服务端口径 `seats_total - seats_used - seats_booking`，
  不再用前端数 `seats` 数组 —— 座位图可能有裁剪，两者会不一致。



