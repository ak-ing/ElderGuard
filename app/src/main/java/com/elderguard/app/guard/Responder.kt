package com.elderguard.app.guard

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.elderguard.app.core.Detection
import com.elderguard.app.core.GuardMode
import com.elderguard.app.data.GuardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 处理一次骚扰事件：按守护方式决定是否动手，然后记录。
 * [act] 由调用方提供实际动作（无障碍服务执行「返回」，通知监听撤掉通知）。
 */
class Responder(
  private val context: Context,
  private val repository: GuardRepository,
  private val scope: CoroutineScope,
  private val cooldownMs: Long,
) {
  @Volatile var mode: GuardMode = GuardMode.AUTO
  private val lastActionAt = HashMap<String, Long>()
  private var lastHintAt = Long.MIN_VALUE / 2

  fun handle(detection: Detection, act: () -> Boolean) {
    val now = SystemClock.elapsedRealtime()
    val shouldAct =
      mode == GuardMode.AUTO &&
        detection.autoBlockable &&
        now - (lastActionAt[detection.culpritPkg] ?: Long.MIN_VALUE / 2) >= cooldownMs
    val handled = shouldAct && act()
    if (handled) {
      lastActionAt[detection.culpritPkg] = now
      hintElder(now)
    }
    scope.launch { repository.record(detection, handled) }
  }

  /** 给长辈的提示：不需要操作，每分钟最多一次 */
  private fun hintElder(now: Long) {
    if (now - lastHintAt < HINT_INTERVAL_MS) return
    lastHintAt = now
    Toast.makeText(context, "刚帮您挡掉一个广告", Toast.LENGTH_SHORT).show()
  }

  private companion object {
    const val HINT_INTERVAL_MS = 60_000L
  }
}
