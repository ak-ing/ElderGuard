package com.elderguard.app.system

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃和进程退出的记录，写进自检报告：厂商真机上没法接电脑看日志，只能靠报告。
 * 崩溃时把异常写进文件（只留最近一次），然后交还系统原来的处理；
 * 进程退出原因（崩溃、被系统结束、权限变化等）由系统记录，Android 11 起可读
 */
object CrashLog {
  private const val FILE = "last_crash.txt"
  private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

  fun install(context: Context) {
    val file = File(context.filesDir, FILE)
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching { file.writeText("${time.format(Date())} 线程 ${thread.name}\n${error.stackTraceToString()}") }
      previous?.uncaughtException(thread, error)
    }
  }

  /** 最近一次崩溃的异常栈，没有时为 null */
  fun lastCrash(context: Context): String? = runCatching { File(context.filesDir, FILE).readText() }.getOrNull()

  /** 最近几次进程退出：时间、原因、系统给的说明 */
  fun exitReasons(context: Context, max: Int = 5): List<String> {
    if (Build.VERSION.SDK_INT < 30) return listOf("Android 11 以下无法读取")
    val am = context.getSystemService(ActivityManager::class.java)
    return runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, max) }
      .getOrDefault(emptyList())
      .map { "${time.format(Date(it.timestamp))} ${reasonLabel(it.reason)}${it.description?.let { d -> "（$d）" }.orEmpty()}" }
  }

  private fun reasonLabel(reason: Int): String =
    when (reason) {
      ApplicationExitInfo.REASON_CRASH -> "崩溃"
      ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃"
      ApplicationExitInfo.REASON_ANR -> "无响应"
      ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变化被系统结束"
      ApplicationExitInfo.REASON_USER_REQUESTED -> "被强行停止或卸载更新"
      ApplicationExitInfo.REASON_USER_STOPPED -> "被用户停止"
      ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "应用更新"
      ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖的进程退出"
      ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源占用过多被结束"
      ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足被结束"
      ApplicationExitInfo.REASON_SIGNALED -> "被系统信号结束"
      ApplicationExitInfo.REASON_EXIT_SELF -> "自行退出"
      ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "启动失败"
      ApplicationExitInfo.REASON_FREEZER -> "被系统冻结"
      ApplicationExitInfo.REASON_OTHER -> "其他（厂商系统结束等）"
      else -> "未知($reason)"
    }
}
