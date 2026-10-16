package com.elderguard.app.guard

import com.elderguard.app.core.EventType
import com.elderguard.app.core.GuardRules
import com.elderguard.app.core.Trust
import com.elderguard.app.privileged.ShizukuExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DetectorTest {
  private val rules =
    GuardRules(
      version = 2,
      autoActionCooldownMs = 10_000,
      quickAppEngines = setOf("com.miui.hybrid"),
      protectedPackages = setOf("com.android.systemui", "com.android.dialer"),
      suspiciousKeywords = listOf("病毒", "中奖"),
    )
  private lateinit var detector: Detector

  @Before
  fun setUp() {
    detector = Detector(rules)
    detector.systemPackages = setOf("launcher")
    detector.selfPackage = "self"
  }

  @Test
  fun ordinaryApps_areNeverJudged() {
    // 不推测是不是长辈点的：普通 App 来到前台一律不处理、不记录
    detector.onForegroundChanged("launcher", null)
    assertNull(detector.onForegroundChanged("com.ad.cleaner", "AdActivity"))
    assertNull(detector.onForegroundChanged("com.tencent.mm", null))
    assertNull(detector.onForegroundChanged("com.xiaomi.market", null))
  }

  @Test
  fun quickApp_isBlockedAndCreditedToAppInUse() {
    detector.onForegroundChanged("com.ad.game", null)
    val d = detector.onForegroundChanged("com.miui.hybrid", "HybridActivity")!!
    assertEquals(EventType.QUICK_APP_LAUNCH, d.type)
    assertEquals("com.ad.game", d.culpritPkg)
    assertEquals("com.miui.hybrid", d.targetPkg)
    assertTrue(d.autoBlockable)
  }

  @Test
  fun quickAppFromLauncher_isBlockedAndCreditedToEngine() {
    // 规则本身就是挡掉快应用，从桌面打开也挡；只是不记在桌面名下
    detector.onForegroundChanged("launcher", null)
    val d = detector.onForegroundChanged("com.miui.hybrid", null)!!
    assertEquals("com.miui.hybrid", d.culpritPkg)
    assertTrue(d.autoBlockable)
  }

  @Test
  fun quickApp_allowedWhenRuleOff() {
    detector.blockQuickApps = false
    detector.onForegroundChanged("com.ad.game", null)
    assertNull(detector.onForegroundChanged("com.miui.hybrid", null))
  }

  @Test
  fun quickApp_firstForegroundAfterStart_isStillBlocked() {
    val d = detector.onForegroundChanged("com.miui.hybrid", null)!!
    assertEquals("com.miui.hybrid", d.culpritPkg)
  }

  @Test
  fun flaggedApp_isBlockedWheneverItAppears() {
    detector.verdicts = mapOf("com.ad.cleaner" to Trust.FLAGGED)
    detector.onForegroundChanged("launcher", null)
    val d = detector.onForegroundChanged("com.ad.cleaner", null)!!
    assertEquals(EventType.FLAGGED_LAUNCH, d.type)
    assertTrue(d.autoBlockable)
  }

  @Test
  fun samePackage_isNotJudgedTwice() {
    detector.verdicts = mapOf("com.ad.cleaner" to Trust.FLAGGED)
    detector.onForegroundChanged("com.ad.cleaner", "A")
    assertNull(detector.onForegroundChanged("com.ad.cleaner", "B"))
  }

  @Test
  fun syncForeground_avoidsRejudgingAfterBack() {
    detector.verdicts = mapOf("com.ad.cleaner" to Trust.FLAGGED)
    detector.onForegroundChanged("com.ad.cleaner", null)
    detector.syncForeground("com.tencent.mm")
    assertEquals("com.tencent.mm", detector.currentPackage)
    assertNull(detector.onForegroundChanged("com.tencent.mm", null))
  }

  @Test
  fun protectedAndTrusted_areNeverBlocked() {
    detector.verdicts = mapOf("com.android.dialer" to Trust.FLAGGED, "com.good" to Trust.TRUSTED)
    assertNull(detector.onForegroundChanged("com.android.dialer", null))
    assertNull(detector.onForegroundChanged("self", null))
    assertNull(detector.onForegroundChanged("com.good", null))
  }

  @Test
  fun overlay_isRecordOnly_andExemptionsApply() {
    val d = detector.onOverlay("com.ad.cleaner")!!
    assertEquals(EventType.OVERLAY_POPUP, d.type)
    assertFalse("别人的悬浮窗按返回关不掉，只记录", d.autoBlockable)
    detector.verdicts = mapOf("com.good" to Trust.TRUSTED)
    assertNull(detector.onOverlay("com.good"))
    assertNull(detector.onOverlay("com.android.systemui"))
    assertNull(detector.onOverlay("launcher"))
  }

  @Test
  fun notification_keywordOrFullScreen() {
    assertEquals(EventType.SUSPICIOUS_NOTIFICATION, detector.onNotification("com.browser", "您的手机中病毒了", false, false)!!.type)
    assertEquals(EventType.SUSPICIOUS_NOTIFICATION, detector.onNotification("com.ad", "看看", true, false)!!.type)
    assertNull(detector.onNotification("com.ad", "看看", true, true))
    assertNull(detector.onNotification("com.ad", "普通消息", false, false))
    assertNull(detector.onNotification("com.android.dialer", "中奖", true, false))
  }

  @Test
  fun notification_autoBlockableOnlyWhenFlagged() {
    assertFalse(detector.onNotification("com.ad", "中奖", false, false)!!.autoBlockable)
    detector.verdicts = mapOf("com.ad" to Trust.FLAGGED)
    assertTrue(detector.onNotification("com.ad", "中奖", false, false)!!.autoBlockable)
  }

  @Test
  fun shizukuPrompt_blockedAndCreditedToRequester() {
    detector.onForegroundChanged("com.ad.cleaner", null)
    val d = detector.onForegroundChanged(ShizukuExecutor.SHIZUKU_PACKAGE, ShizukuExecutor.SHIZUKU_PERMISSION_ACTIVITY)!!
    assertEquals(EventType.PRIVILEGE_REQUEST, d.type)
    assertEquals("com.ad.cleaner", d.culpritPkg)
    assertTrue(d.autoBlockable)
  }

  @Test
  fun shizukuPrompt_trustedRequesterStillNamed() {
    detector.verdicts = mapOf("com.good" to Trust.TRUSTED)
    detector.onForegroundChanged("com.good", null)
    val d = detector.onForegroundChanged(ShizukuExecutor.SHIZUKU_PACKAGE, ShizukuExecutor.SHIZUKU_PERMISSION_ACTIVITY)!!
    assertEquals("com.good", d.culpritPkg)
  }

  @Test
  fun shizukuPrompt_fromManagerItself_creditedToShizuku() {
    detector.onForegroundChanged(ShizukuExecutor.SHIZUKU_PACKAGE, "moe.shizuku.manager.MainActivity")
    val d = detector.onForegroundChanged(ShizukuExecutor.SHIZUKU_PACKAGE, ShizukuExecutor.SHIZUKU_PERMISSION_ACTIVITY)!!
    assertEquals(ShizukuExecutor.SHIZUKU_PACKAGE, d.culpritPkg)
  }

  @Test
  fun shizukuPrompt_allowedDuringMaintenance() {
    detector.privilegeRequestsAllowed = { true }
    detector.onForegroundChanged("self", null)
    assertNull(detector.onForegroundChanged(ShizukuExecutor.SHIZUKU_PACKAGE, ShizukuExecutor.SHIZUKU_PERMISSION_ACTIVITY))
  }
}
