package com.elderguard.app.scan

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
  HARASSMENT("近 7 天有骚扰记录", "守护记录到它自己弹出或诱导跳转", 6, true),
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

/** 系统层检查；[ok] 为 false 表示建议处理 */
data class SystemCheck(val title: String, val detail: String, val ok: Boolean)

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
      val verdicts = repository.verdicts.first()
      val harass = repository.culpritCounts()
      val a11yPkgs = enabledComponents(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
      val listenerPkgs = enabledComponents("enabled_notification_listeners")
      val admins =
        context.getSystemService(DevicePolicyManager::class.java).activeAdmins.orEmpty().map { it.packageName }.toSet()
      val launchable =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
          .map { it.activityInfo.packageName }
          .toSet()
      val systemPkgs = appInfo.systemPackages()

      val packages =
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_PROVIDERS).filter { it.isUserApp() }
      val apps =
        packages
          .filter { it.packageName != context.packageName && it.packageName !in systemPkgs }
          .mapNotNull { info ->
            val pkg = info.packageName
            val trust = verdicts[pkg]
            if (trust == Trust.TRUSTED) return@mapNotNull null
            val label = appInfo.label(pkg)
            val perms = info.requestedPermissions.orEmpty().toSet()
            val signals = buildList {
              if ((harass[pkg] ?: 0) > 0) add(RiskSignal.HARASSMENT)
              if (pkg in a11yPkgs) add(RiskSignal.ACCESSIBILITY)
              if (pkg in admins) add(RiskSignal.DEVICE_ADMIN)
              // 预装应用（含更新过的 Google Play 服务等）本来就常没有图标，只看用户自己装的
              if (pkg !in launchable && !info.isPreinstalled()) add(RiskSignal.HIDDEN_ICON)
              if (TOOL_NAME.containsMatchIn(label)) add(RiskSignal.TOOL_NAME)
              if (Manifest.permission.SYSTEM_ALERT_WINDOW in perms) add(RiskSignal.OVERLAY)
              if (Manifest.permission.REQUEST_INSTALL_PACKAGES in perms) add(RiskSignal.INSTALL_PACKAGES)
              if (pkg in listenerPkgs) add(RiskSignal.NOTIFICATION_ACCESS)
              if (info.providers.orEmpty().any { it.name == SHIZUKU_PROVIDER }) add(RiskSignal.SHIZUKU)
              if (Manifest.permission.RECEIVE_BOOT_COMPLETED in perms) add(RiskSignal.BOOT_START)
            }
            // 被家人标记为流氓的一定列出；其余至少要有一个强信号
            if (trust != Trust.FLAGGED && signals.none { it.strong }) return@mapNotNull null
            RiskyApp(pkg, label, signals, harass[pkg] ?: 0, trust)
          }
          .sortedWith(compareBy<RiskyApp> { it.level }.thenByDescending { it.score })

      ScanResult(apps, systemChecks(), packages.size, System.currentTimeMillis())
    }

  private fun systemChecks(): List<SystemCheck> {
    val resolver = context.contentResolver
    val devOptions = Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    val adb = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1
    val quickApps = rules.quickAppEngines.filter { appInfo.isInstalled(it) }.map { appInfo.label(it) }
    return listOf(
      SystemCheck(
        "开发者选项",
        if (devOptions) "已打开。部分银行应用会因此提示风险，维护结束后建议关闭" else "已关闭",
        ok = !devOptions,
      ),
      SystemCheck("USB 调试", if (adb) "已打开。不维护时建议关闭，避免被他人连接" else "已关闭", ok = !adb),
      SystemCheck(
        "快应用",
        if (quickApps.isEmpty()) "未发现快应用引擎" else "已安装${quickApps.joinToString("、")}，可能被广告拉起；守护会自动挡掉",
        ok = true,
      ),
    )
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

  private fun PackageInfo.isPreinstalled(): Boolean = (applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0

  private companion object {
    const val SHIZUKU_PROVIDER = "rikka.shizuku.ShizukuProvider"
    val TOOL_NAME = Regex("清理|加速|垃圾|杀毒|管家|省电|充电|电池|WiFi|WIFI|wifi|万能钥匙|计步|走路|天气|手电|清理大师|极速|赚钱|红包")
  }
}
