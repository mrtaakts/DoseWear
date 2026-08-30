package com.example.dosewear.util

import android.content.Context
import com.example.dosewear.R
import com.example.dosewear.data.DoseLog
import com.example.dosewear.data.DoseStatus
import com.example.dosewear.data.Fmt
import com.example.dosewear.data.Reminder
import com.example.dosewear.data.Supplement
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Yerellestirilmis metinler tek yerde. Saatin dili Turkce ise values-tr,
 * degilse varsayilan (Ingilizce) kaynaklar kullanilir.
 */
object Texts {

    /* ---------------- Birimler ---------------- */

    /** Veritabaninda birim KODU tutulur; ekranda dile gore etiketlenir. */
    val UNIT_CODES = listOf("pcs", "ml", "drop", "scoop", "mg")

    fun unitLabel(ctx: Context, code: String): String = when (code) {
        "pcs" -> ctx.getString(R.string.unit_pcs)
        "ml" -> ctx.getString(R.string.unit_ml)
        "drop" -> ctx.getString(R.string.unit_drop)
        "scoop" -> ctx.getString(R.string.unit_scoop)
        "mg" -> ctx.getString(R.string.unit_mg)
        // Eski kayitlar (serbest metin) oldugu gibi gosterilir.
        else -> code
    }

    fun nextUnit(code: String): String {
        val i = UNIT_CODES.indexOf(code)
        return if (i < 0) UNIT_CODES.first() else UNIT_CODES[(i + 1) % UNIT_CODES.size]
    }

    fun stockText(ctx: Context, s: Supplement): String =
        "${Fmt.num(s.stock)} ${unitLabel(ctx, s.unit)}"

    fun amountText(ctx: Context, log: DoseLog): String =
        "${Fmt.num(log.amount)} ${unitLabel(ctx, log.supplementUnit)}"

    fun amount(ctx: Context, value: Double, unitCode: String): String =
        "${Fmt.num(value)} ${unitLabel(ctx, unitCode)}"

    /* ---------------- Gunler ---------------- */

    fun dayShort(ctx: Context, index: Int): String =
        ctx.resources.getStringArray(R.array.day_short)[index.coerceIn(0, 6)]

    fun daysText(ctx: Context, mask: Int): String = when (mask) {
        Reminder.ALL_DAYS -> ctx.getString(R.string.days_everyday)
        Reminder.WEEKDAYS -> ctx.getString(R.string.days_weekdays)
        Reminder.WEEKEND -> ctx.getString(R.string.days_weekend)
        0 -> ctx.getString(R.string.days_none)
        else -> (0..6).filter { ((mask shr it) and 1) == 1 }
            .joinToString(" ") { dayShort(ctx, it) }
    }

    fun dayLabel(ctx: Context, epochMs: Long): String {
        val date = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
            .toLocalDate()
        val today = LocalDate.now()
        return when (date) {
            today -> ctx.getString(R.string.day_today)
            today.minusDays(1) -> ctx.getString(R.string.day_yesterday)
            today.plusDays(1) -> ctx.getString(R.string.day_tomorrow)
            else -> "${date.dayOfMonth}.${date.monthValue} ${dayShort(ctx, date.dayOfWeek.value - 1)}"
        }
    }

    /* ---------------- Goreli zaman ---------------- */

    fun relative(ctx: Context, epochMs: Long, now: Long = System.currentTimeMillis()): String {
        var diff = (epochMs - now) / 1000
        val past = diff < 0
        diff = abs(diff)
        val d = diff / 86400
        val h = (diff % 86400) / 3600
        val m = (diff % 3600) / 60
        val core = when {
            d > 0 -> ctx.getString(R.string.rel_days, d.toInt(), h.toInt())
            h > 0 -> ctx.getString(R.string.rel_hours, h.toInt(), m.toInt())
            m > 0 -> ctx.getString(R.string.rel_minutes, m.toInt())
            else -> ctx.getString(R.string.rel_seconds)
        }
        return ctx.getString(if (past) R.string.rel_ago else R.string.rel_in, core)
    }

    /* ---------------- Doz durumu ---------------- */

    fun statusText(ctx: Context, s: DoseStatus): String = ctx.getString(
        when (s) {
            DoseStatus.PENDING -> R.string.status_pending
            DoseStatus.TAKEN -> R.string.status_taken
            DoseStatus.SNOOZED -> R.string.status_snoozed
            DoseStatus.SKIPPED -> R.string.status_skipped
            DoseStatus.MISSED -> R.string.status_missed
        }
    )

    fun statusEmoji(s: DoseStatus): String = when (s) {
        DoseStatus.TAKEN -> "✅"
        DoseStatus.SNOOZED -> "⏳"
        DoseStatus.SKIPPED -> "⤼"
        DoseStatus.MISSED -> "❌"
        DoseStatus.PENDING -> "🔔"
    }
}
