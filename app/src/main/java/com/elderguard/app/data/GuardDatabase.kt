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

  @Query("SELECT * FROM app_verdict") fun verdicts(): Flow<List<AppVerdict>>

  @Upsert suspend fun upsertVerdict(verdict: AppVerdict)

  @Query("DELETE FROM app_verdict WHERE pkg = :pkg") suspend fun deleteVerdict(pkg: String)
}

class Converters {
  @TypeConverter fun eventType(value: EventType): String = value.name

  @TypeConverter fun eventType(value: String): EventType = EventType.valueOf(value)

  @TypeConverter fun trust(value: Trust): String = value.name

  @TypeConverter fun trust(value: String): Trust = Trust.valueOf(value)
}

@Database(entities = [GuardEvent::class, AppVerdict::class], version = 1)
@TypeConverters(Converters::class)
abstract class GuardDatabase : RoomDatabase() {
  abstract fun guardDao(): GuardDao

  companion object {
    fun build(context: Context): GuardDatabase =
      Room.databaseBuilder(context, GuardDatabase::class.java, "guard.db").build()
  }
}
