package com.elderguard.app.guard

import android.os.SystemClock
import android.util.Log
import com.elderguard.app.core.Detection
import com.elderguard.app.core.EventType
import com.elderguard.app.core.GuardMode
import com.elderguard.app.data.GuardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 处理一次骚扰事件：按守护方式决定是否动手，然后记录。
 * [act] 由调用方提供实际动作（无障碍服务执行「返回」，通知监听撤掉通知）。
 */
class Responder(
  private val repository: GuardRepository,
  private val scope: CoroutineScope,
  private val cooldownMs: Long,
  /** 通话中（含微信等网络通话）不动手，避免挂断或打断 */
  private val isInCall: () -> Boolean,
  /** 给长辈的提示 */
  private val hint: (String) -> Unit,
) {
  @Volatile var mode: GuardMode = GuardMode.AUTO
  private val lastActionAt = HashMap<String, Long>()
  private var lastHintAt = Long.MIN_VALUE / 2

  fun handle(detection: Detection, act: () -> Boolean) {
    val now = SystemClock.elapsedRealtime()
    val shouldAct =
      mode == GuardMode.AUTO &&
        detection.autoBlockable &&
        now - (lastActionAt[detection.culpritPkg] ?: Long.MIN_VALUE / 2) >= cooldownMs &&
        !isInCall()
    val handled = shouldAct && act()
    Log.i("ElderGuard", "处理：${detection.culpritPkg} mode=$mode autoBlockable=${detection.autoBlockable} handled=$handled")
    if (handled) {
      lastActionAt[detection.culpritPkg] = now
      if (now - lastHintAt >= HINT_INTERVAL_MS) {
        lastHintAt = now
        hint(if (detection.type == EventType.SUSPICIOUS_NOTIFICATION) "已拦下一条可疑通知" else "已帮您挡掉一个广告")
      }
    }
    scope.launch { repository.record(detection, handled) }
  }

  private companion object {
    const val HINT_INTERVAL_MS = 60_000L
  }
}
