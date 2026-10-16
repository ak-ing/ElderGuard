package com.elderguard.app.selftest

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.net.toUri
import com.elderguard.app.AppContainer
import com.elderguard.app.BuildConfig
import com.elderguard.app.core.GuardMode
import com.elderguard.app.data.GuardEvent
import com.elderguard.app.guard.EventTrace
import com.elderguard.app.guard.GuardAccessibilityService
import com.elderguard.app.guard.GuardNotificationListener
import com.elderguard.app.system.CrashLog
import com.elderguard.app.system.PermissionKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class CheckStatus { PENDING, RUNNING, PASSED, FAILED, SKIPPED }

enum class CheckId(val title: String) {
  GUARD("守护服务"),
  NOTIFICATION("通知监听"),
  QUICK_APP("挡掉快应用"),
}

data class CheckResult(val id: CheckId, val status: CheckStatus = CheckStatus.PENDING, val detail: String = "等待检测")

/** 负责处理某类跳转的应用；[inRules] 表示在拦截名单里 */
data class Handler(val pkg: String, val label: String, val inRules: Boolean)

/** 自检要打开的目标：[intent] 已经指定了包名 */
data class LaunchTarget(val pkg: String, val label: String, val intent: Intent)

/**
 * 守护自检：在长辈手机上打开一次快应用，检查守护能不能挡掉，并生成可以发给开发者的报告。
 * 只用本机能力，不联网。
 */
class SelfTester(private val context: Context, private val c: AppContainer) {
  private val pm = context.packageManager

  /** 能打开快应用链接的应用（hap:// 是各厂商快应用联盟的统一链接） */
  fun quickAppHandlers(): List<Handler> = handlers(HAP_INTENT, c.rules.quickAppEngines)

  suspend fun blockQuickApps(): Boolean = c.settings.blockQuickApps.first()

  /** 守护挡掉同一个应用后，这么久内不再动手 */
  val cooldownMs: Long
    get() = c.rules.autoActionCooldownMs

  /** 优先用名单里的快应用引擎打开快应用链接；没有链接处理者时直接打开已安装的引擎 */
  fun quickAppTarget(): LaunchTarget? {
    quickAppHandlers().firstOrNull { it.inRules }?.let { return LaunchTarget(it.pkg, it.label, Intent(HAP_INTENT).setPackage(it.pkg)) }
    return c.rules.quickAppEngines.firstNotNullOfOrNull { pkg ->
      pm.getLaunchIntentForPackage(pkg)?.let { LaunchTarget(pkg, c.appInfo.label(pkg), it) }
    }
  }

  fun launch(target: LaunchTarget) {
    context.startActivity(Intent(target.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }

  /** 等守护记下 [pkg] 出现在前台；超时返回 null */
  suspend fun awaitEvent(pkg: String, since: Long, timeoutMs: Long): GuardEvent? =
    withTimeoutOrNull(timeoutMs) {
      var event: GuardEvent? = null
      while (event == null) {
        event = c.guardRepository.firstEventFor(pkg, since)
        if (event == null) delay(POLL_MS)
      }
      event
    }

  suspend fun clearTestEvents(pkg: String, since: Long) = c.guardRepository.deleteEventsFor(pkg, since)

  fun guardRunning(): Boolean = c.permissions.isAccessibilityEnabled() && GuardAccessibilityService.running.value

  fun notificationEnabled(): Boolean = c.permissions.isGranted(PermissionKind.NOTIFICATION_LISTENER) == true

  fun notificationConnected(): Boolean = GuardNotificationListener.connected.value

  suspend fun guardMode(): GuardMode = c.settings.guardMode.first()

  /** 纯文本报告：家人通过微信等发给开发者，用来定位厂商系统上的问题 */
  suspend fun buildReport(results: List<CheckResult>): String =
    withContext(Dispatchers.Default) {
      val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
      buildString {
        appendLine("长辈守护 自检报告")
        appendLine("生成时间：${time.format(Date())}")
        appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " debug" else ""}")
        appendLine()
        appendLine("【设备】")
        appendLine("型号：${Build.MANUFACTURER} ${Build.MODEL}（${Build.BRAND} / ${Build.DEVICE}）")
        appendLine("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
        appendLine("版本号：${Build.DISPLAY}")
        appendLine()
        appendLine("【守护】")
        appendLine("守护方式：${guardMode().label}")
        appendLine("挡掉快应用：${if (blockQuickApps()) "开" else "关"}")
        c.permissions.snapshot().forEach { p ->
          appendLine("${p.kind.title}：${when (p.granted) { true -> "已开启"; false -> "未开启"; null -> "无法检测" }}")
        }
        appendLine("安装来源：${c.permissions.installSourceLabel()}")
        appendLine()
        appendLine("【振动】")
        appendVibrator()
        appendLine()
        appendLine("【自检结果】")
        results.forEach { appendLine("${it.id.title}：${it.status.label} - ${it.detail}") }
        appendLine()
        appendLine("【快应用链接处理者】")
        appendHandlers(quickAppHandlers())
        appendLine("名单内已安装的快应用引擎：${c.rules.quickAppEngines.filter { c.appInfo.isInstalled(it) }.ifEmpty { listOf("无") }.joinToString("、")}")
        appendLine()
        appendLine("【最近无障碍事件】（本次守护启动后，只含包名/类名）")
        EventTrace.snapshot().ifEmpty { listOf("无") }.forEach(::appendLine)
        appendLine()
        appendLine("【最近记录】")
        val events = c.guardRepository.latestEvents(RECENT_EVENTS)
        if (events.isEmpty()) appendLine("无")
        events.forEach { e ->
          appendLine(
            "${time.format(Date(e.time))} ${e.type.label} ${e.culpritPkg} → ${e.targetPkg}/${e.targetClass.orEmpty()} " +
              "${if (e.handled) "已处理" else "仅记录"}${e.detail?.let { " $it" }.orEmpty()}"
          )
        }
        appendLine()
        // 对照两边：守护记下的 vs 系统记的近 30 天首次安装，差的就是安装广播没收到的
        appendLine("【新装的应用】")
        val since = c.guardRepository.installWindowStart()
        val recorded = c.guardRepository.allInstalls().filter { it.time >= since }
        appendLine("守护记下的：${recorded.size} 个")
        recorded.forEach { appendLine("  ${time.format(Date(it.time))} ${it.pkg} 来源 ${c.appInfo.sourceLabel(it) ?: "未知"}") }
        val missing = c.appInfo.installedSince(since).filter { app -> recorded.none { it.pkg == app.pkg } }
        appendLine("系统有记录、守护没记下的：${missing.size} 个")
        missing.forEach { appendLine("  ${time.format(Date(it.time))} ${it.pkg}") }
        appendLine()
        // 厂商真机上闪退时用来看是崩溃，还是被系统结束（如权限变化）
        appendLine("【最近的进程退出】")
        CrashLog.exitReasons(context).ifEmpty { listOf("无") }.forEach(::appendLine)
        appendLine()
        appendLine("【最近一次崩溃】")
        appendLine(CrashLog.lastCrash(context) ?: "无")
        appendLine()
        appendLine("【日志】")
        append(readLog())
      }
    }

  /** 密码键盘没有震感时用来判断：有没有马达、支不支持预设效果、系统振动设置 */
  private fun StringBuilder.appendVibrator() {
    val vibrator: Vibrator? =
      if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
      else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    if (vibrator == null) {
      appendLine("取不到振动器")
      return
    }
    appendLine("有振动马达：${if (vibrator.hasVibrator()) "是" else "否"}")
    appendLine("可调强度：${if (vibrator.hasAmplitudeControl()) "是" else "否"}")
    if (Build.VERSION.SDK_INT >= 30) {
      val support = vibrator.areEffectsSupported(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_HEAVY_CLICK)
      appendLine(
        "预设效果：" +
          listOf("点击", "重击").zip(support.toList()).joinToString("、") { (n, s) ->
            "$n${when (s) { Vibrator.VIBRATION_EFFECT_SUPPORT_YES -> "支持"; Vibrator.VIBRATION_EFFECT_SUPPORT_NO -> "不支持"; else -> "未知" }}"
          }
      )
    }
    if (Build.VERSION.SDK_INT >= 31) {
      appendLine("马达数量：${context.getSystemService(VibratorManager::class.java)?.vibratorIds?.size ?: 0}")
    }
    // 系统振动设置（读不到的厂商自定义项写「无」）
    listOf("haptic_feedback_enabled", "haptic_feedback_intensity", "media_vibration_intensity", "notification_vibration_intensity")
      .forEach { key ->
        val value = runCatching { Settings.System.getString(context.contentResolver, key) }.getOrNull()
        appendLine("$key：${value ?: "无"}")
      }
  }

  private fun StringBuilder.appendHandlers(list: List<Handler>) {
    if (list.isEmpty()) appendLine("无")
    list.forEach { appendLine("${it.label}（${it.pkg}）${if (it.inRules) "在名单里" else "不在名单里"}") }
  }

  /** 只能读到本 App 自己的日志，不需要额外权限 */
  private fun readLog(): String =
    runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "$LOG_LINES", "ElderGuard:V", "AndroidRuntime:E", "*:S")
          .redirectErrorStream(true)
          .start()
        process.inputStream.bufferedReader().use { it.readText() }.ifBlank { "（无）\n" }
      }
      .getOrElse { "读取失败：${it.message}\n" }

  private fun handlers(intent: Intent, rules: Set<String>): List<Handler> =
    runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_ALL) }
      .getOrDefault(emptyList())
      .map { it.activityInfo.packageName }
      .distinct()
      .map { Handler(it, c.appInfo.label(it), it in rules) }

  private companion object {
    /** 不存在的快应用：引擎会打开并提示找不到，足以触发守护 */
    val HAP_INTENT = Intent(Intent.ACTION_VIEW, "hap://app/com.elderguard.selftest".toUri())
    const val POLL_MS = 200L
    const val RECENT_EVENTS = 30
    const val LOG_LINES = 400
  }
}

val CheckStatus.label: String
  get() =
    when (this) {
      CheckStatus.PENDING -> "待检测"
      CheckStatus.RUNNING -> "检测中"
      CheckStatus.PASSED -> "通过"
      CheckStatus.FAILED -> "未通过"
      CheckStatus.SKIPPED -> "跳过"
    }
