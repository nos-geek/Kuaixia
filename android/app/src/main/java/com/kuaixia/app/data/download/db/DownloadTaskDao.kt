package com.kuaixia.app.data.download.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 下载任务 DAO。 */
@Dao
interface DownloadTaskDao {

    /** 全量任务流（按创建时间倒序，供 UI 观察）。 */
    @Query("SELECT * FROM download_task ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    /** 一次性读取全部（App 启动恢复用）。 */
    @Query("SELECT * FROM download_task")
    suspend fun getAll(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun getById(id: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DownloadTaskEntity>)

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_task")
    suspend fun clearAll()
}
