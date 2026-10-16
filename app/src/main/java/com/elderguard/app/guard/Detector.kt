package com.elderguard.app.guard

import com.elderguard.app.core.Detection
import com.elderguard.app.core.EventType
import com.elderguard.app.core.GuardRules
import com.elderguard.app.core.Trust
import com.elderguard.app.privileged.ShizukuExecutor

/**
 * 判定引擎（纯 Kotlin，不依赖 Android，便于单元测试）。
 *
 * 只按确定的事实判定：前台出现的是快应用引擎、家人标记的流氓应用、或 Shizuku 授权弹窗。
 * 不推测「是不是长辈点的」：系统不告诉普通应用启动是谁发起的，点击信号也不完整（桌面、侧边栏、Compose 应用都可能没有）。
 */
class Detector(private val rules: GuardRules) {
  /** 桌面、输入法等动态获取的受保护包名 */
  @Volatile var systemPackages: Set<String> = emptySet()
  @Volatile var verdicts: Map<String, Trust> = emptyMap()
  /** 家人规则：挡掉快应用 */
  @Volatile var blockQuickApps: Boolean = true
  /** 本 App 包名 */
  var selfPackage: String = ""
  /** 维护模式中、或本 App 自己刚发起授权时，放行 Shizuku 授权弹窗 */
  @Volatile var privilegeRequestsAllowed: () -> Boolean = { false }

  /** 当前前台 App */
  private var current: String? = null

  /** 当前前台 App（供处理后核对「返回」是否生效） */
  val currentPackage: String?
    get() = current
  /** 当前前台之前的那个 App，写进日志便于核对 */
  var previous: String? = null
    private set

  /** 前台 Activity 切换。返回 null 表示不用处理。 */
  fun onForegroundChanged(pkg: String, cls: String?): Detection? {
    val from = current
    // Shizuku 授权弹窗：只有家人开着维护模式时才放行，否则挡掉，防止长辈被诱导点「允许」。
    // 要在「前台没变」的判断之前：从 Shizuku 管理器里弹出的授权框和管理器同包
    if (pkg == ShizukuExecutor.SHIZUKU_PACKAGE && cls == ShizukuExecutor.SHIZUKU_PERMISSION_ACTIVITY) {
      if (pkg != from) {
        previous = from
        current = pkg
      }
      if (privilegeRequestsAllowed()) return null
      // 被标记为正常的 App 也照记：索要高级权限后果严重
      val requester =
        from?.takeUnless { it == pkg || it == selfPackage || it in rules.protectedPackages || it in systemPackages } ?: pkg
      return Detection(EventType.PRIVILEGE_REQUEST, requester, pkg, cls, autoBlockable = true)
    }

    if (pkg == from) return null
    previous = from
    current = pkg

    return when {
      pkg in rules.quickAppEngines ->
        if (blockQuickApps) {
          // 记在当时正在用的 App 名下（只作参考）；从桌面等系统界面打开的记在快应用引擎自己名下
          val usedApp = from?.takeUnless { isExempt(it) || it in rules.quickAppEngines } ?: pkg
          Detection(EventType.QUICK_APP_LAUNCH, usedApp, pkg, cls, autoBlockable = true)
        } else {
          null
        }
      isExempt(pkg) -> null
      isFlagged(pkg) -> Detection(EventType.FLAGGED_LAUNCH, pkg, pkg, cls, autoBlockable = true)
      else -> null
    }
  }

  /** 挡掉后已退回 [pkg]，但系统没有发窗口切换事件：只更新前台记录，不做判定 */
  fun syncForeground(pkg: String) {
    if (pkg == current) return
    previous = current
    current = pkg
  }

  /** 新出现的悬浮窗，由 [OverlayWatcher] 去重和豁免后调用 */
  fun onOverlay(pkg: String): Detection? =
    if (isExempt(pkg)) null else Detection(EventType.OVERLAY_POPUP, pkg, pkg, null, autoBlockable = false)

  /** 通知判定：可疑关键词，或非来电/闹钟却带全屏意图 */
  fun onNotification(pkg: String, text: String, hasFullScreenIntent: Boolean, isCallOrAlarm: Boolean): Detection? {
    if (isExempt(pkg)) return null
    val keyword = rules.suspiciousKeywords.firstOrNull { text.contains(it) }
    val fullScreenAbuse = hasFullScreenIntent && !isCallOrAlarm
    if (keyword == null && !fullScreenAbuse) return null
    val detail = keyword?.let { "包含「$it」：${text.take(60)}" } ?: "全屏通知：${text.take(60)}"
    return Detection(EventType.SUSPICIOUS_NOTIFICATION, pkg, pkg, null, autoBlockable = isFlagged(pkg), detail = detail)
  }

  private fun isFlagged(pkg: String) = verdicts[pkg] == Trust.FLAGGED

  private fun isExempt(pkg: String) =
    pkg == selfPackage || pkg in rules.protectedPackages || pkg in systemPackages || verdicts[pkg] == Trust.TRUSTED
}
