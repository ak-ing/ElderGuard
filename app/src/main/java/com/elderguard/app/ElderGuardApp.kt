package com.elderguard.app

import android.app.Application
import android.content.Context
import com.elderguard.app.core.GuardRules
import com.elderguard.app.data.GuardDatabase
import com.elderguard.app.data.GuardRepository
import com.elderguard.app.data.SettingsRepository
import com.elderguard.app.privileged.PrivilegedExecutor
import com.elderguard.app.privileged.ShizukuExecutor
import com.elderguard.app.system.AppInfoResolver
import com.elderguard.app.system.PermissionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ElderGuardApp : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
  }
}

/** 手动依赖注入：全局单例都在这里装配 */
class AppContainer(context: Context) {
  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val rules: GuardRules by lazy { GuardRules.load(context) }
  val guardRepository = GuardRepository(GuardDatabase.build(context).guardDao())
  val settings = SettingsRepository(context)
  val permissions = PermissionChecker(context)
  val appInfo = AppInfoResolver(context)
  val privileged: PrivilegedExecutor = ShizukuExecutor(context)
}

val Context.container: AppContainer
  get() = (applicationContext as ElderGuardApp).container
