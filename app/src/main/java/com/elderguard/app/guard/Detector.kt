package com.elderguard.app.guard

import com.elderguard.app.core.Detection
import com.elderguard.app.core.EventType
import com.elderguard.app.core.GuardRules
import com.elderguard.app.core.Trust

/**
 * 骚扰判定引擎（纯 Kotlin，不依赖 Android，便于单元测试）。
 *
 * 核心定义：前台切换到了某个 App，但切换前 [GuardRules.interactionWindowMs] 内用户没有点击/输入。
 * 自动处理范围：快应用、跳商店（高确定性）；其他类型只有当元凶被家人标记为流氓时才自动处理。
 */
class Detector(private val rules: GuardRules, private val clock: () -> Long) {
  /** 桌面、输入法等动态获取的受保护包名 */
  @Volatile var systemPackages: Set<String> = emptySet()
  @Volatile var verdicts: Map<String, Trust> = emptyMap()
  /** 本 App 包名 */
  var selfPackage: String = ""

  private var lastInteractionAt = Long.MIN_VALUE / 2
  private var unlockedAt = Long.MIN_VALUE / 2
  /** 当前前台 App */
  private var current: String? = null
  /** 当前前台之前的那个 App，用于识别「按返回键回到上一个 App」 */
  private var previous: String? = null

  fun onUserInteraction() {
    lastInteractionAt = clock()
  }

  fun onScreenUnlocked() {
    unlockedAt = clock()
  }

  /** 前台 Activity 切换。返回 null 表示正常切换。 */
  fun onForegroundChanged(pkg: String, cls: String?): Detection? {
    val from = current
    if (pkg == from) return null
    val returnedBack = pkg == previous
    previous = from
    current = pkg

    if (isExempt(pkg)) return null
    val now = clock()
    if (now - lastInteractionAt <= rules.interactionWindowMs) return null

    val culpritOfJump = from?.takeUnless { isExempt(it) } ?: pkg
    return when {
      pkg in rules.quickAppEngines ->
        Detection(EventType.QUICK_APP_LAUNCH, culpritOfJump, pkg, cls, autoBlockable = true)
      pkg in rules.appStores ->
        Detection(EventType.STORE_REDIRECT, culpritOfJump, pkg, cls, autoBlockable = true)
      // 用户按返回键回到上一个 App 不会产生点击事件，不能当成弹窗
      returnedBack -> null
      now - unlockedAt <= rules.unlockWindowMs ->
        Detection(EventType.UNLOCK_POPUP, pkg, pkg, cls, autoBlockable = isFlagged(pkg))
      else -> Detection(EventType.UNSOLICITED_LAUNCH, pkg, pkg, cls, autoBlockable = isFlagged(pkg))
    }
  }

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
