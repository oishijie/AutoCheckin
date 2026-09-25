/**
 * 晚归自动签到 · AutoJs6 脚本
 * ------------------------------------------------------------
 * 运行环境：AutoJs6 (org.autojs.autojs6) v6.6+ / Android 7.0+
 * 用法：填好下面【配置区】→ 运行；或在 AutoJs6「定时任务」里设每天固定时间触发
 * ------------------------------------------------------------
 */

// ====================== 配置区（只改这里） ======================

const CONFIG = {

    // ① 签到入口：把学校/App 给你的 intent 链接【整条原样】粘进来
    //    支持：自定义 scheme（xxxapp://...）、标准 intent URI（intent://...#Intent;...end）、https 链接
    //    留空 = 只启动 App，靠 steps 表自己点进去
    intentUrl: "",

    // ② 签到 App 包名（可选，用于确认界面已切过去，如 com.xxx.checkin）
    targetPackage: "",

    // ③ 点击步骤表：按数组顺序依次执行
    //    kind: "text" | "id" | "desc" | "cls" | "xy"
    //    wait: 这一步最多等多少毫秒（可省，默认用下面的 stepWait）
    //    optional: true = 找不到也跳过（弹窗、引导页这类非必经步骤）
    //    contains: true = 文字模糊匹配（kind 为 text 时生效）
    steps: [
        // 示例（替换成真实步骤）：
        // { name: "点击晚归签到", kind: "text", value: "晚归签到" },
        // { name: "选择状态",     kind: "text", value: "在校", optional: true },
        // { name: "点击提交",     kind: "id",   value: "btn_submit" },
    ],

    // ④ 运行参数
    launchWait: 5000,          // 打开 intent 后等界面加载的时间(ms)
    stepWait: 8000,            // 每步默认等待控件出现的上限(ms)
    stepGap: 800,              // 两次点击之间的停顿(ms)
    retries: 2,                // 整体失败后的重试次数
    wakeScreen: true,          // 执行前尝试唤醒并解锁屏幕
    screenshotOnFail: true,    // 失败时截屏存证（便于排查）
    logDir: "/sdcard/自动签到/logs/",
    shotDir: "/sdcard/自动签到/shots/",
};

// ====================== 工具函数 ======================

const LOG_FILE = CONFIG.logDir + "run.log";

function log(msg) {
    const line = "[" + formatTime(new Date()) + "] " + msg;
    console.log(line);
    try {
        files.ensureDir(CONFIG.logDir);
        files.append(LOG_FILE, line + "\n");
    } catch (e) { /* 日志写失败不影响主流程 */ }
}

function formatTime(d) {
    function p(n) { return n < 10 ? "0" + n : "" + n; }
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()) + " " +
        p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
}

function ensureDir(path) {
    try { files.ensureDir(path); } catch (e) { }
}

/** 是否处于锁屏状态 */
function isLocked() {
    try {
        const km = context.getSystemService(android.content.Context.KEYGUARD_SERVICE);
        return km.isKeyguardLocked();
    } catch (e) {
        return false;
    }
}

/** 唤醒屏幕 + 无密码时上滑解锁 */
function wakeScreen() {
    try {
        if (!device.isScreenOn()) {
            log("屏幕未亮，尝试唤醒");
            device.wakeUp();
            sleep(1200);
        }
        if (isLocked()) {
            log("检测到锁屏，尝试上滑解锁（有密码则需人工）");
            swipe(device.width / 2, device.height * 0.85, device.width / 2, device.height * 0.15, 300);
            sleep(1200);
        }
    } catch (e) {
        log("唤醒屏幕异常: " + e);
    }
}

/** 启动签到入口 */
function launchCheckin() {
    const url = (CONFIG.intentUrl || "").trim();
    if (!url) {
        log("未配置 intentUrl，跳过启动（仅执行 steps 表）");
        return;
    }
    try {
        if (url.toLowerCase().indexOf("intent:") === 0) {
            // 标准 intent URI
            const intent = android.content.Intent.parseUri(url, null);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
        } else {
            // 自定义 scheme / http 链接
            app.startActivity({
                action: "android.intent.action.VIEW",
                data: url,
                flags: ["FLAG_ACTIVITY_NEW_TASK"],
            });
        }
        log("已发起跳转: " + url.substring(0, 60) + (url.length > 60 ? "..." : ""));
    } catch (e) {
        throw new Error("启动 intent 失败: " + e);
    }
}

/** 按步骤定义查找控件，返回 UiObject 或 null */
function findNode(step) {
    const timeout = step.wait || CONFIG.stepWait;
    let sel;

    switch (step.kind) {
        case "id":   sel = id(step.value); break;
        case "desc": sel = desc(step.value); break;
        case "cls":  sel = className(step.value); break;
        case "text":
        default:
            sel = step.contains ? textContains(step.value) : text(step.value);
            break;
    }

    // 优先找可点击的，找不到再放宽
    let node = null;
    try { node = sel.clickable(true).findOne(timeout); } catch (e) { node = null; }
    if (!node) {
        try { node = sel.findOne(Math.min(timeout, 3000)); } catch (e) { node = null; }
    }
    return node;
}

/** 点击一个控件节点，失败则退回坐标点击 */
function clickNode(node) {
    try {
        if (node.clickable() && node.click()) return true;
    } catch (e) { }
    try {
        const b = node.bounds();
        if (b && b.centerX() > 0 && b.centerY() > 0) {
            click(b.centerX(), b.centerY());
            return true;
        }
    } catch (e) { }
    return false;
}

/** 执行单个步骤 */
function runStep(step) {
    const name = step.name || (step.kind + "=" + step.value);
    log("→ " + name);

    let ok = false;
    try {
        if (step.kind === "xy") {
            const p = String(step.value).split(",");
            click(parseInt(p[0].trim()), parseInt(p[1].trim()));
            ok = true;
        } else {
            const node = findNode(step);
            if (!node) {
                log("   ✗ 未找到控件 (" + step.kind + "=" + step.value + ")");
                return false;
            }
            ok = clickNode(node);
        }
    } catch (e) {
        log("   ✗ 步骤异常: " + e);
        return false;
    }

    log(ok ? "   ✓ 已点击" : "   ✗ 点击未生效");
    return ok;
}

/** 失败截图 */
function captureShot(tag) {
    if (!CONFIG.screenshotOnFail) return;
    try {
        ensureDir(CONFIG.shotDir);
        const img = images.captureScreen();
        if (!img) { log("截图失败：无权限（可在 AutoJs6 里先申请一次截图权限）"); return; }
        const path = CONFIG.shotDir + tag + "_" + Date.now() + ".png";
        images.save(img, path);
        log("已截图: " + path);
    } catch (e) {
        log("截图异常: " + e);
    }
}

/** 单次完整流程 */
function runOnce() {
    if (CONFIG.wakeScreen) wakeScreen();

    launchCheckin();
    sleep(CONFIG.launchWait);

    if (CONFIG.targetPackage) {
        try {
            if (!waitForPackage(CONFIG.targetPackage, 10000)) {
                log("警告：未等到目标 App 界面（" + CONFIG.targetPackage + "），继续尝试");
            } else {
                log("已进入目标 App: " + CONFIG.targetPackage);
            }
        } catch (e) { }
    }

    if (!CONFIG.steps.length) {
        throw new Error("steps 步骤表为空，请先在 CONFIG 里填写签到步骤");
    }

    for (let i = 0; i < CONFIG.steps.length; i++) {
        const step = CONFIG.steps[i];
        const ok = runStep(step);
        if (!ok && !step.optional) {
            throw new Error("步骤失败，中止: " + (step.name || step.value));
        }
        sleep(CONFIG.stepGap);
    }

    log("✅ 全部步骤执行完毕");
}

/** 主流程（带整体重试） */
function main() {
    log("========== 晚归签到开始 ==========");
    for (let attempt = 1; attempt <= CONFIG.retries + 1; attempt++) {
        log("----- 第 " + attempt + " / " + (CONFIG.retries + 1) + " 次尝试 -----");
        try {
            runOnce();
            log("🎉 签到流程成功结束");
            if (CONFIG.screenshotOnFail) captureShot("success");
            return true;
        } catch (e) {
            log("❌ 失败: " + e);
            captureShot("fail" + attempt);
            if (attempt <= CONFIG.retries) {
                log("3 秒后重试...");
                sleep(3000);
            }
        }
    }
    log("❌❌ 全部尝试失败，请检查步骤表 / 网络 / 无障碍权限");
    return false;
}

// ====================== 入口 ======================

auto.waitFor();   // 等待无障碍服务就绪
main();
