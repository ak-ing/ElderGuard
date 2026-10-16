package com.elderguard.app.system

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.elderguard.app.guard.GuardAccessibilityService

/** 权限页上的分组 */
enum class PermissionTier(val title: String) {
  REQUIRED("需要开启"),
  RECOMMENDED("建议开启"),
  /** 守护由系统直接托管，这几项只在部分厂商系统上有用 */
  IF_STOPPED("守护经常被关掉时再开"),
}

/** [reason] 写清开了能做什么、不开会少什么 */
enum class PermissionKind(val title: String, val reason: String, val tier: PermissionTier) {
  ACCESSIBILITY("无障碍服务", "挡掉快应用和流氓应用、发现悬浮窗、记下新装的应用。不开守护就不工作", PermissionTier.REQUIRED),
  /** 国内厂商系统（ColorOS、MIUI 等）另加的权限；原生系统没有，不显示 */
  APP_LIST("读取应用列表", "看到手机里装了哪些应用：体检、新装的应用都靠它。不开就查不到流氓应用", PermissionTier.REQUIRED),
  NOTIFICATION_LISTENER("通知使用权", "发现全屏弹出、假病毒、假中奖的通知。不开就少了通知这一块", PermissionTier.RECOMMENDED),
  POST_NOTIFICATIONS("发送通知", "守护被关掉时在这台手机上提醒。不开就没人知道守护停了", PermissionTier.RECOMMENDED),
  BATTERY("不限制耗电", "系统清理后台时不再优先关掉守护", PermissionTier.IF_STOPPED),
  AUTOSTART("允许自启动", "小米、华为、vivo 等重启后可能需要；无法检测是否已开", PermissionTier.IF_STOPPED),
  ;

  val required: Boolean
    get() = tier == PermissionTier.REQUIRED
}

/** OPPO 系（ColorOS）：开启无障碍后系统自动给自启动权限，不受自启动管理影响 */
val isColorOs: Boolean
  get() = Build.MANUFACTURER.lowercase() in setOf("oppo", "oneplus", "realme")

/** null 表示无法检测（如厂商自启动），界面上显示为「请确认」 */
data class PermissionState(val kind: PermissionKind, val granted: Boolean?)

/**
 * 「读取应用列表」：团体标准 T/TAF 108-2022 规定的厂商权限。系统支持时，没授权的应用读已装应用列表
 * 只能拿到系统应用和自己，而且一读就弹系统授权框——所以没授权前不去读，免得框在后台莫名弹出来
 */
object AppListPermission {
  const val NAME = "com.android.permission.GET_INSTALLED_APPS"

  /** 系统有这个权限：标准里的开关为 1，或者系统定义了这个权限名 */
  fun supported(context: Context): Boolean =
    runCatching { Settings.Secure.getInt(context.contentResolver, "oem_installed_apps_runtime_permission_enable") == 1 }
      .getOrDefault(false) || runCatching { context.packageManager.getPermissionInfo(NAME, 0) }.isSuccess

  /** 能读完整的应用列表：系统没有这个权限，或者已经授权 */
  fun allowed(context: Context): Boolean =
    !supported(context) || ContextCompat.checkSelfPermission(context, NAME) == PackageManager.PERMISSION_GRANTED
}

class PermissionChecker(private val context: Context) {
  /** 系统没有的权限（如原生系统上的「读取应用列表」）不列出来 */
  fun snapshot(): List<PermissionState> =
    PermissionKind.entries
      .filter { it != PermissionKind.APP_LIST || AppListPermission.supported(context) }
      .map { PermissionState(it, isGranted(it)) }

  fun isGranted(kind: PermissionKind): Boolean? =
    when (kind) {
      PermissionKind.ACCESSIBILITY -> isAccessibilityEnabled()
      PermissionKind.APP_LIST -> AppListPermission.allowed(context)
      PermissionKind.NOTIFICATION_LISTENER ->
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
      PermissionKind.POST_NOTIFICATIONS ->
        Build.VERSION.SDK_INT < 33 ||
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
      PermissionKind.BATTERY ->
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
      PermissionKind.AUTOSTART -> if (isColorOs && isAccessibilityEnabled()) true else null
    }

  /** 安装来源说明，写进自检报告：Android 13 起从文件安装的 App 开无障碍会被系统先拦一次 */
  fun installSourceLabel(): String {
    if (Build.VERSION.SDK_INT < 33) return "无法检测（Android 13 以下）"
    val info = runCatching { context.packageManager.getInstallSourceInfo(context.packageName) }.getOrNull() ?: return "无法检测"
    val source =
      when (info.packageSource) {
        PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> "本地文件"
        PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> "下载的文件"
        PackageInstaller.PACKAGE_SOURCE_STORE -> "应用商店"
        PackageInstaller.PACKAGE_SOURCE_OTHER -> "其他"
        else -> "未指定"
      }
    return "$source（安装器 ${info.installingPackageName ?: "无"}）"
  }

  fun isAccessibilityEnabled(): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val self = ComponentName(context, GuardAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
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
        PermissionKind.NOTIFICATION_LISTENER -> listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        // 拒绝过以后系统不再弹授权框，只能去本应用的权限页里打开（落到末尾的应用详情页）
        PermissionKind.APP_LIST -> emptyList()
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

  // 下面这些跳转都尽量直达「这个应用」的那一个开关；系统不让进时依次退到列表页、应用详情页

  /** [pkg] 的「显示在其他应用上层」开关 */
  fun openOverlay(context: Context, pkg: String) {
    val uri = Uri.fromParts("package", pkg, null)
    startFirst(
      context,
      listOf(
        // 隐藏入口，直达单个应用；部分系统只许系统应用用，会抛 SecurityException 后退回
        Intent("android.settings.MANAGE_APP_OVERLAY_PERMISSION", uri),
        // Android 11 起只进列表，家人在列表里找到它
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
      ),
    )
  }

  /** [pkg] 的通知设置页 */
  fun openNotifications(context: Context, pkg: String, uid: Int) {
    startFirst(
      context,
      listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
          // 部分厂商系统还读旧的两个参数
          .putExtra("app_package", pkg)
          .putExtra("app_uid", uid),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)),
      ),
    )
  }

  /** [service] 这个无障碍服务的开关页 */
  fun openAccessibilityService(context: Context, service: ComponentName) {
    startFirst(
      context,
      buildList {
        if (Build.VERSION.SDK_INT >= 31) {
          add(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, service.flattenToString()))
        }
        add(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
      },
    )
  }

  /** [listener] 这个「通知使用权」的开关页 */
  fun openNotificationListener(context: Context, listener: ComponentName) {
    startFirst(
      context,
      buildList {
        if (Build.VERSION.SDK_INT >= 30) {
          add(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
              .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
          )
        }
        add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
      },
    )
  }

  /** 设备管理器列表：要先在这里取消它，才能卸载 */
  fun openDeviceAdmins(context: Context) {
    startFirst(
      context,
      listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.Settings\$DeviceAdminSettingsActivity"),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
      ),
    )
  }

  /** 各厂商自启动管理页，组件名来自社区经验，需要真机校准 */
  private fun oemAutostartIntents(): List<Intent> =
    listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.oplus.battery" to "com.oplus.startupapp.view.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
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
