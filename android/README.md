# FAFU签到 · 自研 Android 版

> 纯自研、零第三方依赖、免 root 的无障碍签到 App。
> 包名 `com.xiaoyao.autocheckin`，代码 725 行 Kotlin，不引用任何 androidx 组件。
>
> **状态：✅ 已构建通过** — 产物 `app-release.apk` **41.5 KB**，v2 签名验证通过，可直接安装。
> 构建时间 2026-09-25，环境全部就绪（见下节），下一步只差填入签到步骤表。

---

## 一、环境（本机已装好 ✅）

全部环境已完成安装，**无需再装**。

| 组件 | 版本 | 路径 |
|---|---|---|
| JDK | Temurin 17.0.20+8 | `D:\Development\java\jdk-17.0.20+8` |
| Gradle | 8.9 | `D:\Development\gradle-8.9` |
| Android SDK | cmdline-tools latest | `D:\Development\android-sdk\cmdline-tools\latest` |
| platform-tools | 37.0.1（含 adb） | `D:\Development\android-sdk\platform-tools` |
| Android Platform | 34（rev 3） | `D:\Development\android-sdk\platforms\android-34` |
| Build-Tools | 34.0.0 | `D:\Development\android-sdk\build-tools\34.0.0` |

Android SDK 总计约 **419 MB**。JDK 与 Gradle 是复用本机 `D:\Development` 下原有的（未重复下载）。

**已写入的用户级环境变量**（只增不改，未触碰任何原有条目）：

```
JAVA_HOME        = D:\Development\java\jdk-17.0.20+8
GRADLE_HOME      = D:\Development\gradle-8.9
ANDROID_HOME     = D:\Development\android-sdk
ANDROID_SDK_ROOT = D:\Development\android-sdk
```

PATH 新增 4 条：`jdk-17.0.20+8\bin`、`gradle-8.9\bin`、`android-sdk\platform-tools`、`android-sdk\cmdline-tools\latest\bin`

> ⚠️ 环境变量需要**新开一个终端**才能读到，已打开的窗口不会自动刷新。

### 如需补装其他 SDK 组件

```powershell
$env:JAVA_HOME = 'D:\Development\java\jdk-17.0.20+8'
& "D:\Development\android-sdk\cmdline-tools\latest\bin\android.exe" sdk --sdk="D:\Development\android-sdk" install "platforms;android-35"
```

**两个坑（本次安装实际踩到的）**：

1. 老的 `sdkmanager.bat` 已废弃，会转发给新版 `android.exe`；而且 **`.bat` 传参会被 cmd.exe 按分号截断**（`platforms;android-34` 被拆成两个包名导致 not found）。**必须用 `android.exe` 直接调用。**
2. `android.exe` 的 `sdk list` 子命令目前有崩溃 bug（退出码 `0xC0000409`），但 `sdk install` 工作正常——**末尾那个崩溃码可以无视**，以实际目录产出为准。

---

## 二、构建

> ✅ **已实测构建成功**（2026-09-25）：产物 41.5 KB，签名验证通过。下面的命令是**实测可用的原样版本**。

### 方式 A：命令行（推荐）

```powershell
$env:JAVA_HOME = 'D:\Development\java\jdk-17.0.20+8'
$env:GRADLE_USER_HOME = 'D:\Development\gradle-home'
$env:ANDROID_HOME = 'D:\Development\android-sdk'
$env:ANDROID_SDK_ROOT = 'D:\Development\android-sdk'

& 'D:\Development\gradle-8.9\bin\gradle.bat' -p 'D:\VibeCoding\自动签到\android' assembleRelease --no-daemon --console=plain *> 'D:\VibeCoding\自动签到\android\build.log'
```

产物：`android/app/build/outputs/apk/release/app-release.apk`

> `GRADLE_USER_HOME` 指到 D 盘是**故意**的——本机 Gradle 家目录已从默认的 `C:\Users\administraor\.gradle`（361MB 且会持续增长）迁到 `D:\Development\gradle-home`，并已写入用户级环境变量。新开终端会自动生效，无需每次手写。
>
> 项目未包含 `gradle-wrapper.jar`（二进制文件）。若想用 `gradlew` 形式，先跑一次 `gradle wrapper --gradle-version 8.9` 生成即可，非必需。

### ⚠️ 本次构建踩的 4 个坑（都有通用性，勿重犯）

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | 进程在 **恰好 2m1s** 被杀，日志无 FAILURE 报告 | 外层调用工具/终端的**默认超时**把 Gradle 掐断了 | 后台运行 + 放宽 timeout；日志用 PowerShell `*>` 重定向到文件 |
| 2 | 报 `Could not read workspace metadata from ...\caches\8.9\transforms\...\metadata.bin` + 136 more failures | 第 1 次被掐断的构建**写坏了 transform 缓存** | 删除 `%GRADLE_USER_HOME%\caches\8.9\transforms` 整个目录，Gradle 会自动重建（不涉用户数据） |
| 3 | `org.gradle.jvmargs` 里的中文路径告警 / 缓存占 C 盘 | Gradle 家目录默认在 C 盘 | 整体迁到 `D:\Development\gradle-home` + 写 `GRADLE_USER_HOME` |
| 4 | **`Your project path contains non-ASCII characters`** 直接拒绝构建 | AGP 硬性拒绝非 ASCII 项目路径（本项目含「自动签到」） | 在 `gradle.properties` 加 **`android.overridePathCheck=true`** —— 实测放行，**无需真的改目录名** |

> 关于坑 4：虽然可以绕过，但若将来遇到 AGP 其他诡异的路径问题（如 aapt2 资源编译乱码），最干净的解法仍是把项目放到纯英文路径。**注意**：本目录同时是 WorkBuddy 的工作区根，改名会让工作区绑定失效，所以当前**保持中文路径**。

> 📄 `build.log` 是 PowerShell `*>` 输出的 **UTF-16LE** 文件，用普通 `grep`/`tail` 看会出现「每个字符带一个空格」的乱码。读取时先转码：`tr -d '\000' < build.log`。

### 方式 B：Android Studio（可选，本机未装）

1. **File → Open**，选 `D:\VibeCoding\自动签到\android`（选到 `android` 这层，不是 `app`）
2. 等 Gradle Sync 完成
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**

`local.properties` 已写好 `sdk.dir`，Android Studio 打开即可识别 SDK。

> ✅ **中文路径问题已解决**：项目路径含「自动签到」四个中文字，AGP 8.5.2 会直接拒绝构建。已在 `gradle.properties` 加入 `android.overridePathCheck=true` 绕过，**实测构建通过，无需改名目录**。详见上一节的坑 4。

---

## 三、安装到手机

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

或把 APK 传到手机上手动点击安装。

> release 构建已用 debug 签名（见 `app/build.gradle.kts`），方便直接安装调试。要正式发布请换成自己的 keystore。

---

## 四、首次配置

1. 打开 App → 点 **① 去开启无障碍服务** → 在系统列表里找到「FAFU签到」→ 开启
2. 回到 App，状态栏应变成 **✅ 已开启**
3. 填 **② 签到入口链接**（你的 intent 链接，原样粘贴）
4. 填 **④ 点击步骤表**（见下一节）
5. 设 **⑤ 每日触发时间**，勾选「启用每日定时自动签到」
6. 点 **保存配置并应用定时**
7. 点 **立即执行一次** 测试，看日志

---

## 四.5、实测流程（2026-09-25 用 adb 真机侦察）

**目标 App**：`cn.edu.fafu.iportal`（福建农林大学门户，基于**华为 WeLink / W3 框架**）

### 入口链接

| 项 | 值 |
|---|---|
| **推荐填写的 intentUrl** | `hwwelink://cn.edu.fafu.iportal` |
| 实测效果 | ✅ 直接启动 App 到主页（`huawei.w3.MainActivity`） |

> ⚠️ **千万不要带 `#Intent;component=...` 那一段**。实测两点：① 系统会把 `#` 之后的内容整段丢掉（`am start` 回显只剩 `dat=hwwelink://cn.edu.fafu.iportal`）；② `CategoryChatActivity` **未导出**（`not exported from uid 10903`），带 component 必然被 `SecurityException` 拒绝。所以只用 `hwwelink://` 裸地址。

### 两个入口对比（均已实测）

| 入口 | 效果 | 稳定性 | 用法 |
|---|---|---|---|
| `hwwelink://welink.huawei.com/qr?qrcode=<base64>` | **直达通知中心**（少一步） | ⚠️ 未知（qrcode 是 1024 字节加密数据，可能有时效） | **默认主入口**（用户 2026-09-25 拍定「扫码优先」）→ `ConfigStore.SCAN_INTENT` |
| `hwwelink://cn.edu.fafu.iportal` | 到**主页**（须再点「消息」Tab →「通知中心」，多一步） | ✅ 高（不依赖任何凭证） | **自动降级兜底** → `ConfigStore.HOME_INTENT` |

> **降级逻辑**：按 `配置里的入口 → SCAN_INTENT → HOME_INTENT` 顺序逐个尝试，每个启动后等 5 秒检测前台包名。
>
> ⚠️ **关键**：两个入口的**包名完全相同**（都是 `cn.edu.fafu.iportal`），
> 所以"比对包名"只能判断**是否进了目标 App**、**无法区分进了哪一页** ——
> 早期版本就是栽在这里：降级判断形同虚设。
> 现在改用**当前 Activity 类名**判断是否直达（`CategoryChatActivity` ⇒ 直达），
> 类名由无障碍 `TYPE_WINDOW_STATE_CHANGED` 事件缓存。直达时步骤表里的 `tab` 步骤会自动跳过。
>
> ⚠️ 使用 qr 入口时**必须做 URL 编码**（`+` → `%2B`、`/` → `%2F`）。否则 `+` 会被 `getQueryParameter` 解码成空格，参数直接损毁。原始串见 `refs/deeplink-qr-raw.txt`。

### 已实测跑通的完整链路（2026-09-25，无障碍自动执行 + adb 验证）

```
【主路径】扫码 deeplink（默认主入口）
  └─ 直达【通知中心】CategoryChatActivity          ✅ 实测确认
       └─ 【滚到列表底部】service_chat_list
            └─ 点最下面那条签到卡片  id=ll_app_card  (last=1)
                 └─ 签到页 BrowserActivity（H5，无障碍可读 ✅）标题=「签到」
                      ├─ 「定位签到」 [45,316][1035,388]   →  未签到
                      ├─ 「拍照签到」 [45,562][1035,634]   →  未签到
                      ├─ 签到要求：体现实验室或学生宿舍等校园场所
                      ├─ 可签到时间：2026-09-24 20:00 - 2026-09-24 23:00
                      ├─ 可补签时间：2026-09-24 23:00 - 2026-09-24 23:30
                      └─ 签到名称：26年春季学期晚归签到

【兜底路径】主页 deeplink
  └─ 停在【主页】Tab（mainTitle=学生）
       └─ 点底部 Tab「消息」(desc=消息) → 切到会话列表
            └─ 点会话「通知中心」(text=通知中心) → 汇合到上面那条路径
```

> 🔴 **列表顺序是「旧 → 新」，最新的提醒在最下面。**
> 实测：点第一条卡片进的是 **9/22** 的任务页——顶部是最旧的。所以点卡片**必须带 `last=1`**，否则天天点到过期任务。

### 当前默认配置（已预置进 App，无需手填）

```
# ① 切「消息」Tab —— 扫码直达时会被自动跳过
tab|消息|0|1|6000

# ② 点「通知中心」会话 —— 扫码直达时会被自动跳过
tab|通知中心|0|1|10000

# ③ 通知中心列表滚到底部（最新提醒在最下面）
scroll|service_chat_list|0|1|5000

# ④ 点最新（最下面）那条签到卡片   ← last=1 必不可少
id|ll_app_card|0|0|10000|1

# ⑤ 确认签到页真的加载出来   ← 用页面独有文案，写「签到」会命中通知中心卡片
wait|签到要求|1|0|12000

# ⑤≠ 今天已经签过了？命中就【立即收工】，不拍照、不提交、不重试
#    用户自己手动签过时 App 毫不知情（submitDate 只记 App 点过的提交），
#    此时签到页是「已完成」态：有「已签到」和照片，但【没有】「重新定位」按钮，
#    再往下走必然白跑 4 轮 + 冷启动。done 直接看页面事实，命中即整轮成功结束。
done|已签到|1|0|2000

# ⑤- 主动点一次「重新定位」：进页面时定位是异步的，不等它就去拍容易白拍一张
text|重新定位|0|0|10000

# ⑤+ 等定位真的成功（保险丝：没成功就不往下走）
wait|已经进入签到要求范围|1|0|20000

# ⑤++ 静置 3 秒 —— 文案出现 ≠ 组件就绪（地图 / 定位 / 相机都还在异步初始化）
sleep|3000|0|0|1000

# ⑥ 点「拍照」进内嵌相机（私有字体图标 \ue62b，无 id、无 desc，只能按码点认）
uni|e62b|0|0|12000

# ⑥+ 等相机页起来 ← 必须用 textwait：wait 超时会触发 BACK 重进，在相机页里退回去就乱套了
textwait|单击拍照|1|0|10000

# ⑥++ 提示出现 ≠ 预览就绪
sleep|1500|0|0|1000

# ⑦ 按快门（截图识别白色快门 —— 算法与兜底链见下方「快门识别」一节）
shutter|0|0|0|15000

# ⑦+ 等图片编辑页打开 + 照片处理完
#    别省：实测快门点完 0.7 秒就点「完成」等于没点；2500ms 也不够（照片没处理完）。
#    2026-09-25 23:00 对照实验后从 2500 放宽到 8000。
sleep|8000|0|0|1000

# ⑧ 编辑页点「完成」，把照片回传给签到页
id|tv_done|0|0|15000

# ⑮ 等照片回传落定（纯等，不设判据）
#    ⛔ 原为 uiwait|e605 —— 判据错误：照片回传后渲染的是真实 <img> 缩略图，
#       对无障碍是 text 为空的节点，真机上永远匹配不到 e605，
#       会让整条链路卡死在这里（2026-09-25 23:16~23:19 连跑 3 轮全部卡死：
#       空等 40 秒 → 判失败 → 冷启动重来 → ⑯⑰ 从未执行）。
sleep|6000|0|0|1000

# ⑯ 等「提交签到」按钮渲染出实际尺寸 ← 真正的就绪判据
#    （按钮从 [0,0][0,0] 变为 [69,2278][1011,2374]，942x96）
viswait|提交签到|0|0|30000

# ⑰ 点提交（只能走无障碍 ACTION_CLICK，见下方说明）
#    ⛔ 末尾的 submit=1 是【一天只能签一次】的防线（2026-09-26 加，见下方「提交守卫」）：
#       ① 执行【前】查当日标记，今天已点过 → 跳过本步（⑱⑲ 照跑，用于确认）；
#       ② 点击派发成功后【立刻落盘】，不等结果。
text|提交签到|0|0|10000|0|1

# ⑱ 等结果弹窗 ← 「点到了 ≠ 签上了」的唯一解法
#    没照片时按钮同样可点，点下去 H5 静默拒绝、页面毫无变化
#    等不到 → 判本轮失败，但【不会】二次提交（⑰ 已落盘 + 引擎规定提交后不再重跑整轮）
viswait|您已成功签到|1|0|15000

# ⑲ 点掉结果弹窗的「确认」（optional：关不掉不影响签到已达成）
text|确认|0|1|5000
```

> `tab` 类型是专门为「切页」设计的：它同时接受 `contentDescription` 与 `text` 匹配
> （底部 Tab 靠 desc「消息」，会话列表项靠 text「通知中心」），
> 且在**入口已直达通知中心时会被整步跳过** —— 否则不但做无用功，
> 还会因为页面上有同名的**标题栏文字**而误点（这个坑已实测踩到：落点跑到 y=171 的顶部标题栏）。
>
> 第 ④ 步之后的部分为 **2026-09-25 真机实测补齐**：定位 → 拍照 → 回传 → 提交，整条链路已跑通。
>
> ⑯「提交签到」有两点必须留意：
> ① 按钮贴屏幕最底部（实测 `bounds=[69,2278][1011,2374]`，而 `displayMetrics` 高度只有 2273、
> 系统手势安全线更低到 2129），**服务内的坐标点击会被系统手势区吞掉**，只能靠无障碍 `ACTION_CLICK` ——
> 这正是它写成 `text` 而不是 `xy` 的原因。好在 `Button` 自身 `clickable=true`，引擎第一条路径就是 `ACTION_CLICK`。
> ② `viswait` 而不是 `wait`：`wait` 超时会触发「BACK 重开卡片」那套卡死恢复，而最后一步页面早已就绪，退回去只会把现场搞乱。
>
> 🐞 **2026-09-25 23:19 真实签到实测 —— 两条关键纠正**：
> 1. **⑮ `uiwait|e605` 判据是错的，已整步废弃**。当天连跑 3 轮全部卡死在这一步：
>    空等 40 秒 → 判失败 → 整轮冷启动重来 → ⑯⑰ **从未执行**。
>    而同一时刻 `screencap` 证明**照片早就上传成功了** ——「拍照签到」区渲染的是真实 `<img>` 缩略图
>    （带右上角 × 删除按钮），对无障碍就是个 **text 为空**的节点，屏幕上根本没有 e605 这个码点。
>    唯一可靠的判据：**「提交签到」按钮渲染出实际尺寸**。
> 2. **`adb shell input tap` 点得中这个"手势区按钮"** —— 本次真实提交就是用
>    `adb shell input tap 540 2326` 一次点中的（随即弹出「您已成功签到！」）。
>    它走 shell 注入通道、优先级高于系统手势识别；会被吞掉的是**服务内的 `dispatchGesture`**。
>    排查时别把这两条混为一谈。
>
> ✅ **「点到了」≠「签上了」已解决**：⑱ 步等「您已成功签到！」弹窗落地才判定成功
> （2026-09-25 23:19 实测该弹窗为原生 `AlertDialog`，文本可被无障碍读到）。
>
> ⚠️ **2026-09-26 修正 —— 该段原先紧跟着写的「等不到即判本轮失败重来」有隐患，已改**：
> 判失败本身没错（拿不到弹窗就不敢报成功），**但「重来」是错的** ——
> ⑱ 超时既可能是「H5 拒绝」，也可能是「**H5 已受理、只是弹窗慢**」。
> 后者意味着当天机会已经用掉了，此时的"重来"就是对同一天第二次点提交 —— 拿唯一一次机会冒险。
>
> ### 提交守卫（v2.16 起两道，v2.17 补第三道）
>
> 1. **⑰ 提交步打 `submit=1`**：执行【前】查 `ConfigStore` 的当日标记，今天已点过 → **跳过本步**
>    （⑱⑲ 照跑，用于确认）；点击派发成功后【**立刻**落盘】「今日已提交」——
>    **不等结果**，因为「当天机会是否已消耗」看的是请求有没有发出去，不是有没有被确认。
> 2. **提交发出后不再重跑整轮**：引擎记录本轮是否已发出提交，一旦发出，后续任何步骤失败
>    都以「**已提交、结果未确认**」收尾 + `dumpWindowsNow` 留现场，直接结束。
> 3. **`done` 步看页面事实**（v2.17）：前两道都用 App 自己的记忆判断，可用户**自己手动签过**
>    的时候 App 一无所知（`submitDate` 是空的）。所以第五步之后紧跟一条 `done|已签到`：
>    命中即说明今天已完成，整轮立即收工（不拍照、不提交、不重试）。
>    它同时兜住提交后的失败 —— `fail()` 在「提交已发出」时会先探一次页面，
>    若已是「已签到」态就直接判成功，而不是含糊地报「未确认」。
>
> 于是结果从「成 / 败」两态变成四态：
>
> | 状态 | 触发 | 行为 |
> |---|---|---|
> | ✅ 今天已完成 | `done` 命中页面「已签到」（含用户手动签到的情形） | 报完成 + 立即结束，零副作用 |
> | 🎉 真成功 | 全部跑通且等到成功弹窗 | 报成功 + dump 现场 |
> | ⚠️ 已提交、未确认 | 提交发出后某步失败，且页面也没变「已签到」 | **不报成功、不重跑**，dump 现场待人工核对 |
> | ❌ 普通失败 | 提交之前就失败 | 照常重跑整轮（安全，没消耗当天机会） |
>
> ⛔ 调试时想在一次会话里反复执行提交步，清标记：
> `adb shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez clearSubmitDate true`
> **正常签到绝不要清** —— 清了就等于放弃上面这层保护。
>
> ✅ **页面判据已闭环**（v2.17）：2026-09-26 拿到了真机「已签到」态的完整页面样本 ——
> 已签到时页面有 `已签到` + `查看签到定位`，未签到时是 `重新定位` + 相机图标，两者不冲突。
> ⑱ 的「等弹窗」不再是唯一判据：`fail()` 会先探一次页面，`done` 步更是从开局就拦一道。
>
> 📌 仍未闭环的是 ⑱ 本身 —— 它**故意保持 `optional=0`**：拿不到弹窗就不敢报成功，
> 因为**假成功比假失败更危险**（用户以为签上了，当天机会却已错过）。
> 现在多了「页面事实」这层兜底，误判空间已经小了很多。

### 结果通知（微信推送，v2.18 起）

签到是**无人值守**的：成功没人知道、失败更没人知道，唯一能发现的方式是自己想起来翻日志 ——
而那时当天窗口早过了（看到就来不及补签）。所以每个结果都会推一条到微信。

| 项 | 值 |
|---|---|
| 服务 | 用户自建 Cloudflare Worker：`https://push.142588.xyz/wxsend` |
| 协议 | `POST` + `Authorization: <token>` + JSON `{"title","content"[,"userid"]}` |
| 实现 | `Pusher.kt` —— HttpURLConnection + 手写 JSON 转义，**零依赖**（不引 OkHttp/Gson） |
| 配置 | 设置页「结果通知」卡片：开关 / 地址 / token / 接收人（openid，可留空） |
| 触发 | 四种结果各推一条：**签到成功 🎉 / 已签到 / 结果待确认 / 签到失败** |

用 POST 而不是文档里的 GET：token 是密钥，塞在 URL 里会进各种日志；放请求头干净得多。
服务端两种都支持。

> ⚠️ **token 绝不写进代码**：本仓库是**公开**的，密钥只能存在本机 SharedPreferences、
> 由用户在设置页里填。代码里的默认值只有服务地址，不含任何凭证。
>
> ⚠️ **推送失败绝不影响签到判定**：`Pusher.send()` 吞掉一切异常、只写日志（最长阻塞约 13 秒）。
> 没网、token 过期、Worker 挂了 —— 都不该改变「签上了还是没签上」这个结论，
> 更不该让流程在这里异常收尾。**发消息是锦上添花，签到才是本体。**
>
> ⚠️ **调试模式不推失败通知**：那时人就在电脑前盯着日志，推了纯打扰。
>
> 不用等真实签到就能验证链路：设置页点「发送测试推送」（会先保存再测），
> 或 `adb ... --ez testPush true`。结果写在日志里（响应体是 JSON，Toast 装不下）。

### 快门识别（v2.18 重写：从「一根白线」到「一个白斑」）

快门 `cb_capture` 是无文字、`clickable=false` 的裸 View，无障碍树里时有时无
（同机实测：`uiautomator dump` 有、服务读没有），所以只能靠截图找。

**老算法的致命弱点**：只看逐行白段、取全场最长的那一段。底部出现**任何**够长的白色块
（Toast 白底、白底输入框、白色按钮）都会被认成快门。段长是「一维」判据，挡不住「二维的假货」。

**新算法做连通域（blob）分析**，四重判据一起卡：

| 判据 | 阈值 | 依据 |
|---|---|---|
| 段长（只用来选种子） | ≥ 60px | 滤掉箭头、文字笔画 |
| 面积 | ≥ 3500px² | 真机白盘 ≈ 20800px²（163px 实心圆） |
| 填充率 | ≥ 0.45 | 面积 ÷ 外接矩形；正圆 π/4 ≈ 0.785，白底长条只有 0.1~0.3 |
| 长宽比 | 0.62 ~ 1.60 | 圆形必须接近方形，横条竖条全部出局 |
| 直径占屏宽 | 0.055 ~ 0.34 | 真机 163/1080 ≈ 0.15，卡一个宽松区间 |
| 连通域格子数 | ≤ 7000 | 超过即判「一大片白」，中止 flood fill 防扫全屏 |

外加**连拍两帧取交集**（间隔 320ms 各识别一次）：

- 两帧都命中且偏差 ≤ 12% 屏宽 → 取**两者均值**（比任何单帧都准）
- 只命中一帧 → 采信，但标注「置信度较低」
- 两帧都有但偏差过大 → 取分高者，并**如实标注偏差**

识别结果（几帧命中、直径/填充率/长宽比、双帧偏差）全部写进日志 ——
一眼能看出这次是「双帧一致」还是「勉强凑合」。

#### 识别失败时的兜底链

| 顺序 | 依据 | 说明 |
|---|---|---|
| ① | **上次成功坐标** | 存 SharedPreferences（归一化比例，换分辨率也能用）。快门在同一个 App 的同一种相机页里位置固定，**上次准、这次也准** |
| ② | 默认比例 `50%,91%` | ⚠️ **仅在从无成功记录时**才用。真机实测该比例偏 112px（算得 y≈2068，实际快门 y≈2180），基本必失 |

> ⚠️ **老代码的坑**：识别失败直接退 `50%,91%`，等于「兜底兜了个空」。
> 回退要退到**真的到过的地方**，不是退到猜。
>
> ⚠️ 取屏幕尺寸必须用**物理**尺寸（`Display.getRealSize`），不能用 `displayMetrics.heightPixels` ——
> 后者是**应用窗口**高（实测 2273），截图是**物理屏**（2400），差的 127px 一乘就把点甩到快门边缘。

#### 调试

```bash
# 把界面手动开到相机页，然后跑这个。⚠️ 它只报告结果，【不会】自动点击任何东西
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez testShutter true

# 忘掉历史快门坐标 —— 换设备/换分辨率后，或怀疑兜底坐标把流程带偏了
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez clearShutterPoint true
```

### 真机侦察结论（重要）

| 项 | 结论 |
|---|---|
| 🔴 **底部手势区（最大的坑）** | 目标 App 底部 Tab 图标 `bounds=[72,2253][144,2273]`，**只有 20px 高**、紧贴内容区底边。而手势导航下屏幕底部约 48dp(≈144px) 是**系统手势区**，落进去的 `dispatchGesture` 会被系统当作导航手势吞掉。<br>而 `adb input tap` 走 shell 注入通道、优先级高于手势识别，**同一坐标用 adb 手点却能成功** —— 排查时极易误判成"坐标算错了"。<br>→ 现已自动把落点上移到安全线以内重试（`StepEngine.tap`）。 |
| 🔴 **dispatchGesture 假阳性** | `dispatchGesture()` 的返回值**只表示"手势已派发"**，不代表点击真的生效。早期版本据此报 `✓ 已点击`，于是出现「日志全绿、界面纹丝未动」。<br>→ 现改用 `GestureResultCallback` 的 `onCompleted / onCancelled` 判定真实结果。 |
| **入口判据** | 扫码与主页两个入口**包名完全相同**，比对包名分不清"进了哪一页"。现改用 **Activity 类名**（`CategoryChatActivity` ⇒ 已直达通知中心），类名由 `TYPE_WINDOW_STATE_CHANGED` 事件缓存。 |
| **adb 注入** | ⚠️ **修正**：并非被封。`input tap/swipe` 报 `SecurityException: INJECT_EVENTS permission` 是**因为当时手机处于锁屏态**——**锁屏时禁止注入触摸，解锁后 `input tap` 完全正常**（本次实测用它驱动了整条链路做交叉验证）。 |
| 设备 | 华为 **JEF-AN00**，物理分辨率 1080×2400，density 480，手势导航（`navigation_mode=100`） |
| **锁屏** | 🔴 锁屏时无障碍**完全点不动**，`input swipe` 也会被拒。定时签到必须解决锁屏（建议设为「仅滑动解锁」）。 |
| 已装应用 | `com.xiaoyao.autocheckin`（本 App）；`cn.edu.fafu.iportal`（目标） |

### 🔴 定时签到能不能跑起来的关键：后台启动 Activity 限制（BAL）

**这是开发期最容易漏掉、到点必然踩到的坑。**

Android 12+ 禁止**后台应用**调用 `startActivity()`。定时签到由 `AlarmReceiver` 在后台触发，
此时本 App 不在前台，于是跳转被系统直接丢弃：

```
W ActivityTaskManager: activity start fail: Background activity start
  callingPackage: com.xiaoyao.autocheckin
  isCallingUidForeground: false
  callingUidHasAnyVisibleWindow: false      ← 真正的判据
  allowBackgroundActivityStart: false
E ActivityTaskManager: activity start fail: Abort background activity starts from 10190
```

**症状极具迷惑性（2026-09-25 实测）：**

| 观察到的现象 | 实际含义 |
|---|---|
| 日志有 `已发起跳转：hwwelink://...` | 只是"已调用 startActivity"，**不代表系统接受了** |
| `未进入目标 App（当前 com.huawei.android.launcher）` | 跳转被丢弃，前台还停在桌面 |
| 后续所有步骤 `✗ 未找到控件` | 前台压根不是目标 App，纯空转 |
| 白耗 4 轮 × 30 秒 | 每轮都在重复上面这一套 |

> ⚠️ **为什么手动测试测不出来 —— 两个独立的假阳性来源**
>
> ① **adb 触发**：`am start` 由 `com.android.shell`（uid 2000）发起，它自带
> `START_ACTIVITIES_FROM_BACKGROUND` 特权，压根不受这条限制约束，日志里明确写着：
> `Background activity start allowed: START_ACTIVITIES_FROM_BACKGROUND permission granted for callingUid = 2000 callingPackage = com.android.shell`
>
> ② **点按钮 / `backFirst` 触发**：App 刚离开前台，还处在 BAL 的**宽限期**内，
> 后台跳转照样放行 —— 于是给出"一切正常"的错觉。

#### 解法：不是"要权限"，而是"要一个可见窗口"

**实测纠正（2026-09-25）**：仅持有 `SYSTEM_ALERT_WINDOW` 权限**并不足够**。
本机 `appops SYSTEM_ALERT_WINDOW = allow` 早已生效，系统仍然判 `allowBackgroundActivityStart=false`。

真正管用的是 AOSP 的放行分支 **`BAL_ALLOW_VISIBLE_WINDOW`**：
「调用方 uid 存在任何可见窗口 → 直接放行」。悬浮窗正属于调用方 uid 下的窗口，
所以只要**真的挂上一个悬浮窗**，`callingUidHasAnyVisibleWindow` 就会从 `false` 翻成 `true`。

实现见 **`OverlayProbe.kt`**：服务连接时挂一个 **1×1 像素、半透明、不可触摸、不可获焦**的
`TYPE_APPLICATION_OVERLAY` 窗口。用户完全看不见，却换来了后台跳转权限。

⚠️ 两个实现细节，动代码时别踩：

- `alpha` 必须**非 0**：若为 0，`WindowState.isFullyTransparent()` 成立，系统可能不把它计入
  "可见窗口"，豁免就白做了。
- 悬浮窗在**屏幕点亮前不算可见**，所以 `wakeScreen()` 必须先点亮屏幕、等到 `isInteractive`
  为真、再留 800ms 余量，然后才发起跳转。早先版本「已唤醒屏幕」到「发起跳转」只隔 1 秒，
  跳转照样被拦。

自查三处：

- App 界面状态栏第三行：`后台跳转豁免：✅ 探针生效`
- `adb shell appops get com.xiaoyao.autocheckin SYSTEM_ALERT_WINDOW` → 要看到 `allow`
- 日志里出现 `后台跳转豁免探针已挂上（1×1 隐形悬浮窗）`

**代码里另加三道防护**：
① `launchEntry` 取不到前台窗口时，直接打出「探针是否生效」的诊断；
② `runOnce` 发现前台窗口为 null 时立刻放弃本轮，不再空转；
③ 探针挂载失败（没给权限）时打日志 + 界面标红。

#### 怎么验证定时场景（别再用 am start 骗自己）

新增 `onceIn` 参数，走 **AlarmManager → AlarmReceiver → 无障碍服务** 的完整真实路径：

```bash
ADB="D:/Development/android-sdk/platform-tools/adb.exe"

# 60 秒后模拟一次定时（App 会自动退到后台）
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ei onceIn 60

# 立刻熄屏，制造真实现场
"$ADB" shell input keyevent KEYCODE_SLEEP
```

> ⚠️ 参数必须是 **`--ei`**（int extra）。写成 `--ez`（boolean）时 `getIntExtra` 只会拿到 0，
> 函数静默返回 —— 现象是「命令执行了、屏幕也亮了，却什么也没发生」，极易误判成代码没生效。

这才是能测出问题的姿势。`backFirst` 只能算近似，别拿它下结论。

### 签到页卡在「加载中…」怎么办（已内置自动处理）

**现象**：点签到卡片后进入 H5（`BrowserActivity`），页面长时间停在「加载中...」不动。
**性质**：**偶发，非必然** —— 实测同一个入口连着点，多数情况 2 秒内就加载好了（日志 `✓ 已出现：签到要求`）。

---

#### 🔴 先说一条被实测推翻的方案：**不要用「右上角 … → 刷新」救场**

2026-09-25 真机实测（华为 JEF-AN00）：

| 动作 | 结果 |
|---|---|
| 在**正常**的签到页点「…」→「刷新」 | ❌ 标题栏从「签到」变成 **`health-wx`**，WebView 只剩一个「加载中...」（`[465,1366][615,1426]`），**18 秒仍不自愈** |
| 在**卡死**的页面上按一次 **BACK** | ✅ 立刻回到【通知中心】——卡死的 WebView **不会吞掉 BACK**，一次就够 |

也就是说 —— **「刷新」这个动作本身就是制造「加载中」的元凶**，等于亲手把能用的页面推进死状态。

> 顺带记一个更早踩到的坑：`browser_title` 下三个 ImageView 的 `resource-id` **全是 `icon`**、
> class 全是 `ImageView`、全都 `clickable`，用「取第一个可点击 ImageView」会命中
> **返回箭头 `[30,145][126,241]`** 而不是「…」`[828,145][924,241]` —— 点下去直接退出签到页。
> 真要靠坐标定位，只能按横向位置判：返回箭头贴在左边缘（x=30），
> 「…」与「✕」都在右半边，右侧图标里更靠左的那个才是「…」。

#### 现采用的做法（v2.12 起）：两组交替，每组 = BACK ×3 → 冷启动一次

| 层次 | 动作 | 说明 |
|---|---|---|
| 页面级（便宜） | `wait` 超时 → **BACK 退回列表 → 重新点开卡片**，每组试满 **3 次**（各等 6 秒） | 治页面懒加载慢，不退出 App，多数一两次就成 |
| 进程级（贵） | 本组 BACK 试满仍无效 → **冷启动一次**：回桌面 → `killBackgroundProcesses(目标包)` → 重走 deeplink | 治 WebView **实例级**死锁 —— 进程不死就换不掉坏实例，BACK 跑多少轮都是空转 |
| 组的重复 | 上面这个「组」重复 **2 遍** | 即 BACK 最多 6 次、冷启动最多 2 次 |
| 最外层 | 本页两组全失败 → 整轮重来（重来前也先冷启动） | `ConfigStore.retry()` 默认 3，即最多 4 轮 |

> ⚠️ **顺序不许被任何检测短路**：`health-wx` 死状态只在日志里提示、**不改变流程** ——
> 早期版本检测到它就直奔冷启动，等于把「先试满 BACK」整段跳过（注释写着"不中断"、
> 代码却在中断，属于典型的注释与实现不符）。

> 下面这段是**早期版本**的判据（8 秒判死 + 单层 BACK 重进），留作踩坑记录：

```
# ⑤ 确认签到页真的加载出来了。这里偶尔会卡在「加载中...」不动：
#    超过 8 秒仍挂着遮罩就判定卡死，自动 BACK 退回列表、重新点开卡片。
#    ⚠️ 别用「右上角 … → 刷新」救场（实测会把好页面推进死状态，18s 不自愈）。
#    注意用「签到要求」这类【签到页独有】的文案：
#    写「签到」会命中通知中心卡片里的「签到提醒」，导致误判成功。
wait|签到要求|1|0|30000
```

> ⚠️ **`wait` 的匹配词必须选「签到页独有」的文案**。写成「签到」会被通知中心卡片里的
> 「签到提醒」「晚归签到」命中 —— 页面根本没加载也会报"已出现"，恢复机制直接失效。
>
> ℹ️ **8 秒宽限期是必要的**：刚点进卡片时页面本来就会短暂显示「加载中...」，不能一见就判死。
> 判据是 `StepEngine.hasLoadingOverlay()`（扫描节点树里含「加载中」的文本）。

### 最新验证记录（2026-09-25 17:21，模拟定时触发的后台场景）

用 `--ez runNow true --ez backFirst true` 让 App **先退到后台**再执行 —— 复现定时任务的真实环境：

```
已发起跳转：hwwelink://welink.huawei.com/qr?qrcode=lB57ytc6C…      ← 此刻 App 在后台
主入口 已进入 CategoryChatActivity → 已直达通知中心 ✅              ← 后台启动成功
→ [3/5] scroll=service_chat_list     ✓ 已滚动到底部
→ [4/5] id=ll_app_card               ✓ 已点击
→ [5/5] wait=签到要求
   已等 8s 仍未就绪，页面停在「加载中...」，判定卡死                 ← 提前判死生效
   本页重进：BACK 退回后重新点开卡片                                ← 第一层恢复触发
   ✓ 已出现：签到要求（2 秒）                                    ← 恢复成功
🎉 签到流程执行完毕
```

这一次是**真实卡死 + 自动恢复**的完整闭环：页面确实卡在「加载中」，
程序 8 秒判死 → BACK 退回 → 重新点开卡片 → 2 秒后正常加载。

`activity start fail` 计数 = **0**，后台启动限制已解决。

### 调试用：不经 UI 直接触发执行

```bash
ADB="D:/Development/android-sdk/platform-tools/adb.exe"

# ① 跑完整签到流程
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez runNow true

# ② 只演练「卡死恢复」这一条路径（BACK 退回列表 → 重新点开卡片）
#    卡在「加载中...」是概率事件，用这个可以随时演练恢复动作
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez testRecover true

# ③ 重置步骤表为内置默认值
#    ⚠️ 升级 APK 后【必须】跑一次：步骤表存在 SP 里，不清就永远读旧的（见「提交守卫」）
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez resetSteps true

# ④ 提交守卫：验证它【而不消耗当天机会】
#    先设成「今天已提交」，再跑流程 —— 若日志出现「⛔ 已提交过，跳过」即证明守卫生效，
#    且全程【不会真的点提交】。这比"真跑一遍看会不会提交"安全得多。
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez markSubmitted true
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez runNow true

# ⑤ ⚠️ 复位提交标记 —— 跑完 ④ 【务必】执行，否则当天真签到会被跳过！
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez clearSubmitDate true

# ⑥ 结果推送：写入 token 并立刻测一条（装机后不必在手机小键盘上戳密钥）
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --es pushToken "你的token"
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez testPush true

# ⑦ 快门识别单测 —— 需先把界面手动开到相机页。
#    ⚠️ 只报告识别结果，【不会】自动点击（老版本会在失败时盲点比例坐标，已去掉）
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez testShutter true

# ⑧ 忘掉历史快门坐标（换设备/换分辨率，或怀疑兜底坐标把流程带偏）
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez clearShutterPoint true

# 实时读日志。App 同时写 logcat，比截图读界面快得多。
# ⚠️ 长流程前先放大 logcat 环形缓冲，否则开头会被冲掉（本次就踩过，开头 10 行丢了）
"$ADB" logcat -G 8M
"$ADB" logcat -c
"$ADB" shell am start -n com.xiaoyao.autocheckin/.MainActivity --ez runNow true
sleep 40
"$ADB" logcat -d | grep "AutoCheckin:" | sed 's/.*AutoCheckin: //'
```

### 用 adb 侦察界面（比截图快，能拿到精确坐标）

```bash
# ⚠️ Windows 的 Git Bash 会把 /sdcard/... 转成 D:/Development/Git/sdcard/...
#    必须关掉路径转换，否则 dump 出来的文件根本不存在（本次踩过）
export MSYS_NO_PATHCONV=1

"$ADB" shell uiautomator dump /sdcard/ui.xml
"$ADB" shell cat /sdcard/ui.xml > ui.xml
```

> ⚠️ **别用 `am force-stop` 后再触发** —— force-stop 会把**无障碍服务一起踢掉**，
> 服务重连需要数秒，这期间触发的执行会因 `instance == null` 静默 return
> （踩过的坑：固定等 800ms 时服务还没连上，点了没反应）。
> `maybeRunNow()` 已改为轮询等服务就绪（最多 20 秒），但仍建议少用 force-stop。
> 另外：**`pm clear` 同样会清掉无障碍开关**，清完后必须重新开启。

### 抓控件树的正确姿势（Windows / Git Bash）

```bash
export MSYS_NO_PATHCONV=1          # ← 关键！否则 /sdcard/... 会被改写成 Windows 路径
ADB="D:/Development/android-sdk/platform-tools/adb.exe"

# 用 /data/local/tmp（无需存储权限，比 /sdcard 稳）
"$ADB" shell uiautomator dump /data/local/tmp/ui.xml
"$ADB" pull /data/local/tmp/ui.xml "D:/VibeCoding/自动签到/refs/ui-XX.xml"
```

> 首次 dump 常出现 `Got null root node from accessibility - Retrying...`，**这是正常警告**，会自动重试成功。

---

## 五、步骤表怎么填（关键）

### 怎么拿到控件信息

自研方案没有可视化布局分析工具，用 **adb 抓控件树**：

```bash
# 1. 在手机上手动走到签到界面，保持页面不动
# 2. 电脑执行：
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml
```

打开 `ui.xml`，找到那个按钮的节点，看它的属性：

```xml
<node index="2" text="提交"
      resource-id="com.xxx.checkin:id/btn_submit"
      content-desc="提交按钮"
      class="android.widget.Button"
      clickable="true" bounds="[540,1800][900,1900]" />
```

| 属性 | 对应步骤 kind |
|---|---|
| `text="提交"` | `text` |
| `resource-id=".../btn_submit"` | `id`，value 可只写 `btn_submit` |
| `content-desc="提交按钮"` | `desc` |
| `bounds` 中心点 `(720,1850)` | `xy`，value 写 `720,1850` |

### 格式

```
kind|value|contains|optional|waitMs|last|submit
```

- `kind`：`text` / `id` / `desc` / `cls` / `xy`
- `contains`：`1` = 模糊匹配（文字里含该串即可）
- `optional`：`1` = 这一步找不到也继续（适合弹窗、引导页）
- `waitMs`：这一步最多等多少毫秒，默认 8000
- `last`：`1` = 匹配到多个时取最后一个（消息列表「旧在上、新在下」时必须用）
- `submit`：`1` = **提交步**（全流程只能有一条）。执行前查当日标记、执行后立刻落盘，
  是「一天只能签一次」的防线 —— 见上文「提交守卫」。默认可省略。

**示例：**

```
# 以 # 开头是注释，会被忽略
id|btn_late_checkin
text|确认|1|1
id|btn_submit
```

### 定位优先级建议

`id` > `text`（精确） > `desc` > `xy`。
**`xy` 是最后的兜底**——换机型、改分辨率、系统字体大小一变就失效。

---

## 六、界面设计与实现说明

### UI：Material 3，但一个依赖都没引

界面是**自绘的 Material 3** —— 配色规范照抄官方，实现全靠 framework 原生能力。
既不牺牲观感，也不付出 Material Components AAR 的代价。

**为什么不用现成的 UI 脚手架（2026-09-25 调研）**：

GitHub 上那些「Android UI 脚手架」库基本都已经停维护 ——
`navasmdc/MaterialDesignLibrary`（8.9k stars）最后提交停在 2016 年、`rey5137/material`（6.0k）停在 2015 年，
都是为 Android 2.2~4.4 时代做的兼容层；仍然活跃的则清一色是 Jetpack Compose 方案，
对纯 View 工程等于推倒重来。唯一能直接用的现成脚手架是官方 `material-components-android`，
但它会拖进 appcompat / recyclerview / coordinatorlayout 一整串依赖，APK 从 60KB 涨到约 1.5MB。

**最终采用「抄 token，不抄库」**：从 `material-foundation/material-color-utilities`
的 HCT 算法结果里取官方色值，手写进 `colors.xml`。

**资源文件布局**：

| 文件 | 作用 |
|---|---|
| `values/colors.xml` | Material 3 配色 token（浅色），按 Primary / Secondary / Tertiary / Error / Surface / Outline 分组 |
| `values-night/colors.xml` | 深色覆盖。**同名同结构**，系统自动切换，布局侧零感知 |
| `values/themes.xml` | 浅色主题：把 M3 token 灌进 framework 的着色属性 |
| `values-night/themes.xml` | 深色主题：父主题换深色版 + 翻转系统栏图标亮度 |
| `values/styles.xml` | 可复用样式（卡片 / 输入框 / 按钮 / 文字）。改视觉只动这一处 |
| `drawable/bg_*.xml` | 卡片、输入框（含聚焦态）、三种按钮底、日志底、状态点 |

**几个关键做法**：

- **层次靠明度差，不靠阴影** —— M3 的做法。页面底用 `surface`、卡片 `surface_container`、
  输入框 `surface_container_high`，不加分隔线也能分清结构。
- **深色主题里层次方向是反的** —— 浅色下 surface 越高越暗，深色下越高越亮，
  所以两套 `colors.xml` 不能简单地取反色，必须各自取值。
- **涟漪用 framework 原生 `<ripple>`**（API 21+）即可，不必引 Material 库。
- **输入框聚焦态换 2dp 主色描边** —— 用 `<selector>` 实现，给一个明确的「正在编辑这里」的反馈。
- **状态行一个 drawable 覆盖三态** —— 圆点的颜色由代码按状态 tint（绿 / 红 / 灰），不必建三份资源。
- **开关用原生 `android.widget.Switch`** —— Android 12 上它本身就是 Material You 外观，不必自绘。

### 应用图标：矢量 + 自适应图标

**图形**：开口圆环 + 对勾。圆环隐喻「每一天的打卡周期」，开口在右上表示「待完成 → 已完成」，
对勾自环内穿出缺口，带一点达成后的动势。

**实现走的是自适应图标（Adaptive Icon）纯矢量路线**，一个 PNG 都没有：

| 文件 | 作用 |
|---|---|
| `drawable/ic_launcher_foreground.xml` | 前景矢量图（108×108 viewport） |
| `values/ic_launcher.xml` | 背景色 `#415F91`（M3 blue seed 的 primary） |
| `mipmap-anydpi-v26/ic_launcher.xml` | 自适应图标装配（方形） |
| `mipmap-anydpi-v26/ic_launcher_round.xml` | 自适应图标装配（圆形） |

**为什么能这么省**：`minSdk = 26`，恰好是自适应图标的下限，
所以 `mipmap-anydpi-v26` 一个目录就全量覆盖，**不需要为每个 dpi 生成 5 套 PNG**。
代价是 **+1.6 KB**（60.5 KB → 62.1 KB），比塞 PNG 的常规做法小两个数量级。

**画自适应图标必须知道的几何约束**（踩过就懂）：

- 画布是 **108×108dp**，但系统**只显示中间 72×72**，四周各 18dp 会被裁掉。
  所以所有图形必须收在**以 (54,54) 为圆心、半径 36** 的范围内。
  本图最远点 27.25（环外缘），留 8.75 余量 —— 因为 EMUI / OneUI / Pixel 的遮罩形状
  各不相同，贴边画在圆形遮罩下会被削掉。
- **背景色必须用独立常量**，不能直接引用 `@color/md_primary`：
  后者在 `values-night` 里会翻成浅色，图标跟着翻就会变成白底白图。
- 前景 drawable 由系统**加遮罩裁切**，不要自己画圆形背景，否则在方形遮罩下会露出底。

**几何数据**：
- 圆环 `R=24`，stroke 6.5，开口为钟表角 **22.5°~67.5°（即 45°）**，另一侧扫过 315° → `large-arc-flag=1`
- 对勾 `(41.5,56) → (49,64.5) → (70.32,37.1)`，stroke 6.5（与环同宽），
  终点方向 44° 正对缺口中心；与两个环端点各距 9.69 / 14.39，
  减去两 stroke 半径和 6.5，**净间隙 3.19**，不会粘连

> 🔴 **两轮真机迭代换来的两条经验，改这枚图标前务必先读**：
>
> 1. **开口角度是命门**。初版给了 **70°**，真机上环变成个「C」形、右上大片空白，
>    整枚图标重心歪向左下，看着像个刷新按钮。实测 **45° 是甜点** ——
>    超过 50° 就会「散」，小于 30° 又看不出缺口、退化成普通圆环。
> 2. **勾不能太长太粗**。第二版是 `R=26 + 勾 stroke 7`，勾伸出环外的那一段
>    会和缺口边缘「连成一片」，看起来像旋转箭头，很乱。
>    **环收到 `R=24`、勾同步收小、两者 stroke 同宽（6.5）** 才干净。
>
> 调参时不必反复构建 —— 用 PIL 按同一组参数画超级采样预览图，
> 秒级出结果，比 `gradle assembleRelease`（约 50 秒）快得多，
> 定稿后再构建验证即可。

### 体积

- release 启用 R8（`isMinifyEnabled` + `isShrinkResources`），**实测产物 60.5 KB**（61,997 字节）
  - UI 改造前 53.2 KB → 改造后 60.5 KB，**净增 8.8 KB**，且全是资源，dex 零增长
  - 作为对比：走官方 Material Components 方案会涨到约 1.5 MB（约 25 倍）
- 签名：`apksigner verify` 通过（APK Signature Scheme **v2**），使用 debug keystore，可直接 `adb install`
- 零第三方依赖，不引入 androidx，全部用 Android framework 原生 API

### 核心文件

| 文件 | 职责 |
|---|---|
| `CheckinAccessibilityService.kt` | 主流程：唤醒 → 跳转 → 逐步点击，带重试与卡死恢复 |
| `StepEngine.kt` | 控件查找与点击（含坐标手势兜底） |
| `ShutterFinder.kt` | 截图识别白色快门圆盘（连通域 blob 判据 + 连拍双帧 + 历史坐标兜底） |
| `Pusher.kt` | 结果推送到微信（Cloudflare Worker，HttpURLConnection 手写，零依赖） |
| `OverlayProbe.kt` | 1×1 隐形悬浮窗，绕过 Android 12 的后台启动限制（见第四章 BAL 一节） |
| `Scheduler.kt` / `AlarmReceiver.kt` / `BootReceiver.kt` | 每日定时、开机自恢复 |
| `MainActivity.kt` | 配置界面 + 日志查看 + 状态区着色 |
| `ConfigStore.kt` / `Step.kt` | 配置持久化与步骤解析 |

---

## 七、已知限制

1. **锁屏跑不了**：无障碍服务在锁屏下无法操作界面。脚本会自动唤醒并尝试上滑解锁，**但数字/图案密码锁屏需要人工**。建议手机设为「仅滑动解锁」，或定时前保持屏幕常亮。
2. **国产 ROM 后台限制**：需在设置中给本 App 开「自启动」「允许后台运行」，并在最近任务里加锁。
3. **App 检测无障碍**：少数考勤类 App 会检测无障碍服务并拒绝运行，遇到只能退回抓包方案。
4. **不支持 Android 7.0 以下**（minSdk 26）。

---

## 八、使用边界

仅用于**本人账号**的日常签到自动化。不要用于给他人代签，也不要试图绕过人脸识别、定位校验等「人必须到场」的强校验。
