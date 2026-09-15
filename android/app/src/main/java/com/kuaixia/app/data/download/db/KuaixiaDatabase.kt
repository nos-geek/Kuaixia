package com.kuaixia.app.data.download.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 快夏 Room 数据库。 */
@Database(
    entities = [DownloadTaskEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class KuaixiaDatabase : RoomDatabase() {

    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        @Volatile
        private var instance: KuaixiaDatabase? = null

        /** v1 → v2：Phase 4 新增 kind（下载类型）列，旧任务默认 DIRECT（DASH 由字段兼容推导）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE download_task ADD COLUMN kind TEXT NOT NULL DEFAULT 'DIRECT'",
                )
            }
        }

        /** v2 → v3：Phase 5 新增 source（解析来源）列，旧数据默认空串（非 WebView）。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE download_task ADD COLUMN source TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        fun get(context: Context): KuaixiaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KuaixiaDatabase::class.java,
                    "kuaixia.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
