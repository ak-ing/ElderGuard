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
  /**
   * 见过的窗口 id。不能只和上一次的列表比：系统设置等页面会暂时藏起别的应用的悬浮窗，
   * 回到桌面时同一个窗口（id 不变）重新出现，不是新弹出的。窗口 id 只增不复用，只留最近的一批
   */
  private val seen = LinkedHashSet<Int>()
  /** 第一次拿到窗口列表的时间；null 表示还没有基准 */
  private var baselineAt: Long? = null
  private val lastActiveAt = HashMap<String, Long>()
  private val leftForegroundAt = HashMap<String, Long>()
  private val reportedAt = HashMap<String, Long>()

  fun onForegroundChanged(from: String?) {
    if (from != null) leftForegroundAt[from] = clock()
  }

  /** 返回新弹出悬浮窗的 App */
  fun onWindows(windows: List<WindowSnapshot>, foreground: String?): List<String> {
    val now = clock()
    // 服务刚连上时系统给的窗口列表可能还是空的，屏幕上总有窗口，空列表不能当基准
    val startedAt = baselineAt ?: if (windows.isEmpty()) return emptyList() else now.also { baselineAt = it }
    // 服务刚启动的一小会儿里窗口列表还没稳定（启动时就在的窗口可能晚一步才出现），这期间出现的都算启动时就在
    val starting = now - startedAt <= STARTUP_GRACE_MS
    val before = seen.toSet()
    // 新出现、还不知道属于谁的悬浮窗先不算见过，等认出来时再当作新窗口判断
    // 还在屏幕上的移到末尾，淘汰时先淘汰早已关掉的
    windows.filter { starting || !(it.overlay && it.pkg == null) }.forEach {
      seen.remove(it.id)
      seen.add(it.id)
    }
    prune(now)
    val popped =
      if (starting) {
        emptyList()
      } else {
        windows
          .filter { it.overlay && it.id !in before }
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
    windows.filter { it.active && (starting || it.id in before) }.forEach { w -> w.pkg?.let { lastActiveAt[it] = now } }
    return popped
  }

  /** 过了判断时限的记录没有用了，删掉，免得服务长期运行时越积越多 */
  private fun prune(now: Long) {
    lastActiveAt.values.removeAll { now - it > USER_GRACE_MS }
    leftForegroundAt.values.removeAll { now - it > USER_GRACE_MS }
    reportedAt.values.removeAll { now - it > REPEAT_MS }
    while (seen.size > MAX_SEEN) seen.remove(seen.first())
  }

  private fun within(time: Long?, now: Long, window: Long) = time != null && now - time <= window

  private companion object {
    const val USER_GRACE_MS = 2_000L
    const val STARTUP_GRACE_MS = 3_000L
    const val REPEAT_MS = 10 * 60 * 1000L
    const val MAX_SEEN = 512
  }
}
