package com.elderguard.app.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import com.elderguard.app.data.InstalledApp
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/** 某个应用开着的能力：[accessibility]、[notificationListener] 是它开着的那个组件，null 表示没开 */
data class AppControls(
  val overlay: Boolean,
  val accessibility: ComponentName?,
  val notificationListener: ComponentName?,
  val deviceAdmin: Boolean,
  val uid: Int,
)

/** 包名 → 名称/图标，以及判定需要的系统信息查询 */
class AppInfoResolver(private val context: Context) {
  private val pm = context.packageManager
  private val labels = ConcurrentHashMap<String, String>()
  private val activityCache = ConcurrentHashMap<String, Boolean>()

  fun label(pkg: String): String =
    labels.getOrPut(pkg) {
      runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

  private val icons = ConcurrentHashMap<String, Bitmap>()

  /** 解码图标较慢，调用方应在后台线程调用；结果按包名+尺寸缓存 */
  fun icon(pkg: String, sizePx: Int): Bitmap? = cachedIcon(pkg, sizePx)
    ?: runCatching { pm.getApplicationIcon(pkg).toBitmap(sizePx, sizePx) }.getOrNull()?.also { icons["$pkg@$sizePx"] = it }

  /** 只查缓存，可在主线程调用 */
  fun cachedIcon(pkg: String, sizePx: Int): Bitmap? = icons["$pkg@$sizePx"]

  fun isInstalled(pkg: String): Boolean = runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess

  private val preinstalled = ConcurrentHashMap<String, Boolean>()

  /** 厂商预装（含更新过的预装）：它们的悬浮按钮、悬浮球属于系统功能 */
  fun isPreinstalled(pkg: String): Boolean =
    preinstalled.getOrPut(pkg) { runCatching { pm.getApplicationInfo(pkg, 0).isSystem() }.getOrDefault(false) }

  /** [pkg] 的安装记录；时间用系统记的首次安装时间，取不到时用 [fallbackTime] */
  fun installRecord(pkg: String, fallbackTime: Long = System.currentTimeMillis()): InstalledApp {
    val time = runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrNull()?.takeIf { it > 0 } ?: fallbackTime
    if (Build.VERSION.SDK_INT < 30) {
      @Suppress("DEPRECATION") val installer = runCatching { pm.getInstallerPackageName(pkg) }.getOrNull()
      return InstalledApp(pkg, time, installer, null)
    }
    val info = runCatching { pm.getInstallSourceInfo(pkg) }.getOrNull()
    val source = if (Build.VERSION.SDK_INT >= 33) info?.packageSource else null
    return InstalledApp(pkg, time, info?.installingPackageName, source)
  }

  /** [since] 之后首次安装的用户应用（不含系统应用和本 App），按系统记的首次安装时间 */
  fun installedSince(since: Long): List<InstalledApp> =
    // 没有「读取应用列表」权限时读了也只有自己，还会在后台弹系统授权框
    if (!AppListPermission.allowed(context)) emptyList()
    else runCatching { pm.getInstalledPackages(0) }
      .getOrDefault(emptyList())
      .filter { it.packageName != context.packageName && it.applicationInfo?.isSystem() == false && it.firstInstallTime >= since }
      .map { installRecord(it.packageName, it.firstInstallTime) }

  /** 给家人看的安装来源：安装包文件、下载的安装包，或发起安装的应用（商店、浏览器…）；系统没记录时为 null */
  fun sourceLabel(app: InstalledApp): String? =
    when {
      app.source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> "安装包文件"
      app.source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> "下载的安装包"
      app.installer != null -> label(app.installer)
      else -> null
    }

  /** [pkg] 当前开着的、家人可以去系统设置里关掉的能力；决定应用面板里列出哪些跳转 */
  fun controlsOf(pkg: String): AppControls {
    val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()
    val requestsOverlay = info?.requestedPermissions.orEmpty().contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW)
    val uid = info?.applicationInfo?.uid ?: -1
    // 悬浮窗权限是否真的开着；读不到时只要申请了就列出来
    val overlayOn =
      requestsOverlay &&
        (Build.VERSION.SDK_INT < 29 ||
          runCatching {
              context.getSystemService(android.app.AppOpsManager::class.java)
                .unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg)
            }
            .map { it == android.app.AppOpsManager.MODE_ALLOWED }
            .getOrDefault(true))
    fun enabled(key: String) =
      runCatching { Settings.Secure.getString(context.contentResolver, key) }
        .getOrNull()
        .orEmpty()
        .split(':')
        .mapNotNull { ComponentName.unflattenFromString(it) }
        .firstOrNull { it.packageName == pkg }
    val admin =
      runCatching { context.getSystemService(android.app.admin.DevicePolicyManager::class.java).activeAdmins.orEmpty() }
        .getOrDefault(emptyList())
        .any { it.packageName == pkg }
    return AppControls(
      overlay = overlayOn,
      accessibility = enabled(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
      notificationListener = enabled("enabled_notification_listeners"),
      deviceAdmin = admin,
      uid = uid,
    )
  }

  /** 被维护模式冻结（pm disable-user） */
  fun isFrozen(pkg: String): Boolean = runCatching { !pm.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)

  /** 窗口切换事件里的 className 是否真的是 Activity（过滤对话框、Toast、悬浮层） */
  fun isActivity(pkg: String, cls: String): Boolean =
    activityCache.getOrPut("$pkg/$cls") {
      runCatching { pm.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
    }

  /**
   * 桌面：系统自带的桌面 + 用户设为默认的桌面。
   * 不能把所有声明 HOME 的应用都算进来：锁屏广告类应用常声明 HOME 来拦主页键，算进来就会被整个放过、体检也查不到；
   * 设置里开机用的 FallbackHome（优先级为负）也不算
   */
  fun homePackages(): Set<String> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val default = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
      .filter { it.activityInfo.packageName == default || it.priority >= 0 && it.activityInfo.applicationInfo.isSystem() }
      .map { it.activityInfo.packageName }
      .toSet()
  }

  private fun ApplicationInfo.isSystem() = flags and ApplicationInfo.FLAG_SYSTEM != 0

  fun systemPackages(): Set<String> {
    val homes = homePackages()
    val imes = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
    val defaultIme =
      Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.let { ComponentName.unflattenFromString(it)?.packageName }
    return (homes + imes + listOfNotNull(defaultIme)).toSet()
  }
}
