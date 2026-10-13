package com.example.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F16: edit history is bounded — after any insert, only the newest 50 rows remain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EditHistoryDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun history(i: Int) = EditHistory(
        originalUri = "content://original/$i",
        savedUri = "content://saved/$i",
        timestamp = 1_000_000L + i,   // strictly increasing so ordering is deterministic
        preset = "AUTO",
        crop = "Original",
        summary = "row $i"
    )

    @Test
    fun `trim keeps the newest 50 rows`() = runBlocking {
        repeat(60) { i ->
            db.editHistoryDao().insert(history(i))
            db.editHistoryDao().trimHistory()
        }
        val rows = firstBlocking()
        assertEquals("History must be capped at 50 rows", 50, rows.size)
        // Newest 50 of 60 (i = 0..59) = i 10..59 = timestamps 1_000_010 .. 1_000_059.
        assertEquals(1_000_059L, rows.first().timestamp)
        assertEquals(1_000_010L, rows.last().timestamp)
    }

    @Test
    fun `trim is a no-op below the cap`() = runBlocking {
        repeat(10) { i ->
            db.editHistoryDao().insert(history(i))
            db.editHistoryDao().trimHistory()
        }
        assertEquals(10, firstBlocking().size)
    }

    @Test
    fun `history is ordered newest first`() = runBlocking {
        repeat(5) { i -> db.editHistoryDao().insert(history(i)) }
        val rows = firstBlocking()
        assertTrue(rows.size >= 2)
        assertTrue(
            "Rows must be ordered by timestamp DESC",
            rows.zipWithNext().all { (a, b) -> a.timestamp >= b.timestamp }
        )
    }

    // Flow<List<EditHistory>> needs a first() collector on the test dispatcher.
    private fun firstBlocking(): List<EditHistory> =
        runBlocking { db.editHistoryDao().getAllHistory().first() }
}
