package com.elderguard.app.scan

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.elderguard.app.core.GuardRules
import com.elderguard.app.core.Trust
import com.elderguard.app.data.GuardRepository
import com.elderguard.app.system.AppInfoResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 风险信号。[strong] 的信号单独出现就值得家人看一眼；弱信号（悬浮窗、开机自启等）微信这类正常应用也有，
 * 只有和强信号同时出现才列出来，避免体检结果被正常应用淹没。
 */
enum class RiskSignal(val title: String, val detail: String, val weight: Int, val strong: Boolean) {
  HARASSMENT("近 7 天有守护记录", "弹过悬浮窗、发过可疑通知，或用它时出现过快应用", 6, true),
  ACCESSIBILITY("开着无障碍服务", "能读取屏幕内容、代替长辈点击", 5, true),
  DEVICE_ADMIN("是设备管理器", "取消设备管理器之前无法卸载", 5, true),
  HIDDEN_ICON("桌面上没有图标", "装在手机里却看不到，难以发现和卸载", 4, true),
  TOOL_NAME("名字像清理加速类工具", "这类应用常带弹窗广告", 3, true),
  OVERLAY("能显示在其他应用上层", "可以随时弹出悬浮窗", 2, false),
  INSTALL_PACKAGES("能安装其他应用", "可能诱导安装更多应用", 2, false),
  NOTIFICATION_ACCESS("能读取所有通知", "能看到验证码等通知内容", 2, false),
  SHIZUKU("接入了 Shizuku", "可以申请高级权限", 2, false),
  BOOT_START("开机自动运行", "开机后就在后台活动", 1, false),
}

enum class RiskLevel(val label: String) {
  HIGH("高风险"),
  MEDIUM("需留意"),
}

data class RiskyApp(
  val pkg: String,
  val label: String,
  val signals: List<RiskSignal>,
  val harassCount: Int,
  val trust: Trust?,
) {
  val score: Int = signals.sumOf { it.weight }
  val level: RiskLevel = if (score >= HIGH_SCORE || RiskSignal.HARASSMENT in signals) RiskLevel.HIGH else RiskLevel.MEDIUM

  private companion object {
    const val HIGH_SCORE = 8
  }
}

/** 系统层检查；[ok] 为 false 表示建议处理；[freezable] 是维护模式下可以直接冻结的应用 */
data class SystemCheck(val title: String, val detail: String, val ok: Boolean, val freezable: List<String> = emptyList())

data class ScanResult(val apps: List<RiskyApp>, val checks: List<SystemCheck>, val scannedCount: Int, val time: Long)

class AppScanner(
  private val context: Context,
  private val rules: GuardRules,
  private val appInfo: AppInfoResolver,
  private val repository: GuardRepository,
) {
  private val pm = context.packageManager

  suspend fun scan(): ScanResult =
    withContext(Dispatchers.Default) {
      val ctx = collectContext()
      val packages =
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS).filter { it.isUserApp() }
      val apps =
        packages
          // 已冻结的应用不能运行，不再算风险
          .filter { it.packageName != context.packageName && it.packageName !in ctx.systemPkgs && it.applicationInfo?.enabled != false }
          .mapNotNull { info ->
            val pkg = info.packageName
            val trust = ctx.verdicts[pkg]
            if (trust == Trust.TRUSTED) return@mapNotNull null
            val signals = signalsOf(info, ctx)
            // 被家人标记为流氓的一定列出；其余至少要有一个强信号
            if (trust != Trust.FLAGGED && signals.none { it.strong }) return@mapNotNull null
            RiskyApp(pkg, appInfo.label(pkg), signals, ctx.harass[pkg] ?: 0, trust)
          }
          .sortedWith(compareBy<RiskyApp> { it.level }.thenByDescending { it.score })

      ScanResult(apps, systemChecks(), packages.size, System.currentTimeMillis())
    }

  /** 指定应用的风险信号（新装应用列表用）；已卸载的应用没有条目 */
  suspend fun assess(pkgs: Collection<String>): Map<String, List<RiskSignal>> =
    withContext(Dispatchers.Default) {
      if (pkgs.isEmpty()) return@withContext emptyMap()
      val ctx = collectContext()
      pkgs
        .mapNotNull { pkg ->
          runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS) }.getOrNull()
        }
        .associate { it.packageName to signalsOf(it, ctx) }
    }

  /** 判断风险信号要用到的全局信息，一次扫描只取一次 */
  private class ScanContext(
    val verdicts: Map<String, Trust>,
    val harass: Map<String, Int>,
    val a11yPkgs: Set<String>,
    val listenerPkgs: Set<String>,
    val admins: Set<String>,
    val launchable: Set<String>,
    val systemPkgs: Set<String>,
  )

  private suspend fun collectContext() =
    ScanContext(
      verdicts = repository.verdicts.first(),
      harass = repository.culpritCounts(),
      a11yPkgs = enabledComponents(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
      listenerPkgs = enabledComponents("enabled_notification_listeners"),
      admins = context.getSystemService(DevicePolicyManager::class.java).activeAdmins.orEmpty().map { it.packageName }.toSet(),
      launchable =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
          .map { it.activityInfo.packageName }
          .toSet(),
      systemPkgs = appInfo.systemPackages(),
    )

  private fun signalsOf(info: PackageInfo, ctx: ScanContext): List<RiskSignal> {
    val pkg = info.packageName
    val label = appInfo.label(pkg)
    val perms = info.requestedPermissions.orEmpty().toSet()
    // 厂商预装（含可卸载的预装，如 ColorOS 的平板管家、天气、无线耳机）本来就常没有图标、名字像工具，
    // 不按这两条算风险；骚扰记录、无障碍、设备管理器照常算
    // 厂商前缀的应用只有没有安装来源（出厂预置）或由厂商自己的应用（如自带商店）装上时才算；
    // 冒用厂商前缀、从安装包或第三方应用装进来的不算
    val vendorApp = info.isPreinstalled() || VENDOR_PREFIXES.any { pkg.startsWith(it) } && vendorInstalled(installer(pkg))
    return buildList {
      if ((ctx.harass[pkg] ?: 0) > 0) add(RiskSignal.HARASSMENT)
      if (pkg in ctx.a11yPkgs) add(RiskSignal.ACCESSIBILITY)
      if (pkg in ctx.admins) add(RiskSignal.DEVICE_ADMIN)
      if (pkg !in ctx.launchable && !vendorApp) add(RiskSignal.HIDDEN_ICON)
      if (TOOL_NAME.containsMatchIn(label) && !vendorApp) add(RiskSignal.TOOL_NAME)
      if (Manifest.permission.SYSTEM_ALERT_WINDOW in perms) add(RiskSignal.OVERLAY)
      if (Manifest.permission.REQUEST_INSTALL_PACKAGES in perms) add(RiskSignal.INSTALL_PACKAGES)
      if (pkg in ctx.listenerPkgs) add(RiskSignal.NOTIFICATION_ACCESS)
      if (info.providers.orEmpty().any { it.name == SHIZUKU_PROVIDER }) add(RiskSignal.SHIZUKU)
      if (Manifest.permission.RECEIVE_BOOT_COMPLETED in perms) add(RiskSignal.BOOT_START)
    }
  }

  private fun systemChecks(): List<SystemCheck> {
    val resolver = context.contentResolver
    val devOptions = Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    val adb = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1
    val adbWifi = Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) == 1
    // 已冻结的引擎不能再被拉起，不算
    val quickApps = rules.quickAppEngines.filter { appInfo.isInstalled(it) && !appInfo.isFrozen(it) }
    return buildList {
      add(
        SystemCheck(
          "开发者选项",
          if (devOptions) "已打开。部分银行应用会因此提示风险，维护结束后建议关闭" else "已关闭",
          ok = !devOptions,
        )
      )
      add(SystemCheck("USB 调试", if (adb) "已打开。不维护时建议关闭，避免被他人连接" else "已关闭", ok = !adb))
      // 无线调试 Android 11 才有；同一局域网内的人配对后就能控制手机
      if (Build.VERSION.SDK_INT >= 30) {
        add(SystemCheck("无线调试", if (adbWifi) "已打开。维护结束会自动关闭，也可以手动关闭" else "已关闭", ok = !adbWifi))
      }
      add(
        SystemCheck(
          "快应用",
          if (quickApps.isEmpty()) "未发现快应用引擎，或已冻结"
          else "已安装${quickApps.joinToString("、") { appInfo.label(it) }}，可能被广告拉起，守护会挡掉。维护模式下可以直接冻结，从根上去掉",
          ok = true,
          freezable = quickApps,
        )
      )
    }
  }

  private fun enabledComponents(key: String): Set<String> =
    runCatching { Settings.Secure.getString(context.contentResolver, key) }
      .getOrNull()
      .orEmpty()
      .split(':')
      .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
      .toSet()

  private fun PackageInfo.isUserApp(): Boolean {
    val flags = applicationInfo?.flags ?: return false
    return flags and ApplicationInfo.FLAG_SYSTEM == 0 || flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
  }

  private fun installer(pkg: String): String? =
    runCatching {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
      }
      .getOrNull()

  private fun vendorInstalled(installer: String?): Boolean =
    installer == null || VENDOR_PREFIXES.any { installer.startsWith(it) } && !installer.endsWith("packageinstaller")

  private fun PackageInfo.isPreinstalled(): Boolean = (applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0

  private companion object {
    const val SHIZUKU_PROVIDER = "rikka.shizuku.ShizukuProvider"
    /** 厂商自有包名前缀，第三方应用基本不会使用 */
    val VENDOR_PREFIXES =
      listOf(
        "com.android.", "com.google.android.", "android.",
        "com.coloros.", "com.oplus.", "com.heytap.", "com.oppo.", "com.nearme.", "com.oneplus.", "com.realme.",
        "com.miui.", "com.xiaomi.", "com.huawei.", "com.hihonor.", "com.honor.",
        "com.vivo.", "com.bbk.", "com.iqoo.", "com.meizu.", "com.samsung.", "com.sec.",
        "com.zte.", "cn.nubia.", "com.lenovo.", "com.motorola.",
      )
    val TOOL_NAME = Regex("清理|加速|垃圾|杀毒|管家|省电|充电|电池|WiFi|WIFI|wifi|万能钥匙|计步|走路|天气|手电|清理大师|极速|赚钱|红包")
  }
}
