package com.elderguard.app.data

import com.elderguard.app.core.Detection
import com.elderguard.app.core.Trust
import java.util.Calendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class GuardRepository(private val dao: GuardDao) {
  val verdicts: Flow<Map<String, Trust>> = dao.verdicts().map { list -> list.associate { it.pkg to it.trust } }

  fun recentEvents(limit: Int = 50): Flow<List<GuardEvent>> = dao.recent(limit)

  /** 近 7 天每个元凶的骚扰次数（体检用） */
  suspend fun culpritCounts(): Map<String, Int> =
    culpritRanking().first().associate { it.culpritPkg to it.count }

  /** 某个 App 最近的骚扰记录，用于向家人说明它做了什么 */
  fun eventsOf(pkg: String, limit: Int = 5): Flow<List<GuardEvent>> = dao.eventsOf(pkg, limit)

  fun todayCount(): Flow<Int> = dao.countSince(startOfToday())

  fun todayHandled(): Flow<Int> = dao.handledSince(startOfToday())

  /** 最近 7 天自动处理的次数（报告用） */
  fun weekHandled(): Flow<Int> = dao.handledSince(System.currentTimeMillis() - 7 * DAY_MS)

  /** 最近 7 天的元凶榜 */
  fun culpritRanking(): Flow<List<CulpritStat>> = dao.culpritRanking(System.currentTimeMillis() - 7 * DAY_MS)

  suspend fun record(detection: Detection, handled: Boolean) {
    dao.insert(
      GuardEvent(
        time = System.currentTimeMillis(),
        type = detection.type,
        culpritPkg = detection.culpritPkg,
        targetPkg = detection.targetPkg,
        targetClass = detection.targetClass,
        handled = handled,
        detail = detection.detail,
      )
    )
  }

  suspend fun latestEvents(limit: Int): List<GuardEvent> = dao.latest(limit)

  /** 自检：[since] 之后 [pkg] 出现在前台的第一条记录 */
  suspend fun firstEventFor(pkg: String, since: Long): GuardEvent? = dao.firstEventFor(pkg, since)

  /** 自检结束后删掉测试产生的记录，不影响元凶榜 */
  suspend fun deleteEventsFor(pkg: String, since: Long) = dao.deleteEventsFor(pkg, since)

  suspend fun recordInstall(app: InstalledApp) = dao.upsertInstall(app)

  /** 近 30 天新装的应用 */
  fun recentInstalls(): Flow<List<InstalledApp>> = dao.installsSince(System.currentTimeMillis() - 30 * DAY_MS)

  /** trust 为 null 表示清除结论，恢复「待观察」 */
  suspend fun setTrust(pkg: String, trust: Trust?) {
    if (trust == null) dao.deleteVerdict(pkg) else dao.upsertVerdict(AppVerdict(pkg, trust, System.currentTimeMillis()))
  }

  private fun startOfToday(): Long =
    Calendar.getInstance()
      .apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
      .timeInMillis

  private companion object {
    const val DAY_MS = 24 * 60 * 60 * 1000L
  }
}
