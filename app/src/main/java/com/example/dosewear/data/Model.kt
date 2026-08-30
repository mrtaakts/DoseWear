package com.example.dosewear.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/* ------------------------------------------------------------------ */
/*  Durumlar                                                           */
/* ------------------------------------------------------------------ */

enum class DoseStatus {
    /** Hatirlatici tetiklendi, kullanici henuz bir sey yapmadi. */
    PENDING,

    /** Kullanici "Aldim" dedi. Stok bu anda dusuruldu. */
    TAKEN,

    /** Kullanici erteledi; [DoseLog.nextRemindAt] icin yeni alarm kuruldu. */
    SNOOZED,

    /** Kullanici bilerek atladi. Stok dusmez. */
    SKIPPED,

    /** Israrli hatirlatmalar bitti, hala onay yok. Stok dusmez. */
    MISSED
}

class Converters {
    @TypeConverter
    fun statusToString(value: DoseStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): DoseStatus =
        runCatching { DoseStatus.valueOf(value) }.getOrDefault(DoseStatus.PENDING)
}

/* ------------------------------------------------------------------ */
/*  1) Stok karti: her takviye/ilac icin tek kayit                     */
/* ------------------------------------------------------------------ */

@Entity(tableName = "supplements")
data class Supplement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** "D Vitamini", "Magnezyum Bisglisinat" ... */
    val name: String,

    /** Serbest metin doz bilgisi: "1000 IU", "500 mg" */
    val strength: String = "",

    /** Birim KODU: pcs / ml / drop / scoop / mg (ekranda dile gore etiketlenir). */
    val unit: String = "pcs",

    /** Elde kalan miktar. Doz onaylandikca dusulur. */
    val stock: Double = 0.0,

    /** Bu degere inince "az kaldi" uyarisi cikar. Varsayilan 5. */
    @ColumnInfo(name = "low_stock_threshold") val lowStockThreshold: Double = 5.0,

    /** "Satin aldim" denince stoga eklenecek miktar (bir kutu). */
    @ColumnInfo(name = "refill_amount") val refillAmount: Double = 30.0,

    /** UI renk paletindeki indeks. */
    @ColumnInfo(name = "color_index") val colorIndex: Int = 0,

    val active: Boolean = true,

    /** Ayni stok icin tekrar tekrar uyari cikmasin diye bayrak. */
    @ColumnInfo(name = "low_stock_alerted") val lowStockAlerted: Boolean = false,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) {
    val isLow: Boolean get() = stock <= lowStockThreshold
    val isEmpty: Boolean get() = stock <= 0.0
}

/* ------------------------------------------------------------------ */
/*  2) Hatirlatici: bir saat + gunler. Icinde 1..n takviye olabilir.   */
/* ------------------------------------------------------------------ */

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** "Sabah", "Aksam yemegi sonrasi" ... Bos ise saat gosterilir. */
    val label: String = "",

    val hour: Int,
    val minute: Int,

    /** bit0 = Pazartesi ... bit6 = Pazar. Varsayilan: her gun. */
    @ColumnInfo(name = "days_mask") val daysMask: Int = ALL_DAYS,

    val enabled: Boolean = true,

    /** Erteleme suresi (dk). Arayuzden hatirlatici bazinda ayarlanir. */
    @ColumnInfo(name = "snooze_minutes") val snoozeMinutes: Int = 10,

    /**
     * Ayni anda ertelenen dozlarin ust uste binmemesi icin
     * 0..jitter dakika arasi rastgele sapma eklenir. 0 = sapma yok.
     */
    @ColumnInfo(name = "snooze_jitter") val snoozeJitterMinutes: Int = 3,

    /** Kac kez ertelenebilir; asilinca doz MISSED olur. */
    @ColumnInfo(name = "max_snoozes") val maxSnoozes: Int = 3,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
) {
    fun timeText(): String = Fmt.hhmm(hour, minute)

    /** Bir sonraki tetiklenme zamani (epoch ms), yoksa -1. */
    fun nextTriggerAt(from: LocalDateTime = LocalDateTime.now()): Long {
        if (!enabled || daysMask == 0) return -1L
        for (i in 0..8L) {
            val date: LocalDate = from.toLocalDate().plusDays(i)
            val bit = date.dayOfWeek.value - 1 // Monday=1 -> bit0
            if (((daysMask shr bit) and 1) == 0) continue
            val dt = LocalDateTime.of(date, LocalTime.of(hour, minute))
            if (dt.isAfter(from)) {
                return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        return -1L
    }

    companion object {
        const val ALL_DAYS = 0b1111111
        const val WEEKDAYS = 0b0011111
        const val WEEKEND = 0b1100000

        fun maskHasDay(mask: Int, day: DayOfWeek): Boolean =
            ((mask shr (day.value - 1)) and 1) == 1
    }
}

/* ------------------------------------------------------------------ */
/*  3) Hatirlatici <-> Takviye baglantisi (bir hatirlaticida N ilac)   */
/* ------------------------------------------------------------------ */

@Entity(
    tableName = "reminder_items",
    foreignKeys = [
        ForeignKey(
            entity = Reminder::class,
            parentColumns = ["id"],
            childColumns = ["reminder_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Supplement::class,
            parentColumns = ["id"],
            childColumns = ["supplement_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("reminder_id"), Index("supplement_id")]
)
data class ReminderItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "reminder_id") val reminderId: Long,
    @ColumnInfo(name = "supplement_id") val supplementId: Long,
    /** Bu hatirlaticida kac birim alinacak. */
    val amount: Double = 1.0
)

/* ------------------------------------------------------------------ */
/*  4) Doz kaydi = onay mekanizmasi + gecmis                           */
/* ------------------------------------------------------------------ */

@Entity(
    tableName = "dose_logs",
    indices = [Index("scheduled_at"), Index("group_key"), Index("status"), Index("supplement_id")]
)
data class DoseLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    @ColumnInfo(name = "reminder_id") val reminderId: Long,
    @ColumnInfo(name = "supplement_id") val supplementId: Long,

    /** Takviye silinse bile gecmis okunabilsin diye isim burada da tutulur. */
    @ColumnInfo(name = "supplement_name") val supplementName: String,
    @ColumnInfo(name = "supplement_unit") val supplementUnit: String = "pcs",

    val amount: Double = 1.0,

    /** Planlanan zaman (epoch ms). */
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,

    /** Kullanicinin islem yaptigi an (alindi/atlandi). */
    @ColumnInfo(name = "acted_at") val actedAt: Long? = null,

    val status: DoseStatus = DoseStatus.PENDING,

    @ColumnInfo(name = "snooze_count") val snoozeCount: Int = 0,

    /** Ertelendiyse yeni hatirlatma zamani. */
    @ColumnInfo(name = "next_remind_at") val nextRemindAt: Long? = null,

    /**
     * Ayni tetiklemede olusan tum dozlar ayni group_key'i tasir.
     * Tek bildirimde gruplama ve "hepsini aldim" bunun uzerinden calisir.
     */
    @ColumnInfo(name = "group_key") val groupKey: Long,

    /** Israrli hatirlatma sayaci. */
    @ColumnInfo(name = "nag_count") val nagCount: Int = 0
) {
    fun scheduledText(): String = Fmt.hhmm(scheduledAt)
}

/* ------------------------------------------------------------------ */
/*  Iliskisel okuma modelleri                                          */
/* ------------------------------------------------------------------ */

data class ItemWithSupplement(
    @Embedded val item: ReminderItem,
    @Relation(parentColumn = "supplement_id", entityColumn = "id")
    val supplement: Supplement?
)

data class ReminderWithItems(
    @Embedded val reminder: Reminder,
    @Relation(
        entity = ReminderItem::class,
        parentColumn = "id",
        entityColumn = "reminder_id"
    )
    val items: List<ItemWithSupplement>
) {
    /** Etiket yoksa ilac isimleri; o da yoksa bos (cagiran taraf yerellestirilmis metin koyar). */
    fun title(): String =
        if (reminder.label.isNotBlank()) reminder.label
        else items.mapNotNull { it.supplement?.name }.joinToString(" + ")
}

/** Ana ekrandaki "sıradaki doz" özeti. */
data class UpcomingDose(
    val reminderId: Long,
    val triggerAt: Long,
    val title: String,
    val names: List<String>
)

/* ------------------------------------------------------------------ */
/*  Kucuk bicimlendirme yardimcilari                                   */
/* ------------------------------------------------------------------ */

object Fmt {

    private fun two(n: Int): String = if (n in 0..9) "0$n" else n.toString()

    /**
     * String.format() her cagrida yeni bir Formatter allocate ediyor ve liste
     * icinde kare basina onlarca kez cagriliyordu; elle bicimlendirme cok daha ucuz.
     */
    fun num(v: Double): String {
        val asLong = v.toLong()
        if (v == asLong.toDouble()) return asLong.toString()
        val scaled = Math.round(kotlin.math.abs(v) * 10)
        val sign = if (v < 0) "-" else ""
        return "$sign${scaled / 10}.${scaled % 10}"
    }

    fun hhmm(hour: Int, minute: Int): String = "${two(hour)}:${two(minute)}"

    fun hhmm(epochMs: Long): String {
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        return hhmm(t.hour, t.minute)
    }

    fun dayMonth(epochMs: Long): String {
        val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        return "${two(t.dayOfMonth)}.${two(t.monthValue)}"
    }
}

/** Gunluk uyum ozeti: seri hesabi icin TEK sorguyla doner. */
data class DayStat(
    val day: String,
    val total: Int,
    val taken: Int
)
