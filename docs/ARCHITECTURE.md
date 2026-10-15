# 长辈守护（ElderGuard）架构设计

> 目标：治理老年人手机上的「自动弹窗广告、假页面、快应用自动拉起、诱导跳转」，
> 并把「谁干的」查清楚、让家人能处理掉。
> 分发：GitHub 开源，侧载 APK。原则：**本地运行、不联网、可审计**。

---

## 1. 产品使用逻辑

### 1.1 两类角色，同一台手机

| 角色 | 在 App 里做什么 | 不做什么 |
|---|---|---|
| **长辈** | 只看一个大字状态页：「守护中 ✓」/「守护出问题了，请联系家人」；偶尔看到「刚帮您挡掉一个广告」的提示 | 不需要做任何判断，不会被要求点「确定/授权」 |
| **家人** | 安装、开权限、看记录、处置元凶、维护模式 | — |

家人的所有入口都在**家人密码（PIN）**之后。长辈不会误删 App、误关守护、误把流氓 App 加白名单。

### 1.2 生命周期

```
安装 ──► 首次设置向导（家人陪同完成）
          ① 说明：做什么、不联网、开源
          ② 设置家人密码
          ③ 开权限清单（逐项跳转设置页，回来自动刷新状态）
               必需：无障碍服务
               推荐：通知使用权、发送通知、忽略电池优化、厂商自启动
          ④ 选择守护方式：仅记录 / 自动挡掉（默认）
          ⑤ 首次体检：风险应用（可查看原因、卸载、标记）+ 需处理的系统项；可跳过
                │
                ▼
       ┌──── 日常守护（常驻，长辈无感）────┐
       │  检测 ─► 归因 ─► 自动处理 ─► 记录  │
       │        健康巡检（服务是否被杀）     │
       └──────────────────────────────────┘
                │ 家人回来 / 定期
                ▼
       家人入口（PIN）
          ├─ 元凶榜：哪个 App 弹了几次、证据
          │    └─ 处置：卸载 / 关悬浮窗 / 关通知 / 信任它
          │         基础模式：跳转到系统设置页，由家人点
          │         增强模式：一键直接执行
          ├─ 体检：重新扫描
          ├─ 维护模式（Shizuku，临时开启，用完即关）
          ├─ 分享报告（生成图片，发微信）
          └─ 设置：守护方式、信任名单、修改密码
```

### 1.3 日常守护的判定逻辑（核心）

**一次「骚扰事件」的定义**：前台界面切换到了某个 App，但**用户在这之前没有点击任何东西**。

| 事件类型 | 触发条件 | 元凶归属 |
|---|---|---|
| `UNSOLICITED_LAUNCH` 应用外弹窗 | 前台切到 App X，前 N 毫秒内无用户点击，X 不在受保护名单 | **X 自己**（弹出的广告页属于它） |
| `UNLOCK_POPUP` 解锁弹窗 | 解锁后 N 秒内，前台切到非桌面、非解锁前的 App，且无点击 | **X 自己** |
| `QUICK_APP_LAUNCH` 快应用拉起 | 前台切到快应用引擎包名，且无点击 | **切换前的前台 App**（谁把它拉起来的） |
| `STORE_REDIRECT` 诱导跳商店 | 前台切到应用商店，且无点击 | **切换前的前台 App** |
| `SUSPICIOUS_NOTIFICATION` 可疑通知 | 非来电/闹钟类却带全屏意图；或标题命中「病毒/中奖/内存不足/清理」等关键词 | 发通知的 App |

「用户点击」的信号来源：无障碍事件 `TYPE_VIEW_CLICKED`、`TYPE_VIEW_LONG_CLICKED`、
`TYPE_VIEW_SELECTED`，以及桌面图标点击（桌面里的点击同样是 `TYPE_VIEW_CLICKED`）。

**受保护名单（永远不判为骚扰、不自动处理）**：
本 App、当前桌面、系统界面（systemui）、输入法、拨号/来电、闹钟、设置、
家人加入的信任名单。

### 1.4 自动处理策略

| 守护方式 | 行为 |
|---|---|
| 仅记录 | 只记事件，不操作手机 |
| 自动挡掉（默认） | 见下表 |

自动挡掉时，**按确定性分级**，宁可漏挡，不可误挡（误挡会让长辈觉得「手机坏了」）：

| 事件 | 未标记的 App | 家人标记为「流氓」的 App |
|---|---|---|
| 快应用拉起、跳商店 | 自动「返回」（确定性高） | 自动「返回」 |
| 应用外弹窗、解锁弹窗 | **只记录**，进元凶榜等家人确认 | 自动「返回」 |
| 可疑通知 | **只记录** | 自动撤掉通知 |

即：**记录 → 家人在元凶榜确认 → 以后自动挡**。家人标记「正常」的 App 不再判定。

防误伤约束：
- 同一元凶 10 秒内最多自动处理 1 次，防止和它「拉锯」；（已实现）
- 用户按返回键回到上一个 App 不产生点击事件，识别为「返回」而非弹窗；（已实现）
- 「返回」后 800ms 它仍在前台（很多应用首页要按两次返回才退出），改为回到桌面；（已实现）
- 通话或响铃中（按音频模式判断，含微信通话）不处理；（已实现）
- 刚被判为弹窗的 App 30 分钟内再次出现，不按「返回上一个 App」放过，防止反复弹出被隔次漏掉；（已实现）
- 服务启动后看到的第一个前台没有上下文，不判定。（已实现）

处理后给长辈一个短提示：「已帮您挡掉一个广告」（每分钟最多一次），用无障碍浮层显示，
不需要悬浮窗权限、不可点击、不抢焦点，样式同 Material 3 Snackbar。

### 1.7 手机体检

扫描用户安装的应用，按风险信号打分。强信号（骚扰记录、开着无障碍、设备管理器、
自装却无桌面图标、清理加速类名称）单独出现即列出；弱信号（悬浮窗、安装应用、读通知、
Shizuku、开机自启）只在有强信号时计入，避免微信这类正常应用被列为可疑。
家人标记为「正常」的应用不再出现。另含系统检查：开发者选项、USB 调试、快应用引擎。

### 1.5 元凶评分与处置建议

每个 App 累积事件数 + 体检风险信号 → 「骚扰分」，在元凶榜按分数排序。

| 风险信号（体检） | 来源 |
|---|---|
| 申请了悬浮窗权限 | `PackageInfo.requestedPermissions` |
| 没有桌面图标（隐藏） | 无 `LAUNCHER` 入口的非系统应用 |
| 开机自启 + 悬浮窗 | `RECEIVE_BOOT_COMPLETED` 组合 |
| 能安装其他应用 | `REQUEST_INSTALL_PACKAGES` |
| 开启了无障碍服务（非本 App） | `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` |
| 是设备管理器 | `DevicePolicyManager.activeAdmins` |
| 接入了 Shizuku | 声明了 `rikka.shizuku.ShizukuProvider` |
| 名称像「清理/加速/WiFi/计步/充电/天气」工具 | 关键词规则 |

系统层检查：开发者选项 / USB 调试 / 无线调试（Android 11+）是否开启、是否存在快应用引擎。
已冻结的应用不再列为风险。

### 1.6 维护模式（增强，临时）

```
家人页 → 维护模式
  └─ 准备三步：安装 Shizuku → 启动（无线调试）→ 授权给本 App（只需一次）
       └─ 开始维护：可疑应用、手机体检、维护页的应用面板里出现「直接处理」
            收回悬浮窗 / 禁止后台运行 / 关闭通知 / 关闭它的无障碍 / 冻结·解冻 / 直接卸载（二次确认）
            └─ 结束维护（手动，或 30 分钟无操作；每次操作顺延）：
                 关闭无线调试（settings put global adb_wifi_enabled 0）→ 解绑并销毁 shell 进程
```

| 处置 | 命令（shell 身份执行） |
|---|---|
| 收回悬浮窗 | `appops set <pkg> SYSTEM_ALERT_WINDOW ignore` + `am force-stop` |
| 禁止后台运行 | `appops set <pkg> RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND ignore` + `am force-stop` |
| 关闭通知 | 13+：`pm revoke <pkg> POST_NOTIFICATIONS`；更早：`appops set POST_NOTIFICATION ignore` |
| 关闭它的无障碍 | 从 `enabled_accessibility_services` 里去掉它的组件 |
| 冻结 / 解冻 | `pm disable-user --user N` / `pm enable --user N` |
| 直接卸载 | `pm uninstall --user N`（设备管理器应用会失败，提示先取消） |

实现要点：
- `ShellService` 是手写 Binder（不用 AIDL），由 Shizuku 以 UserService 形式在 shell 进程实例化；
  销毁事务码是 `16777115`（AIDL 的 16777114 + FIRST_CALL_TRANSACTION），处理错会导致进程残留。
- 维护结束时间写入 DataStore。App 进程在维护中途被杀，下次启动时补做收尾（已实测）。
- `daemon(false)`：本 App 进程退出时 shell 进程随之销毁。

**授权弹窗监视**：Shizuku 授权弹窗（`RequestPermissionActivity`）出现时，除非正在维护、或本 App
自己刚在 15 秒内发起授权，否则不论守护方式、冷却、是否通话都立即「返回」挡掉，
记为「索要高级权限」，元凶是弹窗前的前台应用（即使被标记为正常也照记）。

### 1.7 分享报告

家人页右上角 → 报告卡片（近 7 天发现/拦截/标记、骚扰最多的 5 个应用、守护状态）→
用 Compose `GraphicsLayer` 直接取图，存到 cache/share，经 FileProvider 交给系统分享面板。

---

## 2. 技术架构

### 2.1 分层

```
┌──────────────── UI（Compose + Navigation3）────────────────┐
│ elder/Home   setup/Wizard   family/{Dashboard, Culprit,     │
│                              Scan, Maintenance, Settings}   │
└───────────────▲────────────────────────────────────────────┘
                │ StateFlow (ViewModel)
┌───────────────┴──────────── domain ────────────────────────┐
│ guard/  Detector（判定）  Responder（处理）  Attribution     │
│ scan/   AppScanner  SystemChecker  RiskScorer               │
│ remedy/ RemedyAction ─► SettingsNavigator（基础）            │
│                      └► PrivilegedExecutor（增强）           │
│ health/ HealthChecker                                        │
└───────────────▲────────────────────────────────────────────┘
                │
┌───────────────┴──────────── data ──────────────────────────┐
│ Room: guard_event, app_verdict    DataStore: settings       │
│ assets/rules.json（快应用引擎、商店、关键词、受保护包名）   │
└────────────────────────────────────────────────────────────┘
                ▲
┌───────────────┴──────────── 系统入口 ──────────────────────┐
│ GuardAccessibilityService   GuardNotificationListener       │
│ HealthCheckWorker（WorkManager 周期巡检）  BootReceiver      │
│ Shizuku UserService（仅维护模式）                            │
└────────────────────────────────────────────────────────────┘
```

依赖注入：不引入框架，`ElderGuardApp` 持有 `AppContainer` 手动装配。

### 2.2 关键接口

```kotlin
// 判定：输入无障碍事件流，输出骚扰事件
class Detector(rules, settings, clock) {
  fun onUserInteraction(pkg)
  fun onForegroundChanged(pkg, cls): GuardEvent?
  fun onScreenUnlocked()
}

// 高权限执行器：业务只依赖接口，底层可换 Shizuku / 内置 ADB
interface PrivilegedExecutor {
  val status: StateFlow<PrivilegeStatus>   // NOT_INSTALLED / NOT_RUNNING / NEEDS_PERMISSION / READY
  fun requestPermission(); fun isRequestingPermission(): Boolean
  suspend fun exec(vararg cmd: String): ShellResult
  fun release()
}

// 维护会话：开始/结束/超时、按应用给出可用处置并执行
class MaintenanceSession { val endsAt: StateFlow<Long?>; fun actionsFor(pkg); suspend fun perform(pkg, AppAction) }
```

### 2.3 数据模型

```
guard_event(id, time, type, culpritPkg, targetPkg, targetClass, handled, detail)
app_verdict(pkg, trust: UNKNOWN|TRUSTED|FLAGGED, note, updatedAt)
settings: setupDone, pinHash+salt, guardMode, lastHealthOk
```

### 2.4 国产 ROM 保活

- 无障碍服务本身优先级高，但仍会被「省电」杀；
- 设置向导里做电池优化白名单 + 各厂商自启动页跳转（尽力而为，按厂商 Intent 表依次尝试）；
- 服务被关闭（`onUnbind`）时立即发常驻通知「手机守护已停止」；
- `HealthCheckWorker` 每 15 分钟检查无障碍是否开启，未开启则持续提醒，恢复后自动撤掉通知。

**已知限制（实测）**：App 被「强行停止」时，系统会同时把它从已开启的无障碍列表移除、
撤掉它的通知、清掉它的定时任务，App 自身无法再发现这件事，只能等下次被打开时在首页显示
「守护未开启」。预防靠电池白名单、在最近任务里锁定，以及体检里提示清理加速类工具。

### 2.5 技术栈与版本（2026-10 查询的最新稳定版）

| 项 | 版本 |
|---|---|
| Gradle | 9.8.1 |
| AGP | 9.4.1（内置 Kotlin） |
| Kotlin / Compose Compiler | 2.4.21 |
| KSP | 2.3.12 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| Compose BOM | 2026.09.00 |
| Navigation3 | 1.2.0 |
| Lifecycle | 2.11.0 |
| Room | 2.8.5 |
| DataStore | 1.2.1 |
| WorkManager | 2.12.0 |
| Coroutines / Serialization | 1.11.0 / 1.11.0 |
| Shizuku API | 13.1.5 |

---

## 3. 分期

| 阶段 | 内容 |
|---|---|
| P0 骨架 ✅ | 工程、分层、设置向导与权限清单、无障碍前台追踪 + 判定 + 记录、长辈首页、家人密码 |
| P1 治理 ✅ | 元凶榜与行为说明、基础模式处置；返回无效时回桌面、通话中不处理；长辈提示浮层；手机体检（风险应用 + 系统检查）；守护停止通知 + 15 分钟巡检 |
| P2 增强 ✅ | Shizuku 维护模式（6 种直接处置、用完即关、进程被杀后补收尾）、授权弹窗监视、分享报告图片、体检增加无线调试检查 |
| P3 | 设置向导加首次体检 ✅；内置 ADB（替代 Shizuku） |

不做：本地 VPN 广告域名屏蔽（需要联网权限，与「不联网」承诺冲突）、子女端（需要服务器和账号体系）。

## 4. 测试设备注意

当前联调设备为 Android 13 车机（横屏 2000×1200）。快应用、厂商自启动、
MIUI「后台弹出界面」等行为必须在华为/小米/OPPO/vivo 真机上验证。
