package com.elderguard.app.guard

/** 屏幕上的一个窗口；[overlay] 表示应用画在别的应用上面的悬浮窗（无障碍窗口类型 TYPE_SYSTEM） */
data class WindowSnapshot(val id: Int, val pkg: String?, val overlay: Boolean, val active: Boolean)

/**
 * 从窗口列表里找出「新弹出的悬浮窗」（纯 Kotlin，便于单元测试）。
 * 只能看到可触摸的悬浮窗：系统给无障碍服务的窗口列表不含不可触摸的窗口。
 *
 * 不记录：
 * - 服务启动时已经在的悬浮窗；
 * - 已经在屏幕上的悬浮窗被点、被拖（窗口没有新增）；
 * - 用户刚操作过这个 App 的窗口（如点开它的悬浮球展开成新窗口）；
 * - App 刚离开前台（如通话缩成小窗、导航缩成悬浮窗）；
 * - 同一个 App 短时间内重复弹出。
 */
class OverlayWatcher(private val clock: () -> Long) {
  /** 上次看到的窗口；null 表示还没有基准 */
  private var known: Set<Int>? = null
  private val lastActiveAt = HashMap<String, Long>()
  private val leftForegroundAt = HashMap<String, Long>()
  private val reportedAt = HashMap<String, Long>()

  fun onForegroundChanged(from: String?) {
    if (from != null) leftForegroundAt[from] = clock()
  }

  /** 返回新弹出悬浮窗的 App */
  fun onWindows(windows: List<WindowSnapshot>, foreground: String?): List<String> {
    val now = clock()
    val baseline = known
    known = windows.map { it.id }.toSet()
    val popped =
      if (baseline == null) {
        emptyList()
      } else {
        windows
          .filter { it.overlay && it.id !in baseline }
          .mapNotNull { it.pkg }
          .distinct()
          .filter { pkg ->
            pkg != foreground &&
              !within(lastActiveAt[pkg], now, USER_GRACE_MS) &&
              !within(leftForegroundAt[pkg], now, USER_GRACE_MS) &&
              !within(reportedAt[pkg], now, REPEAT_MS)
          }
          .onEach { reportedAt[it] = now }
      }
    // 只认原有窗口上的操作：新弹出的窗口抢到焦点不能算用户点过它
    windows.filter { it.active && (baseline == null || it.id in baseline) }.forEach { w -> w.pkg?.let { lastActiveAt[it] = now } }
    return popped
  }

  private fun within(time: Long?, now: Long, window: Long) = time != null && now - time <= window

  private companion object {
    const val USER_GRACE_MS = 2_000L
    const val REPEAT_MS = 10 * 60 * 1000L
  }
}
