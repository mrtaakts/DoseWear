package com.example.dosewear.notif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.dosewear.R
import com.example.dosewear.alarm.DoseActionReceiver
import com.example.dosewear.data.DoseLog
import com.example.dosewear.data.Fmt
import com.example.dosewear.data.Prefs
import com.example.dosewear.data.Supplement
import com.example.dosewear.presentation.AlarmActivity
import com.example.dosewear.util.Texts
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Bildirimler + ALARM TITRESIMI.
 *
 * Titresim tek seferlik degil: onaylanana/ertelenene kadar (ya da ayarlanan sure
 * dolana kadar) kesintisiz tekrar eden bir dalga formu calisir. Kanalin kendi
 * titresimi kapatildi ki cift titresim olmasin.
 */
object DoseNotifier {

    // Kanal ayarlari olusturulduktan sonra degistirilemedigi icin surum sonekli.
    const val CH_DOSE = "dose_alarm_v2"
    const val CH_STOCK = "stock_alert_v2"
    private val LEGACY_CHANNELS = listOf("dose_alarm", "stock_alert", "info")

    const val SNOOZE_KEY_OFFSET = 900_000_000L

    private const val ID_LOW_STOCK_BASE = 20_000
    private const val ID_DOSE_BASE = 1_000

    /**
     * Bir alarm dongusu tam 2 saniye: cift titresim + sessizlik.
     * Ses de 2 saniyede bir tekrarladigi icin ikisi ust uste biniyor.
     */
    private val ALARM_CYCLE_TIMINGS = longArrayOf(0, 400, 200, 400, 1000)
    private val ALARM_CYCLE_AMPS = intArrayOf(0, 255, 0, 255, 0)
    private val STOCK_PATTERN = longArrayOf(0, 200, 150, 200)

    /* ------------------------------------------------------------------ */
    /*  Kanallar                                                           */
    /* ------------------------------------------------------------------ */

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        LEGACY_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }

        val dose = NotificationChannel(
            CH_DOSE,
            context.getString(R.string.chan_dose),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.chan_dose_desc)
            // Titresimi biz yonetiyoruz (surekli dongu) -> kanal titresimi kapali.
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableLights(true)
        }

        val stock = NotificationChannel(
            CH_STOCK,
            context.getString(R.string.chan_stock),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.chan_stock_desc)
            enableVibration(true)
            vibrationPattern = STOCK_PATTERN
            setSound(null, null)
        }

        nm.createNotificationChannel(dose)
        nm.createNotificationChannel(stock)
    }

    /* ------------------------------------------------------------------ */
    /*  Alarm titresimi                                                    */
    /* ------------------------------------------------------------------ */

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * API 33+ VibrationAttributes bekliyor; AudioAttributes'li imza deprecated.
     * minSdk 30 oldugu icin eski yol yedek olarak duruyor.
     */
    private fun play(context: Context, effect: VibrationEffect) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        }
    }

    /**
     * Onay gelene kadar (en fazla [Prefs.alertDurationSeconds] boyunca) titrer.
     * Sonsuz dongu yerine sureyi kaplayacak kadar tekrarlanan sonlu bir dalga formu
     * kullaniyoruz: bir sey ters giderse bile titresim kendiliginden biter.
     */
    fun startAlarmVibration(context: Context) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return

        val seconds = Prefs(context).alertDurationSeconds
        val cycleMs = ALARM_CYCLE_TIMINGS.sum()
        val repeats = ceil(seconds * 1000.0 / cycleMs).toInt().coerceIn(1, 200)

        val timings = LongArray(ALARM_CYCLE_TIMINGS.size * repeats)
        val amps = IntArray(ALARM_CYCLE_AMPS.size * repeats)
        for (r in 0 until repeats) {
            System.arraycopy(
                ALARM_CYCLE_TIMINGS, 0, timings, r * ALARM_CYCLE_TIMINGS.size,
                ALARM_CYCLE_TIMINGS.size
            )
            System.arraycopy(
                ALARM_CYCLE_AMPS, 0, amps, r * ALARM_CYCLE_AMPS.size,
                ALARM_CYCLE_AMPS.size
            )
        }

        val effect = runCatching {
            VibrationEffect.createWaveform(timings, amps, -1)
        }.getOrElse {
            // Bazi cihazlar amplitude kontrolunu desteklemiyor -> sade dalga formu.
            VibrationEffect.createWaveform(timings, -1)
        }
        play(context, effect)
    }

    /** Onay/erteleme/atlama ya da onay ekranina bakildiginda cagrilir. */
    fun stopAlarmVibration(context: Context) {
        runCatching { vibrator(context)?.cancel() }
    }

    private fun shortVibrate(context: Context, pattern: LongArray) {
        play(context, VibrationEffect.createWaveform(pattern, -1))
    }

    /* ------------------------------------------------------------------ */
    /*  Doz bildirimi                                                      */
    /* ------------------------------------------------------------------ */

    fun doseNotificationId(notifKey: Long): Int =
        ID_DOSE_BASE + (abs(notifKey) % 100_000L).toInt()

    private fun actionPi(
        context: Context,
        action: String,
        doseLogId: Long,
        notifKey: Long,
        doseIds: LongArray,
        supplementId: Long,
        rc: Int
    ): PendingIntent {
        val intent = Intent(context, DoseActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("dosewear://action/$action/$doseLogId/$notifKey/$supplementId")
            putExtra(DoseActionReceiver.EXTRA_DOSE_LOG_ID, doseLogId)
            putExtra(DoseActionReceiver.EXTRA_NOTIF_KEY, notifKey)
            putExtra(DoseActionReceiver.EXTRA_DOSE_IDS, doseIds)
            putExtra(DoseActionReceiver.EXTRA_SUPPLEMENT_ID, supplementId)
        }
        return PendingIntent.getBroadcast(
            context, rc, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun openAlarmPi(
        context: Context,
        notifKey: Long,
        doseIds: LongArray,
        rc: Int
    ): PendingIntent {
        val intent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            data = Uri.parse("dosewear://alarm/$notifKey")
            putExtra(AlarmActivity.EXTRA_NOTIF_KEY, notifKey)
            putExtra(AlarmActivity.EXTRA_DOSE_IDS, doseIds)
        }
        return PendingIntent.getActivity(
            context, rc, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * Bildirimi kurar ama GOSTERMEZ. AlarmAlertService bunu foreground
     * bildirimi olarak kullaniyor; boylece tek bir bildirim hem alarmi
     * hem servisi temsil ediyor.
     */
    fun buildDoseNotification(
        context: Context,
        notifKey: Long,
        pending: List<DoseLog>,
        nagLevel: Int = 0,
        fullScreen: Boolean = true,
        alert: Boolean = true
    ): Notification {
        val id = doseNotificationId(notifKey)
        val rcBase = id * 8
        val doseIds = pending.map { it.id }.toLongArray()

        val timeText = Fmt.hhmm(pending.first().scheduledAt)
        val title = if (pending.size == 1)
            context.getString(R.string.notif_title_single, pending.first().supplementName, timeText)
        else
            context.getString(R.string.notif_title_multi, pending.size, timeText)

        val body = pending.joinToString("\n") {
            "• ${it.supplementName}  ${Texts.amountText(context, it)}"
        }
        val urgencyPrefix = when {
            nagLevel >= 2 -> context.getString(R.string.notif_urgent_2)
            nagLevel == 1 -> context.getString(R.string.notif_urgent_1)
            else -> ""
        }

        val builder = NotificationCompat.Builder(context, CH_DOSE)
            .setSmallIcon(R.drawable.ic_dose)
            .setContentTitle(title)
            .setContentText(pending.joinToString(", ") { it.supplementName })
            .setStyle(NotificationCompat.BigTextStyle().bigText(urgencyPrefix + body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(!alert)
            .setContentIntent(openAlarmPi(context, notifKey, doseIds, rcBase))
            .setWhen(pending.first().scheduledAt)
            .setShowWhen(true)

        if (fullScreen && alert) {
            builder.setFullScreenIntent(
                openAlarmPi(context, notifKey, doseIds, rcBase + 1), true
            )
        }

        if (pending.size == 1) {
            val log = pending.first()
            builder.addAction(
                R.drawable.ic_check, context.getString(R.string.action_take),
                actionPi(
                    context, DoseActionReceiver.ACTION_TAKE, log.id, notifKey,
                    doseIds, log.supplementId, rcBase + 2
                )
            )
            builder.addAction(
                R.drawable.ic_snooze, context.getString(R.string.action_snooze),
                actionPi(
                    context, DoseActionReceiver.ACTION_SNOOZE, log.id, notifKey,
                    doseIds, log.supplementId, rcBase + 3
                )
            )
        } else {
            builder.addAction(
                R.drawable.ic_check, context.getString(R.string.action_confirm_each),
                openAlarmPi(context, notifKey, doseIds, rcBase + 4)
            )
            builder.addAction(
                R.drawable.ic_check_all, context.getString(R.string.action_take_all_short),
                actionPi(
                    context, DoseActionReceiver.ACTION_TAKE_ALL, 0L, notifKey,
                    doseIds, 0L, rcBase + 5
                )
            )
            builder.addAction(
                R.drawable.ic_snooze, context.getString(R.string.action_snooze_all),
                actionPi(
                    context, DoseActionReceiver.ACTION_SNOOZE_ALL, 0L, notifKey,
                    doseIds, 0L, rcBase + 6
                )
            )
        }

        return builder.build()
    }

    /**
     * Bildirimi olusturup gosterir ve olusturulan bildirimi dondurur
     * (cagiran taraf ayni nesneyi servise foreground bildirimi olarak verebilsin diye).
     * Ses/titresim burada BASLATILMAZ; onu AlarmAlertService yapar.
     */
    fun showDoseNotification(
        context: Context,
        notifKey: Long,
        pending: List<DoseLog>,
        nagLevel: Int = 0,
        fullScreen: Boolean = true,
        /** false -> bildirim sessizce guncellenir (tekrar dikkat cekmez). */
        alert: Boolean = true
    ): Notification? {
        if (pending.isEmpty()) {
            cancelDose(context, notifKey)
            return null
        }
        val notification =
            buildDoseNotification(context, notifKey, pending, nagLevel, fullScreen, alert)
        try {
            NotificationManagerCompat.from(context).notify(doseNotificationId(notifKey), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS verilmemis
        }
        return notification
    }

    fun cancelDose(context: Context, notifKey: Long) {
        NotificationManagerCompat.from(context).cancel(doseNotificationId(notifKey))
    }

    /* ------------------------------------------------------------------ */
    /*  Stok uyarisi                                                       */
    /* ------------------------------------------------------------------ */

    fun showLowStock(context: Context, s: Supplement) {
        val nm = NotificationManagerCompat.from(context)
        val id = ID_LOW_STOCK_BASE + s.id.toInt()
        val rcBase = id * 4

        val stockText = Texts.stockText(context, s)
        val head = if (s.stock <= 0) context.getString(R.string.notif_low_empty)
        else context.getString(R.string.notif_low_left, stockText)
        val refillText = Texts.amount(context, s.refillAmount, s.unit)

        val builder = NotificationCompat.Builder(context, CH_STOCK)
            .setSmallIcon(R.drawable.ic_stock)
            .setContentTitle(context.getString(R.string.notif_low_title, s.name))
            .setContentText(head)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notif_low_body, head, refillText))
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .addAction(
                R.drawable.ic_stock,
                context.getString(R.string.notif_bought, refillText),
                actionPi(
                    context, DoseActionReceiver.ACTION_REFILL, 0L, 0L,
                    LongArray(0), s.id, rcBase + 1
                )
            )
            .addAction(
                R.drawable.ic_snooze, context.getString(R.string.notif_later),
                actionPi(
                    context, DoseActionReceiver.ACTION_DISMISS_STOCK, 0L, 0L,
                    LongArray(0), s.id, rcBase + 2
                )
            )

        try {
            nm.notify(id, builder.build())
        } catch (_: SecurityException) {
        }
        shortVibrate(context, STOCK_PATTERN)
    }

    fun cancelLowStock(context: Context, supplementId: Long) {
        NotificationManagerCompat.from(context).cancel(ID_LOW_STOCK_BASE + supplementId.toInt())
    }
}
