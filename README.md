# 信师大 · 一站式校园服务 App

面向**信阳师范大学**学生的一站式校园服务 Android 应用（Kotlin + Jetpack Compose 原生开发，非官方、个人开发）。

> ⚠️ 本应用调用的是学校各系统的**公开网页接口**（模拟浏览器登录后抓取数据），与学校官方无隶属关系。数据仅供个人查询使用，请勿滥用。

---

## ✨ 功能总览

| 模块 | 功能 | 是否需要登录 |
|---|---|---|
| 🏠 首页 | 品牌横幅、常用服务入口、教务通知、图书馆公告聚合 | 否 |
| 📅 我的课表 | 正方教务课表（周视图，按星期/节次排布） | 教务系统 |
| 📊 成绩查询 | 按学期查询成绩、学分、绩点 | 教务系统 |
| 🔍 馆藏检索 | 图书馆 OPAC 检索（题名/作者/主题/ISBN），查看索书号、馆藏地、在馆状态 | 否（检索免费） |
| 📚 我的借阅 | 查看当前借阅列表、应还日期、在线续借 | 图书馆 |
| 🏫 教务登录 / 图书馆登录 | 单独登录，凭据 Keystore 加密存储 | — |
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

### 使用说明

1. **馆藏检索**：无需登录，进入"馆藏检索"→ 输入书名/作者→ 搜索 → 点"查看馆藏"看索书号和在馆状态
2. **教务课表/成绩**：首次进入去"教务登录"用学号+教务密码登录（勾选记住则本地加密保存），回课表/成绩页即可查看
3. **我的借阅**：用图书馆账号（学号/校园卡号，初始密码身份证后6位）登录，可看借阅列表并在线续借

## 🔍 抓取协议说明

本应用通过模拟浏览器会话访问学校系统公开接口：

### 教务系统（正方 v5）`http://210.43.24.144/jwglxt`

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
└── ui/
    ├── home/                  # 首页 + 资讯
    ├── jw/                    # 教务：登录/课表/成绩
    ├── lib/                   # 图书馆：登录/检索/借阅
    ├── tools/                 # 设置
    ├── common/                # 通用组件
    └── theme/                 # 主题色（豫南红）
```

## ✅ 测试

```
./gradlew :app:testDebugUnitTest
```

用真实抓取的校内页面作为 fixture 验证解析器（`app/src/test/resources/fixtures/`）。

## 📜 免责声明

- 本应用为个人学习与便利开发的非官方工具
- 登录信息仅保存在本机（Keystore 加密），不会上传任何个人数据
- 请勿使用本应用进行任何违反学校规定的操作
- 数据以学校各系统网页版为准