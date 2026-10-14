package com.elderguard.app.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

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
  fun icon(pkg: String, sizePx: Int): Bitmap? =
    icons["$pkg@$sizePx"]
      ?: runCatching { pm.getApplicationIcon(pkg).toBitmap(sizePx, sizePx) }.getOrNull()?.also { icons["$pkg@$sizePx"] = it }

  fun isInstalled(pkg: String): Boolean = runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess

  /** 窗口切换事件里的 className 是否真的是 Activity（过滤对话框、Toast、悬浮层） */
  fun isActivity(pkg: String, cls: String): Boolean =
    activityCache.getOrPut("$pkg/$cls") {
      runCatching { pm.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
    }

  /** 所有桌面 + 当前输入法：它们出现在前台属于正常现象 */
  fun systemPackages(): Set<String> {
    val homes =
      pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName }
    val imes = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
    val defaultIme =
      Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.let { ComponentName.unflattenFromString(it)?.packageName }
    return (homes + imes + listOfNotNull(defaultIme)).toSet()
  }
}
