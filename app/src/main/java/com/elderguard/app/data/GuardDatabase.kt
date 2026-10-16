package com.elderguard.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.elderguard.app.core.EventType
import com.elderguard.app.core.Trust
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "guard_event", indices = [Index("time"), Index("culpritPkg")])
data class GuardEvent(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  /** 墙上时间（毫秒） */
  val time: Long,
  val type: EventType,
  val culpritPkg: String,
  val targetPkg: String,
  val targetClass: String?,
  /** 是否已自动处理 */
  val handled: Boolean,
  val detail: String?,
)

@Entity(tableName = "app_verdict")
data class AppVerdict(@PrimaryKey val pkg: String, val trust: Trust, val updatedAt: Long)

/** 新装的应用（不含更新）：装上的时间和来源 */
@Entity(tableName = "installed_app", indices = [Index("time")])
data class InstalledApp(
  @PrimaryKey val pkg: String,
  /** 墙上时间（毫秒） */
  val time: Long,
  /** 发起安装的应用（商店、浏览器、文件管理…）；系统没给时为 null */
  val installer: String?,
  /** Android 13 起系统记录的来源：商店 / 本地文件 / 下载的文件…（PackageInstaller.PACKAGE_SOURCE_*） */
  val source: Int?,
)

/** 元凶榜的一行；[types] 是它出现过的事件类型，逗号分隔 */
data class CulpritStat(val culpritPkg: String, val count: Int, val lastTime: Long, val types: String? = null) {
  val eventTypes: List<EventType>
    get() = types.orEmpty().split(',').mapNotNull { name -> EventType.entries.firstOrNull { it.name == name } }
}

@Dao
interface GuardDao {
  @Insert suspend fun insert(event: GuardEvent)

  @Query("SELECT * FROM guard_event ORDER BY time DESC LIMIT :limit")
  fun recent(limit: Int): Flow<List<GuardEvent>>

  @Query("SELECT COUNT(*) FROM guard_event WHERE time >= :since")
  fun countSince(since: Long): Flow<Int>

  @Query("SELECT COUNT(*) FROM guard_event WHERE time >= :since AND handled = 1")
  fun handledSince(since: Long): Flow<Int>

  @Query(
    "SELECT culpritPkg, COUNT(*) AS count, MAX(time) AS lastTime, GROUP_CONCAT(DISTINCT type) AS types " +
      "FROM guard_event WHERE time >= :since GROUP BY culpritPkg ORDER BY count DESC"
  )
  fun culpritRanking(since: Long): Flow<List<CulpritStat>>

  @Query("SELECT * FROM guard_event WHERE culpritPkg = :pkg ORDER BY time DESC LIMIT :limit")
  fun eventsOf(pkg: String, limit: Int): Flow<List<GuardEvent>>

  @Query("SELECT * FROM guard_event ORDER BY time DESC LIMIT :limit")
  suspend fun latest(limit: Int): List<GuardEvent>

  @Query("SELECT * FROM guard_event WHERE targetPkg = :pkg AND time >= :since ORDER BY time LIMIT 1")
  suspend fun firstEventFor(pkg: String, since: Long): GuardEvent?

  @Query("DELETE FROM guard_event WHERE targetPkg = :pkg AND time >= :since")
  suspend fun deleteEventsFor(pkg: String, since: Long)

  @Query("SELECT * FROM app_verdict") fun verdicts(): Flow<List<AppVerdict>>

  @Upsert suspend fun upsertVerdict(verdict: AppVerdict)

  @Query("DELETE FROM app_verdict WHERE pkg = :pkg") suspend fun deleteVerdict(pkg: String)

  @Upsert suspend fun upsertInstall(app: InstalledApp)

  @Query("SELECT * FROM installed_app WHERE time >= :since ORDER BY time DESC")
  fun installsSince(since: Long): Flow<List<InstalledApp>>
}

class Converters {
  @TypeConverter fun eventType(value: EventType): String = value.name

  @TypeConverter fun eventType(value: String): EventType = EventType.valueOf(value)

  @TypeConverter fun trust(value: Trust): String = value.name

  @TypeConverter fun trust(value: String): Trust = Trust.valueOf(value)
}

@Database(entities = [GuardEvent::class, AppVerdict::class, InstalledApp::class], version = 2)
@TypeConverters(Converters::class)
abstract class GuardDatabase : RoomDatabase() {
  abstract fun guardDao(): GuardDao

  companion object {
    /**
     * 1.1.0：不再推测「是不是长辈点的」，删掉靠推测产生的记录（自动弹出、解锁弹窗、跳转商店）；
     * 家人的标记结论保留。新增「新装的应用」表
     */
    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("DELETE FROM guard_event WHERE type IN ('UNSOLICITED_LAUNCH', 'UNLOCK_POPUP', 'STORE_REDIRECT')")
          db.execSQL(
            "CREATE TABLE IF NOT EXISTS `installed_app` (`pkg` TEXT NOT NULL, `time` INTEGER NOT NULL, " +
              "`installer` TEXT, `source` INTEGER, PRIMARY KEY(`pkg`))"
          )
          db.execSQL("CREATE INDEX IF NOT EXISTS `index_installed_app_time` ON `installed_app` (`time`)")
        }
      }

    fun build(context: Context): GuardDatabase =
      Room.databaseBuilder(context, GuardDatabase::class.java, "guard.db").addMigrations(MIGRATION_1_2).build()
  }
}
