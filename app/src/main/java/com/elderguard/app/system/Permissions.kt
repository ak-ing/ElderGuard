package com.elderguard.app.system

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.elderguard.app.guard.GuardAccessibilityService

enum class PermissionKind(val title: String, val reason: String, val required: Boolean) {
  ACCESSIBILITY("无障碍服务", "发现弹窗、识别元凶、自动挡掉，核心能力", required = true),
  USAGE_ACCESS("使用情况访问", "查看 App 前后台切换记录，让元凶判断更准", required = false),
  NOTIFICATION_LISTENER("通知使用权", "发现假病毒、假中奖之类的通知", required = false),
  POST_NOTIFICATIONS("发送通知", "守护停止时提醒家人", required = false),
  BATTERY("不限制耗电", "防止系统省电时把守护关掉", required = false),
  AUTOSTART("允许自启动", "部分手机（小米/华为/OPPO/vivo）需要，无法自动检测", required = false),
}

/** null 表示无法检测（如厂商自启动），界面上显示为「请确认」 */
data class PermissionState(val kind: PermissionKind, val granted: Boolean?)

class PermissionChecker(private val context: Context) {
  fun snapshot(): List<PermissionState> = PermissionKind.entries.map { PermissionState(it, isGranted(it)) }

  fun isGranted(kind: PermissionKind): Boolean? =
    when (kind) {
      PermissionKind.ACCESSIBILITY -> isAccessibilityEnabled()
      PermissionKind.USAGE_ACCESS -> hasUsageAccess()
      PermissionKind.NOTIFICATION_LISTENER ->
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
      PermissionKind.POST_NOTIFICATIONS ->
        Build.VERSION.SDK_INT < 33 ||
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
      PermissionKind.BATTERY ->
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
      PermissionKind.AUTOSTART -> null
    }

  fun isAccessibilityEnabled(): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val self = ComponentName(context, GuardAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
  }

  private fun hasUsageAccess(): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    val mode =
      if (Build.VERSION.SDK_INT >= 29) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
      } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
      }
    return mode == AppOpsManager.MODE_ALLOWED
  }
}

/** 基础模式下所有「处理」都落到这里：打开对应的系统设置页，由人来点 */
object SettingsNavigator {
  fun open(context: Context, kind: PermissionKind) {
    val pkgUri = Uri.fromParts("package", context.packageName, null)
    val candidates =
      when (kind) {
        PermissionKind.ACCESSIBILITY ->
          buildList {
            if (Build.VERSION.SDK_INT >= 33) {
              // 直达本服务的开关页；隐藏常量，部分 ROM 不支持时回退到无障碍总列表
              add(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                  .putExtra(
                    Intent.EXTRA_COMPONENT_NAME,
                    ComponentName(context, GuardAccessibilityService::class.java).flattenToString(),
                  )
              )
            }
            add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
          }
        PermissionKind.USAGE_ACCESS ->
          listOf(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        PermissionKind.NOTIFICATION_LISTENER -> listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        PermissionKind.POST_NOTIFICATIONS ->
          listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        PermissionKind.BATTERY ->
          listOf(
            @Suppress("BatteryLife") Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
          )
        PermissionKind.AUTOSTART -> oemAutostartIntents()
      } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
    startFirst(context, candidates)
  }

  /** 打开某个 App 的系统详情页（卸载、关通知、关悬浮窗都从这里进） */
  fun openAppDetails(context: Context, pkg: String) {
    startFirst(context, listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))))
  }

  fun requestUninstall(context: Context, pkg: String) {
    startFirst(context, listOf(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))))
  }

  /** 各厂商自启动管理页，组件名来自社区经验，需要真机校准 */
  private fun oemAutostartIntents(): List<Intent> =
    listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
      )
      .map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }

  private fun startFirst(context: Context, intents: List<Intent>) {
    for (intent in intents) {
      try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
      } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
    }
  }
}
