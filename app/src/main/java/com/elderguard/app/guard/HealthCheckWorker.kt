package com.elderguard.app.guard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.elderguard.app.container
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * 周期巡检：无障碍服务被关掉（系统省电、长辈误关）时持续提醒。
 * 注意：App 被「强行停止」后系统会清掉它的定时任务，巡检也无法运行，需要靠电池白名单等手段预防。
 */
class HealthCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val c = applicationContext.container
    if (!c.settings.setupDone.first()) return Result.success()
    if (c.permissions.isAccessibilityEnabled()) GuardNotifier.cancelStopped(applicationContext)
    else GuardNotifier.notifyStopped(applicationContext)
    return Result.success()
  }

  companion object {
    private const val NAME = "guard_health"

    fun schedule(context: Context) {
      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(
          NAME,
          ExistingPeriodicWorkPolicy.KEEP,
          PeriodicWorkRequestBuilder<HealthCheckWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
  }
}
