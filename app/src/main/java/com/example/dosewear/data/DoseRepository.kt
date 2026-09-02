package com.example.dosewear.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Tum is mantiginin tek kapisi. Receiver'lar, ekranlar ve Tile hep buradan okur/yazar.
 */
class DoseRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val db = AppDatabase.get(app)
    val supplements: SupplementDao = db.supplementDao()
    val reminders: ReminderDao = db.reminderDao()
    val logs: DoseLogDao = db.doseLogDao()
    val prefs = Prefs(app)

    /* ----------------------------------------------------------------- */
    /*  Okuma akislari                                                    */
    /* ----------------------------------------------------------------- */

    fun observeSupplements(): Flow<List<Supplement>> = supplements.observeAll()
    fun observeActiveSupplements(): Flow<List<Supplement>> = supplements.observeActive()
    fun observeLowStock(): Flow<List<Supplement>> = supplements.observeLowStock()
    fun observeSupplement(id: Long): Flow<Supplement?> = supplements.observeById(id)
    fun observeReminders(): Flow<List<ReminderWithItems>> = reminders.observeAllWithItems()
    fun observeReminder(id: Long): Flow<ReminderWithItems?> = reminders.observeWithItems(id)
    fun observeOpenDoses(): Flow<List<DoseLog>> = logs.observeOpenDoses()
    fun observeGroup(groupKey: Long): Flow<List<DoseLog>> = logs.observeGroup(groupKey)
    fun observeRecentLogs(limit: Int = 120): Flow<List<DoseLog>> = logs.observeRecent(limit)

    /** Gecmis ekrani icin son [days] gun, en yeni ustte. */
    fun observeLastDays(days: Long = 7): Flow<List<DoseLog>> = logs.observeSince(
        LocalDate.now().minusDays(days - 1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )
    fun observeLogsForSupplement(id: Long, limit: Int = 60): Flow<List<DoseLog>> =
        logs.observeForSupplement(id, limit)

    fun observeTodayTaken(): Flow<Int> = logs.observeTakenCount(startOfToday(), endOfToday())
    fun observeTodayTotal(): Flow<Int> = logs.observeTotalCount(startOfToday(), endOfToday())
    fun observeToday(): Flow<List<DoseLog>> = logs.observeBetween(startOfToday(), endOfToday())

    /* ----------------------------------------------------------------- */
    /*  Stok islemleri                                                    */
    /* ----------------------------------------------------------------- */

    suspend fun upsertSupplement(s: Supplement): Long =
        if (s.id == 0L) supplements.insert(s) else { supplements.update(s); s.id }

    suspend fun deleteSupplement(s: Supplement) = supplements.delete(s)

    suspend fun adjustStock(id: Long, delta: Double) {
        val s = supplements.byId(id) ?: return
        val newStock = (s.stock + delta).coerceAtLeast(0.0)
        supplements.update(
            s.copy(
                stock = newStock,
                // Stok esigin uzerine ciktiysa uyari bayragini sifirla ki
                // bir dahaki dususte tekrar uyarabilelim.
                lowStockAlerted = if (newStock > s.lowStockThreshold) false else s.lowStockAlerted
            )
        )
    }

    /** "Satin aldim" akisi: kutu miktarini ekler, uyari bayragini sifirlar. */
    suspend fun refill(id: Long, amount: Double? = null) {
        val s = supplements.byId(id) ?: return
        supplements.update(
            s.copy(
                stock = s.stock + (amount ?: s.refillAmount),
                lowStockAlerted = false
            )
        )
    }

    /* ----------------------------------------------------------------- */
    /*  Hatirlatici islemleri                                             */
    /* ----------------------------------------------------------------- */

    suspend fun saveReminder(reminder: Reminder, items: List<ReminderItem>): Long {
        val id = if (reminder.id == 0L) reminders.insert(reminder)
        else { reminders.update(reminder); reminder.id }
        reminders.clearItems(id)
        items.forEach { reminders.insertItem(it.copy(id = 0, reminderId = id)) }
        return id
    }

    suspend fun deleteReminder(r: Reminder) = reminders.delete(r)
    suspend fun setReminderEnabled(id: Long, enabled: Boolean) = reminders.setEnabled(id, enabled)

    /* ----------------------------------------------------------------- */
    /*  Alarm tetiklendiginde doz kayitlarini olustur                      */
    /* ----------------------------------------------------------------- */

    /**
     * Hatirlatici tetiklendiginde her takviye icin bir PENDING satiri acar.
     * groupKey = planlanan zaman -> ayni tetiklemedeki dozlar tek bildirimde toplanir.
     * Ayni doz zaten varsa tekrar olusturmaz (alarm iki kez dusse bile).
     */
    suspend fun materializeDoses(reminderId: Long, scheduledAt: Long): List<DoseLog> {
        val rwi = reminders.withItems(reminderId) ?: return emptyList()
        val groupKey = groupKeyFor(reminderId, scheduledAt)
        val created = mutableListOf<DoseLog>()
        for (iws in rwi.items) {
            val sup = iws.supplement ?: continue
            if (!sup.active) continue
            if (logs.existsFor(reminderId, sup.id, scheduledAt) > 0) continue
            val log = DoseLog(
                reminderId = reminderId,
                supplementId = sup.id,
                supplementName = sup.name,
                supplementUnit = sup.unit,
                amount = iws.item.amount,
                scheduledAt = scheduledAt,
                status = DoseStatus.PENDING,
                groupKey = groupKey
            )
            val id = logs.insert(log)
            created += log.copy(id = id)
        }
        return created
    }

    fun groupKeyFor(reminderId: Long, scheduledAt: Long): Long =
        scheduledAt / 60000L * 1000L + (reminderId % 997L)

    /* ----------------------------------------------------------------- */
    /*  Onay / erteleme / atlama                                          */
    /* ----------------------------------------------------------------- */

    /**
     * Dozu "alindi" isaretler ve stogu dusurur.
     * @return stok esigin altina yeni dustuyse ilgili takviye, aksi halde null.
     */
    suspend fun markTaken(doseLogId: Long): Supplement? {
        val log = logs.byId(doseLogId) ?: return null
        if (log.status == DoseStatus.TAKEN) return null // idempotent
        logs.update(
            log.copy(
                status = DoseStatus.TAKEN,
                actedAt = System.currentTimeMillis(),
                nextRemindAt = null
            )
        )
        val sup = supplements.byId(log.supplementId) ?: return null
        val newStock = (sup.stock - log.amount).coerceAtLeast(0.0)
        val crossedThreshold = newStock <= sup.lowStockThreshold && !sup.lowStockAlerted
        supplements.update(
            sup.copy(
                stock = newStock,
                lowStockAlerted = if (crossedThreshold) true else sup.lowStockAlerted
            )
        )
        return if (crossedThreshold) sup.copy(stock = newStock) else null
    }

    suspend fun markSkipped(doseLogId: Long) {
        val log = logs.byId(doseLogId) ?: return
        if (log.status == DoseStatus.TAKEN) return
        logs.update(
            log.copy(
                status = DoseStatus.SKIPPED,
                actedAt = System.currentTimeMillis(),
                nextRemindAt = null
            )
        )
    }

    /**
     * Dozu erteler. Ertelemenin nirengisi:
     *  - taban sure hatirlaticidan (varsa) yoksa genel ayardan gelir
     *  - uzerine 0..jitter dakika rastgele sapma eklenir
     *  - sapma doz basina ayri hesaplanir -> ayni anda ertelenen 3 ilac
     *    ayni dakikaya dusmez, birbirini ezmez.
     * @return yeni hatirlatma zamani (epoch ms) veya null (limit doldu -> MISSED).
     */
    suspend fun snooze(doseLogId: Long): Long? {
        val log = logs.byId(doseLogId) ?: return null
        if (log.status == DoseStatus.TAKEN || log.status == DoseStatus.SKIPPED) return null

        val reminder = reminders.byId(log.reminderId)
        val base = reminder?.snoozeMinutes ?: prefs.defaultSnoozeMinutes
        val jitter = reminder?.snoozeJitterMinutes ?: prefs.defaultJitterMinutes
        val maxSnoozes = reminder?.maxSnoozes ?: prefs.defaultMaxSnoozes

        if (log.snoozeCount >= maxSnoozes) {
            logs.update(
                log.copy(
                    status = DoseStatus.MISSED,
                    actedAt = System.currentTimeMillis(),
                    nextRemindAt = null
                )
            )
            return null
        }

        val extra = if (jitter > 0) Random.nextInt(0, jitter + 1) else 0
        val next = System.currentTimeMillis() + (base + extra) * 60_000L
        logs.update(
            log.copy(
                status = DoseStatus.SNOOZED,
                snoozeCount = log.snoozeCount + 1,
                nextRemindAt = next,
                nagCount = 0
            )
        )
        return next
    }

    suspend fun markMissed(doseLogId: Long) {
        val log = logs.byId(doseLogId) ?: return
        if (log.status == DoseStatus.TAKEN || log.status == DoseStatus.SKIPPED) return
        logs.update(log.copy(status = DoseStatus.MISSED, nextRemindAt = null))
    }

    suspend fun bumpNag(doseLogId: Long): Int {
        val log = logs.byId(doseLogId) ?: return 0
        val n = log.nagCount + 1
        logs.update(log.copy(nagCount = n))
        return n
    }

    suspend fun openDosesOfGroup(groupKey: Long): List<DoseLog> =
        logs.byGroup(groupKey).filter { it.status == DoseStatus.PENDING }

    /** Verilen id'lerden hala onay bekleyenler. */
    suspend fun pendingOf(ids: List<Long>): List<DoseLog> =
        if (ids.isEmpty()) emptyList()
        else logs.byIds(ids).filter { it.status == DoseStatus.PENDING }

    suspend fun dosesOf(ids: List<Long>): List<DoseLog> =
        if (ids.isEmpty()) emptyList() else logs.byIds(ids)

    /** Erteleme suresi dolunca dozu tekrar "bekliyor" durumuna alir. */
    suspend fun reactivate(doseLogId: Long): DoseLog? {
        val log = logs.byId(doseLogId) ?: return null
        if (log.status != DoseStatus.SNOOZED) return null
        val updated = log.copy(status = DoseStatus.PENDING, nextRemindAt = null, nagCount = 0)
        logs.update(updated)
        return updated
    }

    /* ----------------------------------------------------------------- */
    /*  Sirada ne var?                                                    */
    /* ----------------------------------------------------------------- */

    suspend fun nextUpcoming(): UpcomingDose? = upcoming(1).firstOrNull()

    /**
     * Siradaki [limit] doz, zamana gore sirali.
     * Tek hatirlatici varsa onun bir sonraki iki tekrarini da dondurebilir;
     * Tile iki satir gosterebilsin diye her hatirlatici icin iki occurrence bakiyoruz.
     */
    suspend fun upcoming(limit: Int = 2): List<UpcomingDose> {
        val now = LocalDateTime.now()
        val out = mutableListOf<UpcomingDose>()
        for (rwi in reminders.activeWithItems()) {
            val names = rwi.items.mapNotNull { it.supplement?.name }
            val first = rwi.reminder.nextTriggerAt(now)
            if (first <= 0) continue
            out += UpcomingDose(rwi.reminder.id, first, rwi.title(), names)

            val afterFirst = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(first + 60_000L), ZoneId.systemDefault()
            )
            val second = rwi.reminder.nextTriggerAt(afterFirst)
            if (second > 0) out += UpcomingDose(rwi.reminder.id, second, rwi.title(), names)
        }
        return out.sortedBy { it.triggerAt }.take(limit)
    }

    /** Bugun icin uyum orani (alinan / planlanan). */
    suspend fun todayAdherence(): Pair<Int, Int> =
        logs.takenCount(startOfToday(), endOfToday()) to logs.totalCount(startOfToday(), endOfToday())

    /**
     * Ust uste kac gundur o gunun tum dozlari alindi.
     * Tek GROUP BY sorgusu -> arayuz acilirken veritabanini kilitlemiyor.
     */
    suspend fun streakDays(maxLookBack: Int = 60): Int {
        val from = LocalDate.now().minusDays(maxLookBack.toLong())
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = logs.dayStats(from).associateBy { it.day }

        var streak = 0
        for (i in 0 until maxLookBack) {
            val day = LocalDate.now().minusDays(i.toLong()).toString() // yyyy-MM-dd
            val stat = stats[day]
            if (stat == null || stat.total == 0) {
                if (i == 0) continue else break // bugun hic doz yoksa seriyi kirma
            }
            if (stat.taken >= stat.total) streak++ else break
        }
        return streak
    }

    /** Gecmisi sinirla: 1 yildan eski kayitlari sil (pil/yer dostu). */
    suspend fun purgeOldLogs() {
        logs.purgeOlderThan(System.currentTimeMillis() - 365L * 24 * 3600 * 1000)
    }

    companion object {
        @Volatile
        private var INSTANCE: DoseRepository? = null

        fun get(context: Context): DoseRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DoseRepository(context).also { INSTANCE = it }
            }

        fun startOfToday(): Long =
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        fun endOfToday(): Long =
            LocalDate.now().atTime(LocalTime.MAX).atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
    }
}
