package com.elderguard.app.privileged

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku UserService：由 Shizuku 在 shell 身份（uid 2000）的独立进程里实例化，这里执行的命令就有 adb shell 的权限。
 * 只有一个调用方（本 App），协议简单，所以直接手写 Binder，不引入 AIDL。
 * 注意：这个进程里没有 Application，不能访问 container。
 */
class ShellService : Binder() {
  override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
    when (code) {
      TRANSACTION_EXEC -> {
        data.enforceInterface(DESCRIPTOR)
        val result = run(data.createStringArray()?.filterNotNull().orEmpty())
        reply?.apply {
          writeNoException()
          writeInt(result.exitCode)
          writeString(result.stdout)
          writeString(result.stderr)
        }
        true
      }
      // Shizuku 解绑并要求移除服务时发来，进程自行退出
      TRANSACTION_DESTROY -> exitProcess(0)
      else -> super.onTransact(code, data, reply, flags)
    }

  private fun run(command: List<String>): ShellResult {
    if (command.isEmpty()) return ShellResult(-1, "", "空命令")
    return runCatching {
        val process = ProcessBuilder(command).start()
        var stdout = ""
        var stderr = ""
        val readers =
          listOf(
            thread { stdout = process.inputStream.bufferedReader().readText() },
            thread { stderr = process.errorStream.bufferedReader().readText() },
          )
        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
          process.destroyForcibly()
          return ShellResult(-1, "", "执行超时")
        }
        readers.forEach { it.join() }
        ShellResult(process.exitValue(), stdout.trim(), stderr.trim())
      }
      .getOrElse { ShellResult(-1, "", it.message.orEmpty()) }
  }

  companion object {
    const val DESCRIPTOR = "com.elderguard.app.privileged.ShellService"
    private const val TRANSACTION_EXEC = IBinder.FIRST_CALL_TRANSACTION
    /** Shizuku 约定的 UserService 销毁事务码（ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy） */
    private const val TRANSACTION_DESTROY = 16777115
    private const val TIMEOUT_S = 30L

    /** 客户端：在调用方进程里通过 [binder] 执行命令（阻塞，需在 IO 线程调用） */
    fun exec(binder: IBinder, command: Array<out String>): ShellResult {
      val data = Parcel.obtain()
      val reply = Parcel.obtain()
      return try {
        data.writeInterfaceToken(DESCRIPTOR)
        data.writeStringArray(command)
        binder.transact(TRANSACTION_EXEC, data, reply, 0)
        reply.readException()
        ShellResult(reply.readInt(), reply.readString().orEmpty(), reply.readString().orEmpty())
      } finally {
        data.recycle()
        reply.recycle()
      }
    }
  }
}
