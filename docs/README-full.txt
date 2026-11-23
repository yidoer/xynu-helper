# 信师大 · 一站式校园服务 App

面向**信阳师范大学**学生的一站式校园服务 Android 应用（Kotlin + Jetpack Compose 原生开发，非官方、个人开发）。

> ⚠️ 本应用调用的是学校各系统的**公开网页接口**（模拟浏览器登录后抓取数据），与学校官方无隶属关系。数据仅供个人查询使用，请勿滥用。

---

## 🚀 v1.1.0 变更说明

- 网络请求统一使用 15 秒连接/读取/写入超时，避免校园系统不可达时页面长时间停留在加载状态。
- 统一识别正方教务与图书馆 OPAC 的登录重定向/登录页响应；会话失效时清理本地会话数据并自动回到对应登录页。
- 为课表、成绩、空教室、OPAC 借阅列表等 Jsoup/JSON 解析逻辑补充页面结构 fixture、字段缺失和异常输入单元测试。
- **新增图书馆功能**（v4）：超星移动图书馆借阅查询（「我的借阅」）+ 「我去图书馆」选座提醒/预约（「座位预约」），纯本地架构、无服务器、无遥测。
- 发布版本号：`versionName 1.1.0`，`versionCode 2`。

## ✨ 功能总览

| 模块 | 功能 | 是否需要登录 |
|---|---|---|
| 🏠 首页 | 品牌横幅、常用服务入口、教务通知、图书馆公告聚合 | 否 |
| 📅 我的课表 | 正方教务课表（周视图，按星期/节次排布） | 教务系统 |
| 📊 成绩查询 | 按学期查询成绩、学分、绩点 | 教务系统 |
| 🔍 馆藏检索 | 图书馆 OPAC 检索（题名/作者/主题词/索书号）+ 书目详情 + 收藏；**免登录，校内外网络都能用** | 否（检索免费） |
| 📚 我的借阅 | 查看当前借阅列表、应还日期、在线续借 | 图书馆 |
| 🏫 教务登录 / 图书馆登录 | 单独登录，凭据 Keystore 加密存储 | — |
| 📖 座位预约 | 到点提醒（基础档）+ Cookie 解锁的全自动选座（高级档） | 微信内获取凭据 |
| 🔑 身份迁移 | 在两台手机之间本地导出/导入图书馆身份（迁移码，不经过服务器） | 否 |
| ⚙️ 设置 | 关于、数据来源说明 | — |

## 🛠 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3 + Navigation Compose
- **网络**: OkHttp + Jsoup（HTML 解析）
- **存储**: DataStore 偏好存储 + Android Keystore 加密凭据
- **架构**: MVVM（ViewModel + StateFlow）
- **构建**: Gradle 8.11.1 / AGP 8.5.2 / JDK 17

## 📦 安装与使用

### 直接安装（已编译 APK）

```
app/build/outputs/apk/debug/app-debug.apk
```

把 APK 传到 Android 手机安装即可（需允许"安装未知来源应用"）。要求 Android 8.0+（minSdk 26）。

### 从源码构建

```bash
# 需要 JDK 17 + Android SDK（compileSdk 35）
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### Release 构建与签名

正式发布的 APK 使用独立的 release keystore 签名。keystore 文件与密码文件均**不提交到 git**（已在 `.gitignore` 中排除）。

**1. 生成 keystore（首次一次性）**

```bash
keytool -genkeypair -v \
  -keystore app/xynu-campus-release.keystore \
  -alias xynu-campus -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=xynu-campus, OU=dev, O=xynu-campus, L=Xinyang, ST=Henan, C=CN"
```

**2. 创建 `app/keystore.properties`**（与 keystore 同目录，内容不提交）

```properties
storeFile=xynu-campus-release.keystore
storePassword=<你的 keystore 密码>
keyAlias=xynu-campus
keyPassword=<你的 key 密码>
```

**3. 构建签名 APK**

```bash
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

`app/build.gradle.kts` 会在 `keystore.properties` 存在时自动挂载 release signingConfig；文件缺失时 release 构建会回退为未签名（CI/无密钥环境仍可编译通过）。

**4. 验证签名**

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

> ⚠️ 请妥善保管 keystore 与密码：丢失后无法以同一签名发布更新，用户将无法覆盖安装升级。

### 使用说明

1. **馆藏检索**：无需登录，进入"馆藏检索"→ 选检索字段（题名/作者/主题词/索书号）→ 输入关键词 → 搜索 →
   点条目进详情看「在馆几本 / 在哪个书库」；列表行右侧的 ♡ 可直接收藏，收藏后能在「收藏书架」里点回来
2. **教务课表/成绩**：首次进入去"教务登录"用学号+教务密码登录（勾选记住则本地加密保存），回课表/成绩页即可查看
3. **我的借阅**：用图书馆账号（学号/校园卡号，初始密码身份证后6位）登录，可看借阅列表并在线续借

## 📖 图书馆功能（借阅 / 选座 / 检索）

首页「常用服务」里有三个图书馆入口，主页面上不再有叫「图书馆助手」的按钮：

| 首页磁贴 | 页内标题 | 干什么 |
| --- | --- | --- |
| **我的借阅** | 我的借阅 | 超星移动图书馆的借阅查询（三步引导取 openid），能看应还日期 / 续借次数 |
| **座位预约** | 图书馆选座 | 到点提醒（基础档）+ Cookie 解锁的全自动选座（高级档） |
| **馆藏检索** | 馆藏检索 | 免登录搜馆藏 + 书目详情 + 收藏，校内外都能用 |

> 为什么不再叫「图书馆助手」：这个模块实际只做借阅查询，名字比功能大。
> 首页磁贴与页内标题统一成「我的借阅」，用户一眼知道点进去是什么。

借阅走的是**超星移动图书馆**（微信公众号那套）。

### 实机截图

| 首页入口 | 借阅页（未配置） | 三步引导 |
|---|---|---|
| ![首页](docs/screenshots/lib-01-home-entry.png) | ![借阅](docs/screenshots/lib-02-borrow-empty.png) | ![引导](docs/screenshots/lib-03-wizard.png) |

| 剪贴板失败提示 | 选座基础档 | 身份迁移 |
|---|---|---|
| ![失败](docs/screenshots/lib-04-wizard-clipboard-fail.png) | ![选座](docs/screenshots/lib-05-seat-basic.png) | ![迁移](docs/screenshots/lib-06-migration.png) |

### 为什么不是同一个入口

学校现存两套图书馆系统，能力不同：

| 系统 | 入口 | 能查借阅 | 能选座 | 说明 |
|---|---|---|---|---|
| OPAC（阿法迪） | `222.21.62.201:8011/opac` | ✅ | ❌ | 用学号+密码登录，已在「我的借阅」中使用 |
| 超星移动图书馆 | `mobilelib.wx.chaoxing.com` | ✅ | ❌ | 微信内免密（openid），本模块的借阅查询走这里 |
| 「我去图书馆」 | `wechat.v2.traceint.com` | ❌ | ✅ | 微信内 OAuth，本模块的选座走这里 |

### 借阅查询（F1 + F2 + F3）

首次使用需要一次性引导（**openid 只能从微信里拿到**）：

1. 微信 → 通讯录 → 公众号 → 「信阳师范大学图书馆」
2. 公众号菜单 → 我的图书馆 → 个人中心 → 借阅信息
3. 右上角 `···` → 复制链接

回到 App 点「我复制好了」，App 从剪贴板里提取 `openid`（正则 `openid=(o[A-Za-z0-9_-]{20,})`），
保存到本机加密存储（Keystore 支持的 `EncryptedSharedPreferences`）。

之后借阅页会按上游真实状态分三种情况渲染：

- **服务器不认识这个身份**（落地到微信 gate 页）→ 提示重新引导
- **身份有效但没绑读者证**（落地到「用户绑定」页）→ 提示去公众号绑定
- **已绑定** → 展示借阅摘要（当前借阅/可借/应还日期），并可展开服务器原文

### 选座（F4，两档设计）

**基础档（默认，零凭据、零网络）**

不配置任何东西也能用：设一个提醒时间，到点本地通知提醒你去抢座，点通知跳到微信。

设计上刻意做到 **零网络请求**——连 WorkManager 的约束里都没有 `NetworkType`，
`SeatReminderWorker` 的字段里也没有任何 HTTP 客户端。这条有单元测试用反射硬校验。

**高级档（需要一次性配置 Cookie）**

在微信里打开「我去图书馆」，从 WebView 开发者工具（iOS 可抓）或已登录的浏览器里复制 Cookie，
粘贴到 App 里。App 会：

1. 解析出字段（`wechatSESS_ID` / `Authorization`，必要时 `FROM_CODE`）
2. 立刻用 `list` 查询**验活**——只有不返回 `40001` 才落盘
3. 之后可查馆列表 → 规则提示 → 座位图点选 → 预约

**座位图颜色由 `status` 门控，不是只按 `seat_status`。** 上游的写法是
「`status==true` 先一律涂红 `#F36F6F`，再用 `seat_status` 2/4/5 覆盖成绿/灰/橙；
`status==false` 才是白底可预约」。早先只按 `seat_status` 映射，漏了外层门控，
于是 `status==true && seat_status<=1`（有人/暂离/监督）全被画成白色 ——
192 个座位里有 140 个被占的座位看不出来。真机前后对比见
`docs/probe_findings.md` §M。有单测
`seat visual mapping follows upstream three-tier status gate` 和
`occupied seats are never rendered as free` 拦着。

> 「已选」用上游的蓝色 `#3D9BFE`，不用主题品牌红 —— 品牌红会和
> 「有人」的红撞在一起，分不清哪个是自己选的。

> **不要自己编 Cookie 字段。** 早期版本会补一个 `v=5.5`，但 `5.5` 在上游所有
> bundle 里出现 **0 次**；`v` 是可选的，没有就不要发。同理 `Origin`/`Referer`
> 也是臆造的，已删除。

**请求体必须是 form 编码，不是 JSON。** 上游是 PHP，按字段名从 `$_POST`
取 `query`，不解析 JSON body。发 JSON 时它解析出空 query，回
`Syntax Error: Unexpected <EOF>`——而不是报错说"格式不对"，
于是列表永远是空的，看起来就像 "Cookie 失效"。真机四组对照见
`docs/probe_findings.md` §K。改回 `application/json` 会立刻复现该 bug，
有单测 `request body is form encoded not json` 拦着。

若上游要求验证码，App 不会去破解，而是提示「请到微信完成本次预约」并降级为基础档。

Cookie 失效（`40001`）时会显示横幅要求重新粘贴，且**自动重试最多 1 次**（防止被判异常流量）。
此时只显示一条红色横幅（含「重新粘贴 Cookie」按钮），不再叠一条重复的文字提示。

> **Android 抓 Cookie 的限制**：非 root 的真机上无法用常规手段抓到别的 App（微信）的 WebView Cookie。
> iOS + Charles/Stream 可以；Android 上可行的替代是用电脑浏览器模拟或在已 root 设备上抓。
> 这也是基础档被做成默认档的原因——不能让用户卡在"必须会抓包"这一步。

### 身份迁移（F5）

设置/首页入口「身份迁移」可以把 openid + 选座 Cookie 导出成一段 Base64 迁移码，在另一台手机上粘贴导入。

导出物**等同于账号本体**（拿到就能以你的名义借书、占座），所以流程是「先警告 → 二次确认 → 才生成」。
导入是**整体替换**而不是合并，且导入的 Cookie 的 `verifiedAt` 归零，进选座页会重新验活。

### 安全边界

| 项 | 做法 |
|---|---|
| 无服务器 | 模块内没有任何上报/统计/崩溃 SDK，凭据只在 `EncryptedSharedPreferences` |
| 域名白名单 | 模块级 OkHttp `Interceptor` 只放行 4 个 host，**不改全局 `network_security_config`** |
| JSBridge | 预留接口，仅暴露四个 `@JavascriptInterface`：`readClipboard` / `saveIdentity` / `requestUpstream` / `requestSeat`（**当前无生产调用方**，见下） |
| 日志脱敏 | `openid=oX***` / `cookie=***` / `pwd=***`，任何字段都不完整落日志 |
| 备份 | `allowBackup="false"` + `dataExtractionRules` 排除凭据文件 |

白名单 4 个 host（其中 `libopac` 只允许 http，`wechat.v2.traceint.com` 只允许 https）：

```
http(s)://mobilelib.wx.chaoxing.com      # 超星移动图书馆（换 sn + 302）
https://mobilelibwx.chaoxing.com         # 同上的备用域（仅 https）
https://wechat.v2.traceint.com           # 选座 API
https://libopac.xynu.edu.cn              # 本校图书馆：借阅明细 / 馆藏检索 / 书目详情
```

> 以 `HostAllowListInterceptor.HTTP_HOSTS` / `HTTPS_HOSTS` 为准，`ALL_HOSTS` 去重后为 4 个；
> `HostAllowListInterceptorTest` 断言 `ALL_HOSTS.size == 4`，改白名单会让它变红。

> **关于 `libopac`：`mobilelib` 无法代理 `/sms/opac/` 下的页面（实测 404）。**
> 要让借阅明细和馆藏查询**在应用内**显示、且**校内外网络都能用**，
> 就必须直连 `libopac`。它本来就在基线 `network_security_config.xml` 里，
> 因此没有改动任何全局网络配置；白名单也只作用于图书馆功能自己的 OkHttpClient。
> 校园内网专用的 `222.21.62.201:8011` **仍然被拒**。详见 `docs/probe_findings.md` §I.5 / §J。
>
> **`web.traceint.com` 已从白名单删除。** 早期版本曾把它列进来当
> `Origin`/`Referer` 的取值来源，但该域在全部 25 份上游 fixture 里
> **一次都没出现过**，是臆造；删掉这两个头之后就不需要它了。
> 详见 `docs/probe_findings.md` §G-11（已标注作废）与 §K。

### 已知限制

- **真机端到端已有真实数据验证**：在模拟器上用真实 openid 跑通了「馆藏检索 → 书目详情 → 我的借阅」，
  借阅明细与馆藏复本都正确渲染。唯一无法脚本化的是「微信里复制链接」这一步——Android 15 下 `adb`
  写不了剪贴板；为此向导页提供了「手动粘贴链接」的兜底输入框，真机上剪贴板读不到时也能走通。
- **`libseat.xynu.edu.cn` 未接入**：图书馆自主座位预约系统需 `studentNo` + 密码 + 姓名，
  且没有稳定的公开接口，代码里只留了 TODO 注释。
- **`libspace.xynu.edu.cn` 入口无效**：80/443 端口拒绝连接。
- 首页 `hall.html` 的 `toUrl` 入口实际是 **16 个**（早期探测记录写 15 个），已按真实数据断言。
- **首页只保留一个馆藏入口**：即「馆藏检索」（免登录、内外网都通，走 `libopac`）。
  老的 `BookSearchScreen` 仍保留在代码和路由里——它绑着 OPAC 登录态与续借，「我的借阅」依赖它——
  但不再是首页入口，避免两个同名入口指向不同网络范围。

### 两个「只有真机才暴露」的坑（已修，有护栏）

不是写错字符，而是测试层天然覆盖不到的盲区。记在这里，避免以后改回去。

**1. `SeatViewModel` 的属性声明顺序会决定会不会闪退**

用户报的「给了 Cookie，删后台再打开直接闪退」就是它。Kotlin 按**声明顺序**
初始化属性，`init {}` 块夹在中间就地执行。当时 `refreshThrottle` 声明在 `init` **之后**，
而 `init` 里的 `refreshTier()` 会读它 —— 读到的是 `null`，NPE，闪退。

只在**存过会话之后**的冷启动才崩：没有会话时 `refreshTier()` 提前 return，碰不到那个 `null`。
所以「随手点一遍」永远发现不了。

→ 护栏：`SeatViewModelInitOrderTest` 直接读源码文本，断言 `init` 块里读到的每个字段
都声明在 `init` 之前。（把声明挪回去跑过，确认它真的会 FAILED，不是空转。）

**2. 无 factory 的 `viewModel()` 只在真机闪退**

`viewModel()` 底层用反射找**无参构造**。有构造参数的 VM 直接用它，
编译通过、单测通过（单测直接 `new`，不经过 `ViewModelProvider`），真机
`NoSuchMethodException` 闪退。→ 有构造参数的 VM 一律走 `LibraryViewModelFactory`，
`UiContractRegressionTest` 扫源码拦着。

## 🔍 抓取协议说明

本应用通过模拟浏览器会话访问学校系统公开接口：

### 教务系统（正方 V9）`http://210.43.24.144/jwglxt`

- **登录**：GET `xtgl/login_slogin.html` 取 csrftoken → GET `login_getPublicKey.html` 取 RSA 公钥 → POST `xtgl/login_slogin.html` 提交 `yhm` + `mm`（RSA/ECB/PKCS1 加密）
- **课表**：GET `kbcx/xskbcx_cxXskbcxIndex.html` 取学期下拉 → POST `kbcx/xskbcx_cxXsKb.html?gnmkdm=N253508` 提交 `xnm/xqm/kzlx`，解析 JSON `kbList`
- **成绩**：POST `cjcx/cjcx_cxDgXscj.html?doType=query&gnmkdm=N305005` 提交学期，解析 JSON

### 图书馆 OPAC（阿法迪）`http://222.21.62.201:8011/opac`

- **登录**：POST `pages/include/checklogin.jsp`，密码使用页面内嵌 JS 的 AES-128-ECB 加密（密钥从登录页源码自动提取，实现见 `data/crypto/AesEcbCrypto.kt`）
- **检索**：POST `opac/search`，参数 `q`（关键词）/`field`（题名/作者等）
- **馆藏**：GET `opac/book/getHoldingsInformation/{bookId}` 返回 JSON 数组（索书号/条形码/馆藏地/状态）
- **我的借阅**：GET `opac/mylibrary`（需登录会话）
- **续借**：POST `opac/mylibrary/renew`

### 资讯（WebPlus 站群）

- 教务处动态：`http://jwc.xynu.edu.cn/jwdt.htm`，解析 `li > span(日期) + a(标题)`
- 图书馆公告：`http://lib.xynu.edu.cn/`，解析 `.tzgg` 区块

> 学校系统升级或改版可能导致接口失效，届时需同步更新 `data/net` 与 `data/parser`。

## 📁 项目结构

```
app/src/main/java/com/xynu/campus/
├── MainActivity.kt            # 入口 + 导航路由
├── data/
│   ├── model/Models.kt        # 数据模型
│   ├── net/                   # 网络层（JwApi/LibApi/NewsApi/OkHttp）
│   ├── parser/                # HTML/JSON 解析器
│   ├── crypto/                # RSA/AES/Keystore 加密
│   └── repo/                  # 凭据存储
├── library/                   # 图书馆功能模块（纯本地，无服务器）
│   ├── crypto/                # openid 提取与校验
│   ├── graphql/               # 选座 GraphQL 文档 + 客户端 + 响应解析
│   ├── migrate/               # F5 身份迁移
│   ├── model/                 # HallEntry / SeatSession / Seat / SeatLayout …
│   ├── net/                   # 白名单拦截器 + 模块级 OkHttp + 落地态判定 + 馆藏仓库
│   ├── parser/                # hall_map / AES 密钥 / 借阅摘要+书单 / 馆藏检索 / 书目详情
│   ├── seat/                  # WorkManager 提醒 + 通知跳转链
│   ├── store/                 # EncryptedSharedPreferences 身份存储
│   ├── ui/                    # 借阅页 / 引导向导 / 选座页 / 迁移页 / 馆藏检索+详情页
│   ├── util/                  # 日志脱敏 / 文本处理 / 刷新节流
│   ├── vm/                    # BorrowViewModel / SeatViewModel / CatalogViewModel
│   └── web/                   # JSBridge（四方法）+ WebView 容器（当前无生产调用方）
└── ui/
    ├── home/                  # 首页 + 资讯
    ├── jw/                    # 教务：登录/课表/成绩
    ├── lib/                   # 图书馆：登录/检索/借阅
    ├── tools/                 # 设置
    ├── common/                # 通用组件
    └── theme/                 # 主题色（豫南红）
```

> **`web/` 与 `assets/library/wizard.html` 目前没有接线。**
> F1 引导向导（`Routes.LIBRARY_WIZARD`）实际走的是纯 Compose 实现
> `library/ui/BorrowWizardScreen.kt`，剪贴板由
> `BorrowViewModel.onClipboardReadRequested()` 读取，不经过 JSBridge。
> `LibraryJsBridge` / `LibraryWebView` / `androidDeps()` / `wizard.html`
> 在 `app/src/main` 里**没有任何实例化或调用点**，
> 仅由 `LibraryJsBridgeContractTest` 等测试覆盖。
> 这两条路线留一即可 —— 现在是把两套都写了但只接了一套。

## ✅ 测试

```
./gradlew :app:testDebugUnitTest
```

用真实抓取的校内页面作为 fixture 验证解析器（`app/src/test/resources/fixtures/`）。

图书馆功能模块的测试覆盖「验收标准」的每一条硬线，几条值得单独说明：

| 测什么 | 怎么测 | 为什么这么测 |
|---|---|---|
| JSBridge 只暴露四个方法 | 反射扫 `@JavascriptInterface` 注解 | 多一个方法就多一项能力交给 HTML |
| 白名单只放行这 4 个 host | 真实 OkHttp + MockWebServer 断言 `requestCount == 0` | 只测字符串判断会漏掉"拦截器没挂上" |
| 基础档零网络 | 反射扫 Worker 字段 + 源码扫 SDK 引用 | 「看起来没发请求」和「结构上不可能发」是两件事（反射那条只扫本类字段，真正硬的是源码扫描） |
| 40001 只重试 1 次 | MockWebServer 计数器断言恰好 2 次请求 | 无限重试会被风控，不重试则体验差 |
| 刷新 <3s 被钳制 | 注入时钟的 `RefreshThrottle` | 纯数学逻辑，不该被 Android 依赖绑死 |
| 日志不含完整凭据 | 挂 `LibraryLog.sink` 收集全部日志再断言 | 靠 code review 查日志泄露不可靠 |
| 迁移能恢复两个模块 | export → clearAll → import 逐字段比对 | 只测一半会在真换机时才暴露 |
| ViewModel 必须带 factory | 源码扫"有构造参数的 VM 是否走 `LibraryViewModelFactory`" | 无 factory 的 `viewModel()` 会反射建对象，**编译/单测都不报错，只在真机闪退** |
| 用户看不到 Markdown 星号 | 扫 UI 源码字符串字面量里的 `**...**` | Compose 的 `Text` 不解析 Markdown，字面星号会原样显示 |
| 检索/借阅解析对得上真实页面 | 用公网抓的原文做 fixture，断言解析结果（条目数、字段语义） | 手写 HTML 只能证明「解析器能解析我以为的页面」，证明不了真实页面 |
| 翻页失败后重试不会退页 | 假数据源 + `runTest` 断言请求页码序列 | 「失败保留旧页」和「重试打失败那页」是一对容易写反的边界 |
| 界面不打印凭据 URL | 扫 UI 源码里「数据来源」那行是否经过 `hostOf()` | 日志脱敏只管了 logcat，**界面**是同一个问题的另一个出口 |

**加密存储的测试必须在设备上跑**（Robolectric 没有 AndroidKeyStore provider）：

```
./gradlew :app:connectedDebugAndroidTest
```

上面两条（以及"正文色对比度"）是**真机冒烟时才发现**的缺陷，单测层天然覆盖不到，
所以补成了静态护栏，并对护栏本身做过变异测试（把代码改回错误写法，确认测试会变红）。
完整的踩坑记录见 [`docs/probe_findings.md`](docs/probe_findings.md) §H。

## 📜 免责声明

- 本应用为个人学习与便利开发的非官方工具
- 登录信息仅保存在本机（Keystore 加密），不会上传任何个人数据
- 请勿使用本应用进行任何违反学校规定的操作
- 数据以学校各系统网页版为准

## ⚖️ 许可

本项目采用 **GNU General Public License v3.0** 发布，全文见 [`LICENSE`](LICENSE)。

需要留意的两点：

- **GPL-3.0 有传染性**：基于本项目代码的衍生作品在分发时也必须以 GPL-3.0 开源。
- **仓库里没有构建产物**：APK 不入库（`.gitignore` 已排除），请按上文「从源码构建」自行编译。
  提交 issue 前请先说明你的 `./gradlew :app:testDebugUnitTest` 是否全绿。

## 🤝 参与贡献

欢迎提 issue / PR，但请先读两条本仓库的硬规矩：

1. **绝不提交真实凭据。** 包括 openid、`sn`、`wechatSESS_ID`、`Authorization`、Cookie、
   学号、姓名。fixture 里的凭据必须是占位符（`oTEST…` / `"0".repeat(144)` 之类）。
   提交前跑一遍：

   ```bash
   pwsh -NoProfile -File scripts/scan_secrets.ps1
   ```

   它以「待提交文件」为扫描范围，返回 0 才算干净；CI 也会跑同一条命令。

2. **改了上游协议相关代码，就要补一条会失败的测试。**
   这个项目里几乎每个真机 bug 都留了一条「改回去就变红」的护栏测试
   （见下方「测试」章节），新增解析/重定向逻辑请照办。

> 学校系统改版会让解析器失效，这类 issue 最有价值 —— 附上抓到的页面原文
> （**记得先脱敏**）即可。
