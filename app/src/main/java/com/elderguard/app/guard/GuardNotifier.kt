package com.elderguard.app.guard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.elderguard.app.MainActivity
import com.elderguard.app.R

/** 守护状态通知：守护停止时提醒，恢复后自动撤掉 */
object GuardNotifier {
  private const val CHANNEL_ID = "guard_status"
  private const val ID_STOPPED = 1

  fun notifyStopped(context: Context) {
    val nm = NotificationManagerCompat.from(context)
    val permitted =
      Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!permitted || !nm.areNotificationsEnabled()) return
    ensureChannel(context)
    val open =
      PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_shield_check)
        .setContentTitle("手机守护已停止")
        .setContentText("广告不会再被挡掉，请让家人帮忙重新打开")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(open)
        .build()
    // 检查之后用户仍可能在设置里收回权限
    try {
      nm.notify(ID_STOPPED, notification)
    } catch (_: SecurityException) {}
  }

  fun cancelStopped(context: Context) {
    NotificationManagerCompat.from(context).cancel(ID_STOPPED)
  }

  private fun ensureChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "守护状态", NotificationManager.IMPORTANCE_HIGH).apply {
        description = "守护被关闭时提醒"
      }
    )
  }
}
