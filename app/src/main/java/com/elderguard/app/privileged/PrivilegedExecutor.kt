package com.elderguard.app.privileged

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

enum class PrivilegeStatus(val label: String) {
  NOT_INSTALLED("未安装"),
  NOT_RUNNING("未启动"),
  NEEDS_PERMISSION("待授权"),
  READY("已就绪"),
}

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
  val ok: Boolean
    get() = exitCode == 0
}

/**
 * 高权限执行器：维护模式下用来直接执行处置（收回悬浮窗、冻结、卸载…）。
 * 业务只依赖此接口，底层先用 Shizuku，以后可换成内置 ADB 实现。
 */
interface PrivilegedExecutor {
  fun status(): PrivilegeStatus

  fun requestPermission()

  suspend fun exec(vararg command: String): ShellResult
}

class ShizukuExecutor(private val context: Context) : PrivilegedExecutor {
  override fun status(): PrivilegeStatus =
    when {
      !isShizukuInstalled() -> PrivilegeStatus.NOT_INSTALLED
      !Shizuku.pingBinder() -> PrivilegeStatus.NOT_RUNNING
      Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> PrivilegeStatus.NEEDS_PERMISSION
      else -> PrivilegeStatus.READY
    }

  override fun requestPermission() {
    if (Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQUEST_CODE)
  }

  // P2：通过 Shizuku UserService（AIDL）在 shell 进程里执行，见 docs/ARCHITECTURE.md 1.6
  override suspend fun exec(vararg command: String): ShellResult = ShellResult(-1, "", "维护模式尚未实现")

  private fun isShizukuInstalled(): Boolean =
    runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

  companion object {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 1001
  }
}
