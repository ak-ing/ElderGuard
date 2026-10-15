package com.elderguard.app.guard

import com.elderguard.app.core.Detection
import com.elderguard.app.core.EventType
import com.elderguard.app.core.GuardRules
import com.elderguard.app.core.Trust
import com.elderguard.app.privileged.ShizukuExecutor

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
  /** 维护模式中、或本 App 自己刚发起授权时，放行 Shizuku 授权弹窗 */
  @Volatile var privilegeRequestsAllowed: () -> Boolean = { false }

  private var lastInteractionAt = Long.MIN_VALUE / 2
  private var unlockedAt = Long.MIN_VALUE / 2
  /** 当前前台 App */
  private var current: String? = null

  /** 最近被判为骚扰的前台包名 → 时间 */
  private val lastDetectedAt = HashMap<String, Long>()

  /** 当前前台 App（供处理后核对「返回」是否生效） */
  val currentPackage: String?
    get() = current
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
    // Shizuku 授权弹窗：只有家人开着维护模式时才放行，否则不论有没有点击都挡掉，防止长辈被诱导点「允许」。
    // 要在「前台没变」的判断之前：从 Shizuku 管理器里弹出的授权框和管理器同包
    if (pkg == ShizukuExecutor.SHIZUKU_PACKAGE && cls == ShizukuExecutor.SHIZUKU_PERMISSION_ACTIVITY) {
      if (pkg != from) {
        previous = from
        current = pkg
      }
      if (privilegeRequestsAllowed()) return null
      val requester = from?.takeUnless { it == pkg || it == selfPackage || it in rules.protectedPackages || it in systemPackages } ?: pkg
      return Detection(EventType.PRIVILEGE_REQUEST, requester, pkg, cls, autoBlockable = true)
    }

    if (pkg == from) return null
    val returnedBack = pkg == previous
    previous = from
    current = pkg

    // 服务刚启动时看到的第一个前台没有上下文可比，不判定
    if (from == null || isExempt(pkg)) return null
    val now = clock()
    if (now - lastInteractionAt <= rules.interactionWindowMs) return null

    val culpritOfJump = from.takeUnless { isExempt(it) } ?: pkg
    // 刚被判为弹窗的 App 再次出现，不能按「返回上一个 App」放过，否则反复弹出的广告每隔一次就漏掉
    val recentlyFlagged = now - (lastDetectedAt[pkg] ?: Long.MIN_VALUE / 2) <= SUSPECT_MEMORY_MS
    val detection = when {
      pkg in rules.quickAppEngines ->
        Detection(EventType.QUICK_APP_LAUNCH, culpritOfJump, pkg, cls, autoBlockable = true)
      pkg in rules.appStores ->
        Detection(EventType.STORE_REDIRECT, culpritOfJump, pkg, cls, autoBlockable = true)
      // 用户按返回键回到上一个 App 不会产生点击事件，不能当成弹窗
      returnedBack && !recentlyFlagged -> null
      now - unlockedAt <= rules.unlockWindowMs ->
        Detection(EventType.UNLOCK_POPUP, pkg, pkg, cls, autoBlockable = isFlagged(pkg))
      else -> Detection(EventType.UNSOLICITED_LAUNCH, pkg, pkg, cls, autoBlockable = isFlagged(pkg))
    }
    if (detection != null) lastDetectedAt[detection.targetPkg] = now
    return detection
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

  private companion object {
    const val SUSPECT_MEMORY_MS = 30 * 60 * 1000L
  }

  private fun isExempt(pkg: String) =
    pkg == selfPackage || pkg in rules.protectedPackages || pkg in systemPackages || verdicts[pkg] == Trust.TRUSTED
}
