package com.example.dosewear.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplementDao {

    @Query("SELECT * FROM supplements ORDER BY active DESC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Supplement>>

    @Query("SELECT * FROM supplements WHERE active = 1 ORDER BY name COLLATE NOCASE ASC")
    fun observeActive(): Flow<List<Supplement>>

    @Query("SELECT * FROM supplements WHERE stock <= low_stock_threshold AND active = 1")
    fun observeLowStock(): Flow<List<Supplement>>

    @Query("SELECT * FROM supplements WHERE id = :id")
    fun observeById(id: Long): Flow<Supplement?>

    @Query("SELECT * FROM supplements WHERE id = :id")
    suspend fun byId(id: Long): Supplement?

    @Query("SELECT * FROM supplements ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<Supplement>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: Supplement): Long

    @Update
    suspend fun update(s: Supplement)

    @Delete
    suspend fun delete(s: Supplement)

    @Query("UPDATE supplements SET stock = MAX(0, stock - :amount) WHERE id = :id")
    suspend fun decrementStock(id: Long, amount: Double)

    @Query("UPDATE supplements SET stock = stock + :amount, low_stock_alerted = 0 WHERE id = :id")
    suspend fun refill(id: Long, amount: Double)

    @Query("UPDATE supplements SET low_stock_alerted = :flag WHERE id = :id")
    suspend fun setLowStockAlerted(id: Long, flag: Boolean)
}

@Dao
interface ReminderDao {

    @Transaction
    @Query("SELECT * FROM reminders ORDER BY hour ASC, minute ASC")
    fun observeAllWithItems(): Flow<List<ReminderWithItems>>

    @Transaction
    @Query("SELECT * FROM reminders WHERE id = :id")
    fun observeWithItems(id: Long): Flow<ReminderWithItems?>

    @Transaction
    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun withItems(id: Long): ReminderWithItems?

    @Transaction
    @Query("SELECT * FROM reminders WHERE enabled = 1")
    suspend fun activeWithItems(): List<ReminderWithItems>

    @Transaction
    @Query("SELECT * FROM reminders")
    suspend fun allWithItems(): List<ReminderWithItems>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): Reminder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(r: Reminder): Long

    @Update
    suspend fun update(r: Reminder)

    @Delete
    suspend fun delete(r: Reminder)

    @Query("UPDATE reminders SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ReminderItem): Long

    @Query("DELETE FROM reminder_items WHERE reminder_id = :reminderId")
    suspend fun clearItems(reminderId: Long)

    @Query("SELECT * FROM reminder_items WHERE reminder_id = :reminderId")
    suspend fun itemsOf(reminderId: Long): List<ReminderItem>

    @Query("SELECT COUNT(*) FROM reminder_items WHERE supplement_id = :supplementId")
    suspend fun usageCount(supplementId: Long): Int
}

@Dao
interface DoseLogDao {

    @Insert
    suspend fun insert(log: DoseLog): Long

    @Update
    suspend fun update(log: DoseLog)

    @Query("SELECT * FROM dose_logs WHERE id = :id")
    suspend fun byId(id: Long): DoseLog?

    @Query("SELECT * FROM dose_logs WHERE group_key = :groupKey ORDER BY id ASC")
    suspend fun byGroup(groupKey: Long): List<DoseLog>

    @Query("SELECT * FROM dose_logs WHERE group_key = :groupKey ORDER BY id ASC")
    fun observeGroup(groupKey: Long): Flow<List<DoseLog>>

    @Query("SELECT * FROM dose_logs WHERE id IN (:ids) ORDER BY id ASC")
    fun observeByIds(ids: List<Long>): Flow<List<DoseLog>>

    @Query("SELECT * FROM dose_logs WHERE id IN (:ids) ORDER BY id ASC")
    suspend fun byIds(ids: List<Long>): List<DoseLog>

    @Query("SELECT * FROM dose_logs WHERE status IN ('PENDING','SNOOZED') ORDER BY scheduled_at ASC")
    suspend fun openDoses(): List<DoseLog>

    @Query("SELECT * FROM dose_logs WHERE status IN ('PENDING','SNOOZED') ORDER BY scheduled_at ASC")
    fun observeOpenDoses(): Flow<List<DoseLog>>

    @Query("SELECT * FROM dose_logs WHERE scheduled_at BETWEEN :from AND :to ORDER BY scheduled_at ASC")
    fun observeBetween(from: Long, to: Long): Flow<List<DoseLog>>

    @Query("SELECT * FROM dose_logs ORDER BY scheduled_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DoseLog>>

    /** Gecmis ekrani: belirli bir tarihten bugune, en yeni ustte. */
    @Query("SELECT * FROM dose_logs WHERE scheduled_at >= :from ORDER BY scheduled_at DESC")
    fun observeSince(from: Long): Flow<List<DoseLog>>

    @Query("SELECT * FROM dose_logs WHERE supplement_id = :supplementId ORDER BY scheduled_at DESC LIMIT :limit")
    fun observeForSupplement(supplementId: Long, limit: Int): Flow<List<DoseLog>>

    @Query("SELECT COUNT(*) FROM dose_logs WHERE status = 'TAKEN' AND scheduled_at BETWEEN :from AND :to")
    fun observeTakenCount(from: Long, to: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM dose_logs WHERE scheduled_at BETWEEN :from AND :to")
    fun observeTotalCount(from: Long, to: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM dose_logs WHERE status = 'TAKEN' AND scheduled_at BETWEEN :from AND :to")
    suspend fun takenCount(from: Long, to: Long): Int

    @Query("SELECT COUNT(*) FROM dose_logs WHERE scheduled_at BETWEEN :from AND :to")
    suspend fun totalCount(from: Long, to: Long): Int

    /**
     * Seri (streak) hesabi icin gun gun ozet. Onceki surumde 60 gun x 2 sorgu
     * calisiyordu ve arayuzu kilitliyordu; artik tek sorgu.
     */
    @Query(
        "SELECT date(scheduled_at / 1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS total, " +
            "SUM(CASE WHEN status = 'TAKEN' THEN 1 ELSE 0 END) AS taken " +
            "FROM dose_logs WHERE scheduled_at >= :from " +
            "GROUP BY day ORDER BY day DESC"
    )
    suspend fun dayStats(from: Long): List<DayStat>

    /** Ayni doz iki kez olusmasin (alarm iki kez tetiklenirse). */
    @Query(
        "SELECT COUNT(*) FROM dose_logs WHERE reminder_id = :reminderId " +
            "AND supplement_id = :supplementId AND scheduled_at = :scheduledAt"
    )
    suspend fun existsFor(reminderId: Long, supplementId: Long, scheduledAt: Long): Int

    @Query("DELETE FROM dose_logs WHERE scheduled_at < :before")
    suspend fun purgeOlderThan(before: Long)

    @Query("DELETE FROM dose_logs")
    suspend fun deleteAll()
}
