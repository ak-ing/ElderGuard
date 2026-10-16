# 长辈守护（ElderGuard）架构设计

> 定位：长辈手机的「家人规则 + 管家」。挡掉长辈用不到的高风险入口（快应用），
> 记下确定的事实（谁弹了悬浮窗、发了可疑通知、新装了什么），由家人从源头处置（卸载、冻结、收回权限）。
> 分发：GitHub 开源，侧载 APK。原则：**本地运行、不联网、可审计；只按确定的事实动手，不猜「是不是长辈点的」**。
>
> 1.1.0 起不再推测「是不是长辈点的」：普通应用拿不到启动的发起者（系统不提供；读日志的方案已否决），
> 点击信号也不完整（ColorOS 桌面、侧边栏、Compose/Flutter 应用、手势都可能没有），
> 「长辈真想去」和「广告诱导长辈碰了一下」在系统看来是同一个动作。1.0.0 基于点击推测的规则修一个误判就冒出一个新的。

---

## 1. 产品使用逻辑

### 1.1 两类角色，同一台手机

| 角色 | 在 App 里做什么 | 不做什么 |
|---|---|---|
| **长辈** | 只看一个大字状态页：「守护中 ✓」/「守护出问题了，请联系家人」；偶尔看到「刚帮您挡掉一个广告」的提示 | 不需要做任何判断，不会被要求点「确定/授权」 |
| **家人** | 安装、开权限、看记录、处置元凶、维护模式 | — |

家人的所有入口都在**家人密码（PIN）**之后。长辈不会误删 App、误关守护、误把流氓 App 加白名单。
离开 App 超过 5 分钟（含进程被杀后恢复）家人页自动锁上；连续输错 5 次锁 30 秒，退出密码页重进不清零。
密码键盘每按一下震一下，输错连震两下、输对重震一下。直接用振动器（`VIBRATE` 普通权限），
不走 View 触感反馈：后者受系统「触摸振动」开关控制，很多手机默认关着。没有振动器的设备不震。

页面切换用放大淡入的导航动画：新页面从 0.9 倍放大并淡入，旧页面放大到 1.1 倍并淡出，
500ms，前进、返回、预测性返回都一样；设置向导的分步整块左右滑动。

动画不叠影（`theme/Motion.kt`）：
- 列表项增删（展开收起、标记后移出）：移走的项立刻消失、不淡出，下面的项平滑上移；新项等位置让开后再淡入；
- 文字替换（状态、说明、数字、按钮文字）用 fade through：旧的 90ms 淡出后新的再淡入；
- 图标替换用 `popSwap`：旧图标缩小淡出后新图标放大淡入；
- 家人页「需要留意的应用」「新装的应用」默认显示 3 个、「最近记录」5 条，「查看全部 N 个」展开；
  体检、自检、维护模式（可选）放在总览下面一组。

配色和图标试过两版清新绿（SchemeVibrant、微信式中性底 + #07C160），家人都觉得不如原来的深青绿，已还原。

### 1.2 生命周期

```
安装 ──► 首次设置向导（家人陪同完成）
          ① 说明：做什么、不联网、开源
          ② 设置家人密码
          ③ 开权限（逐项跳转设置页，回来自动刷新状态），分三组：
               需要开启：无障碍服务；读取应用列表（国产系统才有，见下）
               建议开启：通知使用权、发送通知
               守护经常被关掉时再开（默认收起）：不限制耗电、厂商自启动
          ④ 选择守护方式：仅记录 / 自动挡掉（默认）
          ⑤ 首次体检：风险应用（可查看原因、卸载、标记）+ 需处理的系统项；可跳过
                │
                ▼
       ┌──── 日常守护（常驻，长辈无感）──────────────┐
       │  挡掉：快应用、家人标记的流氓应用              │
       │  记录：悬浮窗、可疑通知、新装的应用             │
       │  健康巡检（服务是否被杀）                       │
       └────────────────────────────────────────────┘
                │ 家人回来 / 定期
                ▼
       家人入口（PIN）
          ├─ 需要留意的应用：谁弹了悬浮窗、发了可疑通知，证据
          ├─ 新装的应用：近 30 天，来源 + 风险信号
          │    └─ 处置：标记流氓 / 正常、卸载、应用设置；维护模式下直接冻结、收回权限
          ├─ 守护方式 + 「挡掉快应用」开关
          ├─ 体检：重新扫描；维护模式下可直接冻结快应用引擎
          ├─ 维护模式（Shizuku，临时开启，用完即关）
          └─ 分享报告（生成图片，发微信）
```

### 1.3 日常守护的规则（核心）

全部规则只看确定的事实，不看「之前有没有点击」：

| 事实 | 事件类型 | 处理 | 记在谁名下 |
|---|---|---|---|
| 快应用引擎来到前台（「挡掉快应用」开着） | `QUICK_APP_LAUNCH` | 返回 | 当时正在用的 App（只作参考）；从桌面等系统界面打开的记在引擎自己名下 |
| 家人标记的流氓应用来到前台 | `FLAGGED_LAUNCH` | 返回 | 它自己 |
| 不在前台的 App 新弹出悬浮窗 | `OVERLAY_POPUP` | 只记录（别人的悬浮窗按返回关不掉，由家人收回悬浮窗权限） | 它自己 |
| 通知带全屏意图（非来电/闹钟）或命中「病毒/中奖/内存不足…」 | `SUSPICIOUS_NOTIFICATION` | 只记录；流氓应用的撤掉 | 发通知的 App |
| Shizuku 授权弹窗（非维护、非本 App 刚发起） | `PRIVILEGE_REQUEST` | 返回（不论守护方式、冷却、通话） | 弹窗前的前台 App |
| 新装了一个应用（不含更新） | 「新装的应用」表 | 记录时间、来源，展示时附风险信号 | — |
| 应用商店、其他普通应用来到前台 | — | **不拦、不记**（长辈可以正常下载） | — |

代价：没被标记的应用靠整页界面自己跳出来的广告不会有记录。补救：装它时有记录、它弹悬浮窗/全屏通知有记录、
体检能看到它申请的悬浮窗/开机自启等能力；家人标记后它再出现就挡。

**悬浮窗怎么发现**：订阅 `TYPE_WINDOWS_CHANGED`，用 `getWindows()` 取可交互窗口列表，类型为 `TYPE_SYSTEM`
且属于第三方应用的窗口即悬浮窗（实测：`TYPE_APPLICATION_OVERLAY` 窗口以 `TYPE_SYSTEM` 出现，能取到所属包名）。
`OverlayWatcher` 只报「新增」的窗口，并排除：
- 服务启动时已经在的（启动时列表可能是空的或不全：空列表不当基准，启动 3 秒内出现的都算启动前就有）；
- 暂时被藏起又重新出现的：系统设置等页面在前台时，系统会藏起别的应用的悬浮窗，回到桌面时同一个窗口
  （id 不变）重新出现（实测），所以记住见过的所有窗口 id，而不只和上一次的列表比；
- 刚出现时还读不出所属应用的，先不算见过，认出来后再判断（每个窗口最多读 5 次）；
- 前台 App 自己的；
- 厂商预装应用的悬浮按钮；
- App 刚离开前台 2 秒内出现的（通话缩成小窗、导航悬浮窗）；
- 用户刚操作过这个 App 原有窗口后出现的（点悬浮球展开）；新弹出的窗口抢到焦点不算；
- 同一 App 10 分钟内重复的。

限制：系统不把**不可触摸**的悬浮窗放进列表（实测），这类看不到。

**新装应用**：两条路径。① 监听 `ACTION_PACKAGE_ADDED`（排除 `EXTRA_REPLACING`），装上就记；
② 守护启动时、每次打开家人页，按系统记的首次安装时间（`PackageInfo.firstInstallTime`）补齐近 30 天装的用户应用：
OPPO 平板（ColorOS 16）实测从商店装应用后列表里没有，广播可能被厂商系统丢掉；守护没在运行时装的也靠这条补上。
补记的写进自检报告的事件记录，报告里另列「守护记下的」和「系统有记录、守护没记下的」，对照可知广播是否丢失。
卸载后重装（首次安装时间更晚）会更新时间；已卸载应用的旧记录保留。记录时间统一用首次安装时间。
来源来源取 `getInstallSourceInfo`
（Android 13 起区分安装包文件/下载的安装包/商店；更早只有安装器包名；adb 安装没有来源）。

**读取应用列表权限**：ColorOS、MIUI 等按团体标准 T/TAF 108-2022 另加了 `com.android.permission.GET_INSTALLED_APPS`。
没授权时 `getInstalledPackages` 只返回系统应用和自己，并且一调用就弹系统授权框（1.1.0 在 OPPO 平板上实测：
开无障碍后守护服务启动补齐新装应用，框在后台突然弹出，家人拒绝后体检、新装应用全部失效）。处理：
清单里声明；设置向导「需要开启」里用运行时权限申请（拒绝过就跳应用详情页）；系统没有这个权限
（标准里的 `oem_installed_apps_runtime_permission_enable` 不为 1 且权限名不存在）时不显示；
没授权前不读应用列表，体检和首次体检顶部提示「没有读取应用列表的权限」并给「开启」按钮。

**受保护名单（永远不判为骚扰、不自动处理）**：
本 App、当前桌面、系统界面（systemui）、输入法、拨号/来电、闹钟、设置、各家安全中心/权限页、
系统分享面板、家人加入的信任名单。
「桌面」只算系统自带的桌面和用户设为默认的桌面：锁屏广告类应用常声明 HOME 来拦主页键，
不能因此被放过；设置里开机用的 FallbackHome（优先级 -1000）也不算。

**通知去重**：同一条通知刷新（key 不变、命中内容不变）只记一次；分组摘要不判定。

**聊天应用**（微信、QQ、短信等）的通知不做关键词判定：亲友一句「恭喜您」不能把微信算成可疑应用，只看全屏意图。
「消息」类别、聊天样式任何应用都能设，所以只认规则里的聊天应用名单，以及带这类标记的系统自带应用；
被标记为流氓的一律不豁免。被标记以后它再发来同样内容的通知，会重新处理（撤掉）。

### 1.4 自动处理策略

| 守护方式 | 行为 |
|---|---|
| 仅记录 | 只记事件，不操作手机（Shizuku 授权弹窗仍然挡） |
| 自动挡掉（默认） | 快应用（规则开着时）、流氓应用来到前台就返回；流氓应用的可疑通知撤掉 |

即：**记录事实 → 家人确认（标记流氓/正常，或直接卸载、冻结）→ 以后自动挡**。家人标记「正常」的 App 不再记录。
快应用要从根上去掉：维护模式下在体检的「快应用」系统项里一键冻结引擎。

防误伤约束：
- 同一 App 10 秒内最多自动处理 1 次，防止和它「拉锯」；
- 「返回」后 800ms 它仍在屏幕上（很多应用首页要按两次返回才退出；快应用游戏常吃掉返回键，OPPO 平板实测），
  改为回到桌面；它的任何应用窗口还在都算没退出；
- 退到后台后用 `killBackgroundProcesses` 结束它的进程（`KILL_BACKGROUND_PROCESSES` 普通权限），免得马上又弹出来；
  在前台时普通应用结束不了它，所以要先返回或回到桌面；以最上层的应用窗口（`TYPE_APPLICATION`）为准，
  因为退回已有界面时系统不一定发窗口切换事件；不用活动窗口：活动的可能是别的应用的悬浮窗，
  把它当成前台会漏掉它之后真正打开的界面；
- 通话或响铃中（按音频模式判断，含微信通话）不处理。

无障碍只订阅 `typeWindowStateChanged`（前台切换）和 `typeWindowsChanged`（窗口增减），
不收点击和输入事件，读不到长辈打的字。

处理后给长辈一个短提示：「已挡掉快应用」/「已帮您挡掉一个广告」（每分钟最多一次），用无障碍浮层显示，
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

### 1.5.1 不用 Shizuku 的处置（默认）

OPPO 平板（ColorOS 16）实测 Shizuku 很难启动（要先切英文找「Disable system optimization」），普通家人做不来，
所以维护模式退为「可选」。应用面板的「关掉它的能力」直接跳到系统里**这个应用**的开关，只列它真开着的：

| 处置 | 跳转（依次尝试，失败退到下一个） |
|---|---|
| 取消设备管理器 | `Settings$DeviceAdminSettingsActivity` → 安全设置 |
| 关掉它的无障碍 | `ACCESSIBILITY_DETAILS_SETTINGS`（带它的组件，直达开关）→ 无障碍列表 |
| 关掉它的悬浮窗 | 隐藏的 `MANAGE_APP_OVERLAY_PERMISSION`（直达，但实测多数系统不让普通应用进）→ 「显示在其他应用上层」列表（说明里写出应用名让家人找）→ 应用详情 |
| 关掉它读通知 | `NOTIFICATION_LISTENER_DETAIL_SETTINGS`（直达）→ 通知使用权列表 |
| 关掉它的通知 | `APP_NOTIFICATION_SETTINGS`（直达，实测）→ 应用详情 |
| 耗电、后台、自启动 | 应用详情页 |
| 卸载 | 系统卸载确认框 |

冻结做不到，用卸载代替。

### 1.6 维护模式（可选，临时）

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
- Shizuku 在运行时，App 进程一启动就会收到它的 binder，但本 App 的接入还没完成，此时调
  `checkSelfPermission` 会抛「Not an attached client」。1.1.0 在 `Application.onCreate` 里查状态，OPPO 平板上
  装完首次打开就闪退；1.1.1 起查询失败按「未启动」处理，接入完成后 binder 监听会再刷新。

**授权弹窗监视**：Shizuku 授权弹窗（`RequestPermissionActivity`）出现时，除非正在维护、或本 App
自己刚在 15 秒内发起授权，否则不论守护方式、冷却、是否通话都立即「返回」挡掉，
记为「索要高级权限」，元凶是弹窗前的前台应用（即使被标记为正常也照记）。

### 1.7 分享报告

家人页右上角 → 报告卡片（近 7 天发现/拦截/标记、骚扰最多的 5 个应用、守护状态）→
用 Compose `GraphicsLayer` 直接取图，存到 cache/share，经 FileProvider 交给系统分享面板。

### 1.8 守护自检

家人页 → 守护自检。用途：确认守护在这台手机上真的有效；没有电脑、无法远程 adb 时，作为厂商真机的测试手段。

1. 守护服务：无障碍已开启且服务已连接
2. 通知监听：已开启且已连接（未开启算跳过，可选项）
3. 挡掉快应用：用 `hap://app/…` 查出这台手机处理快应用链接的应用，取名单内的那个；倒计时 4 秒后打开，
   8 秒内等守护记下并处理；刚测过同一个应用时倒计时延长到守护的 10 秒冷却结束之后，否则连测会误报失败；家人关掉了「挡掉快应用」或引擎已冻结时算跳过

（1.1.0 起商店不再拦截，去掉了「挡掉跳商店」一项。）

测试产生的记录在回到本页后删除，不进元凶榜。完成后生成纯文本报告，经 FileProvider 分享：
- 设备、权限、守护方式与快应用规则、安装来源、自检结果；
- 振动：有没有马达、预设效果支持、系统振动设置（1.1.1 起；OPPO OPD2506 平板据此确认没有振动马达）；
- 快应用链接的实际处理者及是否在名单内；
- 本次守护启动后最近 80 条无障碍事件（只有类型和包名/类名，含「新装」「补记新装」「返回无效，回到桌面」「结束进程」）；
- 最近 30 条记录；
- 新装的应用：守护记下的 vs 系统有记录、守护没记下的（对照可知安装广播是否丢失）；
- 最近的进程退出（`getHistoricalProcessExitReasons`：崩溃、权限变化被结束、更新等）和最近一次崩溃的异常栈（1.1.1 起，`CrashLog`）；
- 本 App 自己的日志。

崩溃栈是 R8 混淆过的，用报告里的 `r8-map-id` 找对应版本的 `mapping-x.y.z.txt`（Release 附件 / CI Artifacts）还原。
报告由家人决定发给谁，App 本身不联网。

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
│ Room: guard_event, app_verdict, installed_app               │
│ DataStore: settings                                          │
│ assets/rules.json（快应用引擎、关键词、受保护包名、聊天应用）│
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
// 判定：只按确定的事实（快应用、流氓应用、授权弹窗、悬浮窗、通知）
class Detector(rules) {
  var blockQuickApps: Boolean; var verdicts: Map<String, Trust>
  fun onForegroundChanged(pkg, cls): Detection?
  fun onOverlay(pkg): Detection?
  fun onNotification(pkg, text, hasFullScreenIntent, isCallOrAlarm): Detection?
}

// 悬浮窗：从窗口列表里找出新弹出的，负责去重和豁免
class OverlayWatcher(clock) { fun onForegroundChanged(from); fun onWindows(windows, foreground): List<String> }

// 高权限执行器：业务只依赖接口，底层用 Shizuku（不用内置 ADB，避免联网权限）
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
app_verdict(pkg, trust: TRUSTED|FLAGGED, updatedAt)
installed_app(pkg, time, installer, source)        // 数据库 v2（1.1.0）
settings: setupDone, pinHash+salt, guardMode, blockQuickApps, maintenanceEndsAt
```

数据库 1 → 2 迁移：删掉推测产生的旧记录（`UNSOLICITED_LAUNCH`、`UNLOCK_POPUP`、`STORE_REDIRECT`），
保留其余记录和家人结论，新建 `installed_app`（从旧版本升级实测通过）。

```
```

### 2.4 国产 ROM 保活

- 无障碍服务本身优先级高，但仍会被「省电」杀；
- 设置向导里做电池优化白名单 + 各厂商自启动页跳转（尽力而为，按厂商 Intent 表依次尝试）；
- 服务被关闭（`onUnbind`）时立即发常驻通知「手机守护已停止」；
- `HealthCheckWorker` 每 15 分钟检查无障碍是否开启，未开启则持续提醒，恢复后自动撤掉通知；
- 开启「发送通知」时发一条示范通知（8 秒后自动消失）：平时没有要发的通知，家人开了却从没见过，会以为没用；
- **不出现在最近任务里**（`MainActivity` 的 `excludeFromRecents`，1.1.1 起）：ColorOS 等在最近任务里划掉应用、
  「一键清理」等于强行停止。实测：不在最近任务里时一键清理后守护进程和无障碍都还在。
  家人从桌面图标回到 App，停留的页面不变。

**已知限制（实测）**：App 被「强行停止」时，系统会同时把它从已开启的无障碍列表移除、
撤掉它的通知、清掉它的定时任务（`am force-stop` 可复现），App 自身无法再发现这件事，只能等下次被打开时在首页显示
「守护未开启」。预防靠不进最近任务、电池白名单，以及体检里提示清理加速类工具。

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
| P3 ✅ | 设置向导加首次体检；守护自检（模拟快应用、跳商店并生成报告） |
| 1.0.0 发布 ✅ | 按 OPPO 平板报告修误判（桌面打开、手势切换、本 App/分享面板跳出、聊天通知）；ColorOS 开无障碍即视为允许自启动；体检不把厂商预装算成工具类风险；维护模式可解冻；补充荣耀/三星/联想/中兴等商店与快应用名单；正式图标；release 签名、R8、Lint 零问题 |
| 1.1.0 ✅ | 重新定位：只按确定的事实动手。去掉基于点击推测的「自动弹出/解锁弹窗/跳商店」；商店不再拦；新增悬浮窗记录、新装应用记录（广播 + 按首次安装时间补齐）、「挡掉快应用」开关、维护模式下冻结快应用引擎；无障碍不再收点击和输入事件；权限页分组；家人页离开 5 分钟自动锁、输错密码锁定不能靠重进清零。代码审查修复（悬浮窗被藏起再出现不算新弹出、聊天通知豁免收紧、自检冷却等）。快应用退不出去时回桌面并结束进程。不用 Shizuku 的处置：应用面板「关掉它的能力」直达系统开关，维护模式改为可选。家人页工具成组、列表折叠；放大淡入的导航动画；动画去重影。密码震动。CI 自动构建与签名发布，GPL-3.0 开源。ARTEMIS 全量功能测试 25 项通过 |
| 1.1.1 ✅ | 适配国产系统「读取应用列表」权限（声明、设置向导申请、未授权不读、体检提示）；修复装着 Shizuku 时首次打开闪退；不出现在最近任务里；开启发送通知时发示范通知；自检报告加振动信息、进程退出原因和最近一次崩溃；查询应用列表的系统接口都加保护；CI 保存并随 Release 附上 R8 混淆映射 |

不做：
- 本地 VPN 广告域名屏蔽：需要联网权限，与「不联网」承诺冲突
- 内置 ADB（替代 Shizuku）：连本机 adb 也要联网权限，同样与「不联网」冲突；Shizuku 通过 Binder 通信，本 App 无需联网权限
- 子女端：需要服务器和账号体系

## 4. 测试设备注意

没有快应用引擎、没有国产系统权限的测试机上，用两个自制测试应用模拟：`com.test.cleanmaster`「手机清理大师」
（悬浮窗、通知、广告页）和 `com.huawei.fastapp`「快应用（测试）」（处理 `hap://`，吞返回键）。快应用、厂商自启动、
读取应用列表、MIUI「后台弹出界面」等行为必须在华为/小米/OPPO/vivo 真机上验证。
无法连接电脑的设备：安装 App 后跑一遍「守护自检」，把报告发回来。
注意：`uiautomator dump` 会暂停其他无障碍服务（守护显示「已暂停」）；自动化测试用 ARTEMIS 自带的无障碍辅助程序，
不要让它退回 UIAutomator。

已验证：OPPO OPD2506 平板（ColorOS 16 / Android 16）1.0.0 自检四项通过；用测试用「流氓应用」验证了
标记后自动挡掉、假病毒通知、体检、维护模式全部 6 种处置。
1.1.0 验证：悬浮窗记录（离开前台 0.5 秒内的豁免、5 秒后的记录；守护启动前就在、被系统设置藏起再出现的不记）、
新装应用记录、流氓应用挡掉、普通应用不记、数据库从旧版本升级；ARTEMIS 全量功能测试 25 项通过
（设置向导、密码、家人页折叠与切换、吞返回键的快应用回桌面并结束进程、悬浮窗记录、应用面板跳转系统开关、
标记后挡掉、体检、自检、周报、维护模式页、返回导航）。
1.1.x OPPO OPD2506 平板验证：快应用服务框架 `com.nearme.instant.platform` 0.1~0.2 秒挡掉，连测第二次通过，
退不出去时回桌面并结束进程；从软件商店装的应用收不到 `PACKAGE_ADDED`，靠首次安装时间补齐；「读取应用列表」授权后
体检、新装应用正常；装着 Shizuku 时首次打开闪退（「Not an attached client」）已修复；没有振动马达；
Shizuku 在 ColorOS 16 上很难启动（要在英文界面打开开发者选项「Disable system optimization」）。
未验证：维护模式下冻结快应用引擎、「关掉它的能力」各跳转在 ColorOS 上能到哪一层、秒玩小游戏是否走快应用引擎。

## 5. 发布

- `./gradlew :app:assembleRelease`，开启 R8 与资源压缩。
- 正式包由 GitHub Actions 构建（`.github/workflows/build.yml`）：推送 main / PR 时单测、Lint、打包；
  推送 `v*` 标签时检查标签与 `versionName` 一致，签名打包并建 Release 附上 APK。
- 签名：CI 从仓库 Secrets（`KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`）生成根目录
  `keystore.properties`，字段 `storeFile/storePassword/keyAlias/keyPassword`；没有该文件时 release 包不签名。
  1.1.0 起换成 CI 专用签名（证书 SHA-256 `6E:8A:15:D3:…:69:19`），装过更早版本的设备需先卸载。
  **Secrets 存进去就取不回来，jks 和密码另有离线备份；丢失后无法发布覆盖安装的更新。**
- 发布前跑 `:app:lintRelease`（需零问题）和 `:app:testDebugUnitTest`。
- R8 混淆映射：每次 CI 构建都作为 Artifact 保存（`mapping-<版本>-<提交>`），发版时以 `mapping-<版本>.txt` 附在 Release 里，
  用来还原真机自检报告里的崩溃栈。
- 发版流程：改 `versionCode`/`versionName` → 推 main，CI 包发到真机验证 → 推 `v<版本>` 标签，CI 建 Release。
