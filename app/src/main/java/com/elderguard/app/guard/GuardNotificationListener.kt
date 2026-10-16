package com.elderguard.app.guard

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.elderguard.app.container
import com.elderguard.app.core.Trust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 可疑通知：先记录；元凶被家人标记为流氓后，自动撤掉它的此类通知 */
class GuardNotificationListener : NotificationListenerService() {
  private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private lateinit var detector: Detector
  private lateinit var responder: Responder
  /** 已记录过的通知 key → 当时的内容 */
  private val reported = HashMap<String, String?>()

  override fun onListenerConnected() {
    // 断开后系统可能重新连接，每次连接用新的作用域
    scope.cancel()
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    reported.clear()
    val c = container
    detector = Detector(c.rules).apply { selfPackage = packageName }
    responder =
      Responder(
        c.guardRepository,
        scope,
        c.rules.autoActionCooldownMs,
        isInCall = { isInCall(this) },
        // 通知监听不能画无障碍浮层，用 Toast 提示
        hint = { text -> Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() } },
      )
    scope.launch { c.guardRepository.verdicts.collect { detector.verdicts = it } }
    scope.launch { c.settings.guardMode.collect { responder.mode = it } }
    _connected.value = true
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (!::detector.isInitialized || sbn.isOngoing) return
    val n = sbn.notification
    // 分组摘要只是把同组通知折叠起来，内容在各条通知里
    if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
    val extras = n.extras
    val text =
      listOfNotNull(
          extras.getCharSequence(Notification.EXTRA_TITLE),
          extras.getCharSequence(Notification.EXTRA_TEXT),
          extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        )
        .joinToString(" ")
    val isCallOrAlarm = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM
    val pkg = sbn.packageName
    // 聊天消息是人发的，亲友一句「恭喜您」不能把微信算成可疑应用；只看全屏意图。
    // 「消息」类别、聊天样式谁都能设，只认名单里的聊天应用和系统自带的（如短信）；家人标记的流氓一律不豁免
    val messaging =
      n.category == Notification.CATEGORY_MESSAGE ||
        extras.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name
    val isChat =
      detector.verdicts[pkg] != Trust.FLAGGED &&
        (pkg in container.rules.chatPackages || messaging && container.appInfo.isPreinstalled(pkg))
    val detection = detector.onNotification(pkg, if (isChat) "" else text, n.fullScreenIntent != null, isCallOrAlarm) ?: return
    // 同一条通知刷新（进度、时间变化）会反复发来，内容没变就不重复记录；
    // 之后被家人标记为流氓的，再发来时要重新处理（撤掉）
    val seen = "${detection.autoBlockable}|${detection.detail}"
    if (reported.put(sbn.key, seen) == seen) return
    responder.handle(detection) {
      cancelNotification(sbn.key)
      true
    }
  }

  override fun onNotificationRemoved(sbn: StatusBarNotification) {
    reported.remove(sbn.key)
  }

  override fun onListenerDisconnected() {
    _connected.value = false
    scope.cancel()
  }

  companion object {
    private val _connected = MutableStateFlow(false)
    /** 本进程内是否已连上系统（设置里开着但被系统限制时为 false） */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
  }
}
