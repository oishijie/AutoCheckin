# FAFU签到

> 到点自动走完学校「晚归签到」：进签到页 → 重新定位 → 拍照 → 回传。全程无人值守。

基于 Android 无障碍服务的自动化应用。**免 root、零第三方依赖、APK 不到 100 KB。**

- 仓库：<https://github.com/oishijie/AutoCheckin>
- 下载：<https://github.com/oishijie/AutoCheckin/releases/latest>
- 反馈：<https://github.com/oishijie/AutoCheckin/issues>

---

## 它做什么

| 能力 | 说明 |
|---|---|
| 模拟点击 | 用系统无障碍服务读取屏幕控件后派发手势，不注入代码、不修改目标应用数据 |
| 步骤表驱动 | 整条流程写在一张文本表里。目标应用改版后改表即可，**不用重新打包** |
| 图像识别兜底 | 拍照快门没有稳定文本，改用截图识别「下半屏最长连续白色段」定位，抗 UI 改版 |
| 卡住自动救 | 页面偶发卡在「加载中」时，自动退回并重开签到卡，最多重试 3 轮 |
| 后台跳转豁免 | 挂一个 1×1 隐形窗口拿到 Android 12+ 的后台启动白名单，否则定时触发时跳转会被系统静默丢弃 |
| 每日定时 | AlarmManager 精确闹钟 + 开机自动重排，熄屏也能跑 |
| 调试模式 | 只跑 1 轮、失败即停并保留现场，方便 dump 控件树排查 |

## 安装

从 [Releases](https://github.com/oishijie/AutoCheckin/releases/latest) 下载 `FAFU-Checkin-vX.Y.apk` 装到手机（Android 8.0+）。

首次使用三步：

1. **状态**页 → 打开无障碍设置，开启本应用的服务；再授予「显示在其他应用上层」。
2. **设置**页 → 填签到入口链接与目标应用包名，保存。
3. 打开每日定时并设定时间（建议设在签到窗口开启后几分钟，留出 App 加载余量）。

## 从源码构建

需要 JDK 17、Gradle 8.9、Android SDK 34。`android/local.properties` 里写好 `sdk.dir`。

```bash
cd android
gradle assembleRelease
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

> 项目路径含中文时 AGP 默认会拒绝构建，已在 `android/gradle.properties` 里用 `android.overridePathCheck=true` 放行。

## 项目结构

```
android/
├─ app/src/main/java/com/xiaoyao/autocheckin/
│  ├─ MainActivity.kt                主界面：状态 / 设置 / 关于 三页
│  ├─ CheckinAccessibilityService.kt 无障碍服务，整条流程的调度中枢
│  ├─ StepEngine.kt                  步骤解析与控件匹配、手势派发
│  ├─ Step.kt                        步骤模型 + 内置默认步骤表
│  ├─ ShutterFinder.kt               截图识别拍照快门（最长连续白段）
│  ├─ Updater.kt                     关于页的版本检查（GitHub Releases）
│  ├─ ConfigStore.kt                 配置持久化（SharedPreferences）
│  ├─ Scheduler.kt / AlarmReceiver.kt / BootReceiver.kt   定时与开机重排
│  ├─ OverlayProbe.kt                1×1 隐形窗口，换取后台跳转豁免
│  └─ Logger.kt                      环形缓冲 + 落盘日志
└─ app/src/main/res/                 布局、自绘 Material 3 样式、无障碍配置
android/README.md                    构建手册与踩坑记录（含详细实现笔记）
```

## 步骤表怎么填

「设置」页里每行一条，自上而下执行，格式 `kind|value|contains|optional|waitMs|last`。

| kind | 含义 | value 示例 |
|---|---|---|
| `tab` | 点击底部/顶部 Tab | `消息` |
| `text` | 按显示文字找控件 | `重新定位` |
| `textwait` | 等文字出现，但不触发重试 | `单击拍照` |
| `id` / `desc` / `cls` | 按控件 id / 无障碍描述 / 类名找 | `e605` |
| `uni` | 按字体图标码点找（PUA 字符） | `e62b` |
| `uiwait` | 等某个控件出现（有超时） | `e605` |
| `xy` | 按坐标点，支持百分比 | `50%,91%` |
| `shutter` | 截图识别快门并点击 | 空 |
| `scroll` | 滚动 | 空 |
| `sleep` | 静置等待 | `1500` |

## 已知限制

- **锁屏后无障碍服务无法操作界面**，需保持屏幕亮起或使用无密码解锁。
- 目标页面结构大改时，步骤表需要同步更新；若按钮改用纯图片，需依赖 `xy` / `shutter` 兜底。
- 部分机型需在电池优化里把本应用设为「不优化」，否则后台会被杀。

## 使用边界

仅用于**本人账号**的日常签到自动化。请勿用于代签，也不要绕开人脸、定位等真实性校验。学校若明令禁止自动化，风险自担。本仓库不含目标应用的任何代码或数据。

---

<details>
<summary>附录：早期评估过的 AutoJs6 方案（未采用）</summary>

选型阶段对比过「AutoJs6 脚本」与「自研 Android App」，最终选后者：宿主可控、体积从 130 MB 降到不足 100 KB、不依赖第三方应用常驻。

AutoJs6 方案的关键配置（`main.js` 顶部 `CONFIG`）：

```js
intentUrl: "",        // 贴你的 intent 链接（原样整条粘贴）
targetPackage: "",    // 签到 App 的包名
steps: [
    // { name: "点击晚归签到", kind: "text", value: "晚归签到" },
],
```

需按顺序开齐：无障碍服务 → 悬浮窗 → 电池优化白名单 → 自启动 → 后台任务上锁。缺一项都会在熄屏时失败。

</details>
