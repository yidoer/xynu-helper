# 信师大 · 一站式校园服务 App

面向**信阳师范大学**学生的校园服务 Android 应用。Kotlin + Jetpack Compose 原生开发，非官方、个人开发。

> ⚠️ 本应用调用学校各系统的**公开网页接口**（模拟浏览器登录后读取数据），与学校官方无隶属关系。数据仅供个人查询，请勿滥用。

## 功能

| 模块 | 功能 | 需要登录 |
|---|---|---|
| 🏠 首页 | 常用服务入口、教务通知、图书馆公告聚合 | 否 |
| 📅 我的课表 | 正方教务课表，周视图按星期/节次排布 | 教务系统 |
| 📊 成绩查询 | 按学期查询成绩、学分、绩点 | 教务系统 |
| 🔍 馆藏检索 | OPAC 检索 + 书目详情 + 收藏，校内外网络都能用 | 否 |
| 📚 我的借阅 | 当前借阅、应还日期、在线续借 | 图书馆 |
| 📖 座位预约 | 到点本地提醒（基础档）/ Cookie 解锁自动选座（高级档） | 微信内取凭据 |
| 🔑 身份迁移 | 两台手机间本地导出/导入图书馆身份，不经服务器 | 否 |
| ⚙️ 设置 | 关于、数据来源说明 | — |

## 安装

**方式一：直接装 APK**
把 `app-debug.apk` 传到手机安装即可（需允许「安装未知来源应用」）。要求 **Android 8.0+**（minSdk 26）。

**方式二：自己编译**

```bash
# 需要 JDK 17 + Android SDK (compileSdk 35)
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 技术栈

Kotlin · Jetpack Compose + Material 3 · MVVM (StateFlow) · OkHttp + Jsoup · DataStore + Android Keystore · WorkManager
构建：Gradle 8.11.1 / AGP 8.5.2 / JDK 17 / minSdk 26 · targetSdk 35

## 使用要点

- **馆藏检索**免登录，输入关键词即可查书、看书目详情与在馆状态，可收藏。
- **课表/成绩**首次使用需在「教务登录」用学号 + 教务密码登录（凭据本地 Keystore 加密）。
- **我的借阅**首次需在微信里取一次 `openid`（App 内有图引导），之后自动查询。
- 借阅凭证等同于账号本体，请勿分享迁移码。

## 安全边界

- **无服务器**：没有任何上报、统计、崩溃 SDK，凭据只存在本机 `EncryptedSharedPreferences`。
- **域名白名单**：图书馆模块的 OkHttp 拦截器只放行 4 个 host，放行范围仅限该模块。
- **日志脱敏**：`openid`、Cookie、密码在日志与界面里都不完整出现。
- **禁止备份**：`allowBackup="false"` + 数据提取规则排除凭据文件。

## 测试

```bash
./gradlew :app:testDebugUnitTest        # 32 个测试类 / 327 个用例
./gradlew :app:connectedDebugAndroidTest # 加密存储相关，需真机或模拟器
```

用真实抓取的校内页面作为 fixture 验证解析器。项目里大量测试是**回归护栏**：把代码改回错误写法就会变红，例如「白名单只放行 4 个 host」「基础档零网络请求」「会话失效只重试 1 次」。

## 项目结构

```
app/src/main/java/com/xynu/campus/
├── MainActivity.kt      # 入口 + 导航路由
├── data/                # 网络层 / 解析器 / 加密 / 凭据存储
├── library/             # 图书馆模块（借阅 / 选座 / 馆藏检索 / 身份迁移）
└── ui/                  # home / jw 教务 / lib 图书馆 / tools / common / theme
```

## 参与贡献

1. **绝不提交真实凭据**（openid、sn、Cookie、学号、姓名）。fixture 里必须是占位符。提交前跑：

   ```bash
   pwsh -NoProfile -File scripts/scan_secrets.ps1   # 返回 0 才算干净
   ```

2. **改了上游协议相关代码，就补一条会失败的测试**。这个项目几乎每个真机 bug 都留了护栏。

学校系统改版导致解析失效的 issue 最有价值，附上**已脱敏**的页面原文即可。

## 免责声明

个人学习与便利开发的非官方工具。登录信息仅保存在本机（Keystore 加密），不上传任何个人数据。
请勿用于违反学校规定的用途。数据以学校各系统网页版为准。

## 许可

[GNU General Public License v3.0](LICENSE)。衍生作品分发时须同样以 GPL-3.0 开源。
仓库不含构建产物，APK 请自行编译。

---

<details>
<summary>📖 详细文档（功能细节 / 抓包协议 / 踩坑记录）</summary>

完整的原版说明已备份至 [`docs/README-full.txt`](docs/README-full.txt)，包含：

- 图书馆模块两档选座设计的完整说明与真机截图
- 各系统抓包协议：正方教务 V9、阿法迪 OPAC、超星移动图书馆、「我去图书馆」选座
- 一条条「只有真机才暴露」的坑与对应护栏测试
- release 签名与 keystore 配置步骤

上游协议的实验记录见 [`docs/probe_findings.md`](docs/probe_findings.md)。

</details>
