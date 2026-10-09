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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

/** Repositório de dados (fonte do catálogo). */
@Entity(tableName = "data_repos")
data class DataRepoEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** URL do JSON do catálogo (já resolvida; pode ser direta ou de um repo GitHub). */
    val url: String,
    /** Base das imagens (raiz raw do repositório, terminando em "/"). */
    val baseUrl: String,
    /** Base do site (GitHub Pages) para links "./..." de jogos web. */
    val siteBase: String,
    val enabled: Boolean,
    val sortIndex: Int,
    val lastUpdated: Long,
    val etag: String?,
    val gameCount: Int,
    val status: String,
    val lastError: String?,
)

/** Corpo bruto do games.json de cada repositório (cache offline). */
@Entity(tableName = "repo_cache")
data class RepoCacheEntity(
    @PrimaryKey val repoId: String,
    val body: String,
    val etag: String?,
    val fetchedAt: Long,
)

/** Cache de consulta de versão (releases do GitHub de cada port). */
@Entity(tableName = "version_cache")
data class VersionCacheEntity(
    @PrimaryKey val gameId: String,
    val repoSlug: String,
    val tag: String,
    val releaseName: String?,
    val notes: String?,
    val publishedAt: String?,
    val assetsJson: String,
    val etag: String?,
    val fetchedAt: Long,
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

@Dao
interface DataRepoDao {
    @Query("SELECT * FROM data_repos ORDER BY sortIndex ASC")
    fun observeAll(): Flow<List<DataRepoEntity>>

    @Query("SELECT * FROM data_repos ORDER BY sortIndex ASC")
    suspend fun all(): List<DataRepoEntity>

    @Query("SELECT * FROM data_repos WHERE id = :id")
    suspend fun get(id: String): DataRepoEntity?

    @Query("SELECT COUNT(*) FROM data_repos")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DataRepoEntity)

    @Query("UPDATE data_repos SET enabled = :enabled, lastUpdated = :at WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, at: Long)

    @Query("UPDATE data_repos SET sortIndex = :index WHERE id = :id")
    suspend fun setSortIndex(id: String, index: Int)

    @Query("UPDATE data_repos SET name = :name, url = :url, baseUrl = :baseUrl, siteBase = :siteBase WHERE id = :id")
    suspend fun updateTargets(id: String, name: String, url: String, baseUrl: String, siteBase: String)

    @Query("UPDATE data_repos SET status = :status, lastError = :error, lastUpdated = :at, gameCount = :gameCount, etag = :etag WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, error: String?, at: Long, gameCount: Int, etag: String?)

    @Query("DELETE FROM data_repos WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface RepoCacheDao {
    @Query("SELECT * FROM repo_cache WHERE repoId = :repoId")
    suspend fun get(repoId: String): RepoCacheEntity?

    @Query("SELECT * FROM repo_cache")
    suspend fun all(): List<RepoCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RepoCacheEntity)

    @Query("DELETE FROM repo_cache WHERE repoId = :repoId")
    suspend fun delete(repoId: String)
}

@Dao
interface VersionCacheDao {
    @Query("SELECT * FROM version_cache WHERE gameId = :gameId")
    suspend fun get(gameId: String): VersionCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VersionCacheEntity)

    @Query("DELETE FROM version_cache WHERE gameId = :gameId")
    suspend fun delete(gameId: String)
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `data_repos` (" +
                "`id` TEXT NOT NULL PRIMARY KEY, " +
                "`name` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`baseUrl` TEXT NOT NULL, " +
                "`siteBase` TEXT NOT NULL, " +
                "`enabled` INTEGER NOT NULL, " +
                "`sortIndex` INTEGER NOT NULL, " +
                "`lastUpdated` INTEGER NOT NULL, " +
                "`etag` TEXT, " +
                "`gameCount` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`lastError` TEXT)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `repo_cache` (" +
                "`repoId` TEXT NOT NULL PRIMARY KEY, " +
                "`body` TEXT NOT NULL, " +
                "`etag` TEXT, " +
                "`fetchedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `version_cache` (" +
                "`gameId` TEXT NOT NULL PRIMARY KEY, " +
                "`repoSlug` TEXT NOT NULL, " +
                "`tag` TEXT NOT NULL, " +
                "`releaseName` TEXT, " +
                "`notes` TEXT, " +
                "`publishedAt` TEXT, " +
                "`assetsJson` TEXT NOT NULL, " +
                "`etag` TEXT, " +
                "`fetchedAt` INTEGER NOT NULL)",
        )
    }
}

@Database(
    entities = [
        DownloadEntity::class,
        SearchHistoryEntity::class,
        DataRepoEntity::class,
        RepoCacheEntity::class,
        VersionCacheEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun dataRepoDao(): DataRepoDao
    abstract fun repoCacheDao(): RepoCacheDao
    abstract fun versionCacheDao(): VersionCacheDao

    companion object {
        fun build(context: android.content.Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "portstore.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
