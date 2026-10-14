package com.elderguard.app.guard

import android.app.Notification
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.elderguard.app.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 可疑通知：先记录；元凶被家人标记为流氓后，自动撤掉它的此类通知 */
class GuardNotificationListener : NotificationListenerService() {
  private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private lateinit var detector: Detector
  private lateinit var responder: Responder

  override fun onListenerConnected() {
    // 断开后系统可能重新连接，每次连接用新的作用域
    scope.cancel()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val c = container
    detector = Detector(c.rules, SystemClock::elapsedRealtime).apply { selfPackage = packageName }
    responder = Responder(applicationContext, c.guardRepository, scope, c.rules.autoActionCooldownMs)
    scope.launch { c.guardRepository.verdicts.collect { detector.verdicts = it } }
    scope.launch { c.settings.guardMode.collect { responder.mode = it } }
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (!::detector.isInitialized || sbn.isOngoing) return
    val n = sbn.notification
    val extras = n.extras
    val text =
      listOfNotNull(
          extras.getCharSequence(Notification.EXTRA_TITLE),
          extras.getCharSequence(Notification.EXTRA_TEXT),
          extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        )
        .joinToString(" ")
    val isCallOrAlarm = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM
    val detection = detector.onNotification(sbn.packageName, text, n.fullScreenIntent != null, isCallOrAlarm) ?: return
    responder.handle(detection) {
      cancelNotification(sbn.key)
      true
    }
  }

  override fun onListenerDisconnected() {
    scope.cancel()
  }
}
