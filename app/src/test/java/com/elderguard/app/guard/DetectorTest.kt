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
  private var now = 100_000L
  private val rules =
    GuardRules(
      version = 1,
      interactionWindowMs = 2_500,
      unlockWindowMs = 8_000,
      autoActionCooldownMs = 10_000,
      quickAppEngines = setOf("com.miui.hybrid"),
      appStores = setOf("com.xiaomi.market"),
      protectedPackages = setOf("com.android.systemui", "com.android.dialer"),
      suspiciousKeywords = listOf("病毒", "中奖"),
    )
  private lateinit var detector: Detector

  @Before
  fun setUp() {
    detector = Detector(rules) { now }
    detector.systemPackages = setOf("launcher")
    detector.selfPackage = "self"
  }

  private fun advance(ms: Long) {
    now += ms
  }

  @Test
  fun userClickThenSwitch_isNotDetected() {
    detector.onForegroundChanged("launcher", null)
    detector.onUserInteraction()
    advance(500)
    assertNull(detector.onForegroundChanged("com.tencent.mm", null))
  }

  @Test
  fun switchWithoutClick_isUnsolicitedAndCulpritIsTarget() {
    detector.onForegroundChanged("launcher", null)
    advance(60_000)
    val d = detector.onForegroundChanged("com.ad.cleaner", "AdActivity")!!
    assertEquals(EventType.UNSOLICITED_LAUNCH, d.type)
    assertEquals("com.ad.cleaner", d.culpritPkg)
    assertFalse("未标记的 App 只记录不处理", d.autoBlockable)
  }

  @Test
  fun flaggedApp_isAutoBlockable() {
    detector.verdicts = mapOf("com.ad.cleaner" to Trust.FLAGGED)
    detector.onForegroundChanged("launcher", null)
    advance(60_000)
    assertTrue(detector.onForegroundChanged("com.ad.cleaner", null)!!.autoBlockable)
  }

  @Test
  fun quickAppWithoutClick_culpritIsPreviousApp() {
    detector.onForegroundChanged("com.news.app", null)
    advance(10_000)
    val d = detector.onForegroundChanged("com.miui.hybrid", null)!!
    assertEquals(EventType.QUICK_APP_LAUNCH, d.type)
    assertEquals("com.news.app", d.culpritPkg)
    assertTrue(d.autoBlockable)
  }

  @Test
  fun storeRedirectAfterSplashShake_isDetected() {
    detector.onForegroundChanged("launcher", null)
    detector.onUserInteraction() // 点了图标打开 App
    advance(300)
    assertNull(detector.onForegroundChanged("com.video.app", null))
    advance(4_000) // 开屏广告期间手一晃
    val d = detector.onForegroundChanged("com.xiaomi.market", null)!!
    assertEquals(EventType.STORE_REDIRECT, d.type)
    assertEquals("com.video.app", d.culpritPkg)
  }

  @Test
  fun popupRightAfterUnlock_isUnlockPopup() {
    detector.onForegroundChanged("launcher", null)
    advance(60_000)
    detector.onScreenUnlocked()
    advance(1_000)
    assertEquals(EventType.UNLOCK_POPUP, detector.onForegroundChanged("com.ad.wifi", null)!!.type)
  }

  @Test
  fun backToPreviousApp_isNotDetected() {
    detector.onForegroundChanged("com.tencent.mm", null)
    detector.onUserInteraction()
    advance(500)
    detector.onForegroundChanged("com.android.browser", null)
    advance(30_000) // 按返回键，没有点击事件
    assertNull(detector.onForegroundChanged("com.tencent.mm", null))
  }

  @Test
  fun popupAgainAfterBeingSentHome_isStillDetected() {
    detector.onForegroundChanged("launcher", null)
    advance(60_000)
    assertEquals(EventType.UNSOLICITED_LAUNCH, detector.onForegroundChanged("com.ad.cleaner", null)!!.type)
    advance(800) // 守护执行回桌面
    assertNull(detector.onForegroundChanged("launcher", null))
    advance(60_000) // 它又自己弹出来：此时它是「上一个 App」，不能当成用户按返回回到它
    assertEquals(EventType.UNSOLICITED_LAUNCH, detector.onForegroundChanged("com.ad.cleaner", null)!!.type)
  }

  @Test
  fun protectedAndTrustedPackages_areExempt() {
    detector.verdicts = mapOf("com.bank" to Trust.TRUSTED)
    detector.onForegroundChanged("launcher", null)
    advance(60_000)
    assertNull(detector.onForegroundChanged("com.android.dialer", null))
    advance(60_000)
    assertNull(detector.onForegroundChanged("com.bank", null))
  }

  @Test
  fun suspiciousNotification_matchesKeywordOrFullScreen() {
    assertEquals(EventType.SUSPICIOUS_NOTIFICATION, detector.onNotification("com.browser", "您的手机中病毒了", false, false)!!.type)
    assertEquals(EventType.SUSPICIOUS_NOTIFICATION, detector.onNotification("com.ad", "看看", true, false)!!.type)
    assertNull(detector.onNotification("com.clock", "闹钟", true, true))
    assertNull(detector.onNotification("com.tencent.mm", "晚上吃什么", false, false))
  }

  @Test
  fun shizukuPermissionDialog_isBlockedEvenRightAfterClick() {
    detector.verdicts = mapOf("com.trusted.tool" to Trust.TRUSTED)
    detector.onForegroundChanged("launcher", null)
    detector.onForegroundChanged("com.trusted.tool", null)
    detector.onUserInteraction()
    val d = detector.onForegroundChanged(ShizukuExecutor.SHIZUKU_PACKAGE, ShizukuExecutor.SHIZUKU_PERMISSION_ACTIVITY)!!
    assertEquals(EventType.PRIVILEGE_REQUEST, d.type)
    assertEquals("发起请求的 App 即使被信任也要记为元凶", "com.trusted.tool", d.culpritPkg)
    assertTrue(d.autoBlockable)
  }

  @Test
  fun shizukuPermissionDialog_allowedDuringMaintenance() {
    detector.privilegeRequestsAllowed = { true }
    detector.onForegroundChanged("launcher", null)
    detector.onForegroundChanged("self", null)
    assertNull(detector.onForegroundChanged(ShizukuExecutor.SHIZUKU_PACKAGE, ShizukuExecutor.SHIZUKU_PERMISSION_ACTIVITY))
  }
}
