package com.deivid22srk.portstore.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val gameId: String,
    val title: String,
    val cover: String?,
    val url: String,
    val destPath: String,
    val state: String,
    val downloaded: Long,
    val total: Long,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val at: Long,
)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state IN ('Queued','Connecting','Downloading','Verifying')")
    suspend fun active(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state IN ('Queued','Connecting','Downloading','Verifying','Paused')")
    suspend fun nonTerminal(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state = 'Completed'")
    suspend fun completed(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE downloads SET state = :state, error = :error, downloaded = :downloaded, total = :total, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgress(id: String, state: String, error: String?, downloaded: Long, total: Long, updatedAt: Long)

    @Query("UPDATE downloads SET state = :state, error = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateState(id: String, state: String, error: String?, updatedAt: Long)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM downloads WHERE state = 'Completed'")
    suspend fun deleteCompleted()
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY at DESC LIMIT 12")
    fun observeRecent(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Database(
    entities = [DownloadEntity::class, SearchHistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        fun build(context: android.content.Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "portstore.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
