package com.elderguard.app.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OverlayWatcherTest {
  private var now = 100_000L
  private lateinit var watcher: OverlayWatcher

  private val launcher = WindowSnapshot(1, "launcher", overlay = false, active = true)

  @Before
  fun setUp() {
    watcher = OverlayWatcher { now }
    // 基准：服务启动时只有桌面
    watcher.onWindows(listOf(launcher), "launcher")
    now += 60_000
  }

  private fun overlay(id: Int, pkg: String, active: Boolean = false) = WindowSnapshot(id, pkg, overlay = true, active = active)

  @Test
  fun newOverlayFromBackgroundApp_isReported() {
    assertEquals(listOf("com.ad"), watcher.onWindows(listOf(launcher, overlay(2, "com.ad")), "launcher"))
  }

  @Test
  fun overlaysPresentAtStart_areIgnored() {
    val w = OverlayWatcher { now }
    assertTrue(w.onWindows(listOf(launcher, overlay(2, "com.ad")), "launcher").isEmpty())
    // 同一个窗口之后的变化（拖动、点击）也不算新弹出
    now += 60_000
    assertTrue(w.onWindows(listOf(launcher, overlay(2, "com.ad", active = true)), "launcher").isEmpty())
  }

  @Test
  fun overlayOfForegroundApp_isIgnored() {
    assertTrue(watcher.onWindows(listOf(launcher, overlay(2, "com.app")), "com.app").isEmpty())
  }

  @Test
  fun overlayRightAfterLeavingForeground_isIgnored() {
    // 如通话中按主页键，通话缩成小窗
    watcher.onForegroundChanged("com.call")
    now += 1_000
    assertTrue(watcher.onWindows(listOf(launcher, overlay(2, "com.call")), "launcher").isEmpty())
  }

  @Test
  fun overlayLongAfterLeavingForeground_isReported() {
    watcher.onForegroundChanged("com.ad")
    now += 30_000
    assertEquals(listOf("com.ad"), watcher.onWindows(listOf(launcher, overlay(2, "com.ad")), "launcher"))
  }

  @Test
  fun overlayAfterUserTouchedSameAppsWindow_isIgnored() {
    // 用户点了它的悬浮球（悬浮球成为当前操作的窗口），随后展开成新窗口
    watcher.onWindows(listOf(launcher.copy(active = false), overlay(2, "com.ball")), "launcher")
    now += 60_000
    watcher.onWindows(listOf(launcher.copy(active = false), overlay(2, "com.ball", active = true)), "launcher")
    now += 500
    assertTrue(watcher.onWindows(listOf(launcher, overlay(2, "com.ball"), overlay(3, "com.ball")), "launcher").isEmpty())
  }

  @Test
  fun newOverlayTakingFocus_doesNotExemptItself() {
    assertEquals(listOf("com.ad"), watcher.onWindows(listOf(launcher.copy(active = false), overlay(2, "com.ad", active = true)), "launcher"))
  }

  @Test
  fun repeatedPopups_reportedOncePerTenMinutes() {
    assertEquals(listOf("com.ad"), watcher.onWindows(listOf(launcher, overlay(2, "com.ad")), "launcher"))
    watcher.onWindows(listOf(launcher), "launcher")
    now += 60_000
    assertTrue(watcher.onWindows(listOf(launcher, overlay(3, "com.ad")), "launcher").isEmpty())
    watcher.onWindows(listOf(launcher), "launcher")
    now += 10 * 60_000
    assertEquals(listOf("com.ad"), watcher.onWindows(listOf(launcher, overlay(4, "com.ad")), "launcher"))
  }

  @Test
  fun emptyListAtStart_isNotTakenAsBaseline() {
    // 服务刚连上时窗口列表可能还是空的
    val w = OverlayWatcher { now }
    assertTrue(w.onWindows(emptyList(), null).isEmpty())
    assertTrue(w.onWindows(listOf(launcher, overlay(2, "com.ball")), "launcher").isEmpty())
    now += 60_000
    assertTrue(w.onWindows(listOf(launcher, overlay(2, "com.ball")), "launcher").isEmpty())
  }

  @Test
  fun overlayHiddenThenShownAgain_isNotNew() {
    // 系统设置在前台时会藏起别的应用的悬浮窗，回到桌面时同一个窗口重新出现
    assertEquals(listOf("com.ad"), watcher.onWindows(listOf(launcher, overlay(2, "com.ad")), "launcher"))
    now += 11 * 60_000
    watcher.onWindows(listOf(WindowSnapshot(5, "settings", overlay = false, active = true)), "settings")
    now += 5_000
    assertTrue(watcher.onWindows(listOf(launcher, overlay(2, "com.ad")), "launcher").isEmpty())
  }

  @Test
  fun overlayShowingUpRightAfterStart_isTreatedAsPresentAtStart() {
    // 启动时的窗口列表里还没有它，过一会儿才出现
    val w = OverlayWatcher { now }
    w.onWindows(listOf(launcher), "launcher")
    now += 500
    assertTrue(w.onWindows(listOf(launcher, overlay(2, "com.ball")), "launcher").isEmpty())
    now += 60_000
    assertTrue(w.onWindows(listOf(launcher, overlay(2, "com.ball")), "launcher").isEmpty())
  }

  @Test
  fun overlayPresentAtStartButUnrecognized_isNotReportedLater() {
    val w = OverlayWatcher { now }
    w.onWindows(listOf(launcher, WindowSnapshot(2, null, overlay = true, active = false)), "launcher")
    now += 300
    w.onWindows(listOf(launcher, WindowSnapshot(2, null, overlay = true, active = false)), "launcher")
    now += 60_000
    assertTrue(w.onWindows(listOf(launcher, overlay(2, "com.ad")), "launcher").isEmpty())
  }

  @Test
  fun overlayRecognizedLater_isStillReported() {
    // 刚弹出时还读不到所属 App，下一次窗口变化才认出来
    assertTrue(watcher.onWindows(listOf(launcher, WindowSnapshot(2, null, overlay = true, active = false)), "launcher").isEmpty())
    now += 300
    assertEquals(listOf("com.ad"), watcher.onWindows(listOf(launcher, overlay(2, "com.ad")), "launcher"))
  }

  @Test
  fun nonOverlayWindowsAndUnknownPackages_areIgnored() {
    val dialog = WindowSnapshot(2, "com.app", overlay = false, active = false)
    val unknown = WindowSnapshot(3, null, overlay = true, active = false)
    assertTrue(watcher.onWindows(listOf(launcher, dialog, unknown), "launcher").isEmpty())
  }
}
