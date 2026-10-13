package com.example.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "edit_history")
data class EditHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalUri: String,
    val savedUri: String,
    val timestamp: Long,
    val preset: String,
    val crop: String,
    val summary: String
)

@Dao
interface EditHistoryDao {
    @Query("SELECT * FROM edit_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<EditHistory>>

    @Insert
    suspend fun insert(history: EditHistory)

    // F16: keep the history bounded — trim everything but the newest 50 rows after each insert.
    @Query("DELETE FROM edit_history WHERE id NOT IN (SELECT id FROM edit_history ORDER BY timestamp DESC, id DESC LIMIT 50)")
    suspend fun trimHistory()
}

@Database(entities = [EditHistory::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun editHistoryDao(): EditHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "editor_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
