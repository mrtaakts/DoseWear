package com.example.dosewear.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.data.DoseStatus
import com.example.dosewear.data.Prefs
import com.example.dosewear.presentation.MainActivity
import java.time.LocalDateTime

/**
 * Tum zamanlama burada. WorkManager KULLANILMIYOR: dakika hassasiyeti garantisi yok.
 *
 * Iki mod:
 *  - setAlarmClock()  -> sistemin en yuksek oncelikli alarmi. Doze'dan ve OEM
 *    (Xiaomi HyperOS dahil) arka plan kisitlarindan muaf tutulur. Varsayilan.
 *  - setExactAndAllowWhileIdle() -> alarm ikonu istemeyenler icin yedek mod.
 *
 * Pil: hicbir servis, hicbir periyodik is yok. Sadece gerektigi anda tek bir alarm.
 * Bir alarm tetiklendiginde zincirin bir sonraki halkasi kurulur.
 */
object AlarmScheduler {

    private const val TAG = "DoseAlarm"

    const val ACTION_FIRE_REMINDER = "com.example.dosewear.FIRE_REMINDER"
    const val ACTION_FIRE_SNOOZE = "com.example.dosewear.FIRE_SNOOZE"
    const val ACTION_NAG = "com.example.dosewear.NAG"

    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_DOSE_LOG_ID = "dose_log_id"
    const val EXTRA_NOTIF_KEY = "notif_key"
    const val EXTRA_DOSE_IDS = "dose_ids"
    const val EXTRA_SCHEDULED_AT = "scheduled_at"

    private const val RC_REMINDER_BASE = 1_000_000
    private const val RC_SNOOZE_BASE = 5_000_000
    private const val RC_NAG_BASE = 7_000_000
    private const val RC_SHOW_INTENT = 909090

    private fun am(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun canScheduleExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am(context).canScheduleExactAlarms()
        else true

    /* ------------------------------------------------------------------ */
    /*  Tek noktadan kurulum                                               */
    /* ------------------------------------------------------------------ */

    private fun schedule(context: Context, triggerAt: Long, pi: PendingIntent, tag: String) {
        val alarmManager = am(context)
        val prefs = Prefs(context)
        var mode: String
        try {
            if (prefs.highPriorityAlarms && canScheduleExact(context)) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    RC_SHOW_INTENT,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, showIntent),
                    pi
                )
                mode = "setAlarmClock"
            } else if (canScheduleExact(context)) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                mode = "setExactAndAllowWhileIdle"
            } else {
                // Kesin alarm izni yoksa en azindan yaklasik alarm kur.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                mode = "setAndAllowWhileIdle (izin yok!)"
            }
        } catch (se: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            mode = "fallback (SecurityException)"
        }
        prefs.lastScheduleMode = mode
        Log.i(TAG, "kuruldu[$tag] mode=$mode at=$triggerAt")
    }

    /* ------------------------------------------------------------------ */
    /*  Hatirlatici alarmlari                                              */
    /* ------------------------------------------------------------------ */

    private fun reminderPi(context: Context, reminderId: Long, scheduledAt: Long): PendingIntent {
        val intent = Intent(context, DoseAlarmReceiver::class.java).apply {
            action = ACTION_FIRE_REMINDER
            data = android.net.Uri.parse("dosewear://reminder/$reminderId")
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
        }
        return PendingIntent.getBroadcast(
            context,
            RC_REMINDER_BASE + reminderId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Verilen hatirlaticinin bir sonraki tetiklenmesini kurar. */
    suspend fun scheduleNext(context: Context, reminderId: Long) {
        val repo = DoseRepository.get(context)
        val reminder = repo.reminders.byId(reminderId) ?: return
        cancelReminder(context, reminderId)
        if (!reminder.enabled) return
        val next = reminder.nextTriggerAt(LocalDateTime.now())
        if (next <= 0) return
        schedule(context, next, reminderPi(context, reminderId, next), "reminder#$reminderId")
    }

    fun cancelReminder(context: Context, reminderId: Long) {
        am(context).cancel(reminderPi(context, reminderId, 0L))
    }

    /* ------------------------------------------------------------------ */
    /*  Erteleme alarmlari (doz basina ayri)                               */
    /* ------------------------------------------------------------------ */

    private fun snoozePi(context: Context, doseLogId: Long): PendingIntent {
        val intent = Intent(context, DoseAlarmReceiver::class.java).apply {
            action = ACTION_FIRE_SNOOZE
            data = android.net.Uri.parse("dosewear://snooze/$doseLogId")
            putExtra(EXTRA_DOSE_LOG_ID, doseLogId)
        }
        return PendingIntent.getBroadcast(
            context,
            RC_SNOOZE_BASE + doseLogId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun scheduleSnooze(context: Context, doseLogId: Long, triggerAt: Long) {
        schedule(context, triggerAt, snoozePi(context, doseLogId), "snooze#$doseLogId")
    }

    fun cancelSnooze(context: Context, doseLogId: Long) {
        am(context).cancel(snoozePi(context, doseLogId))
    }

    /* ------------------------------------------------------------------ */
    /*  Israrli hatirlatma (nag) alarmi - grup basina                      */
    /* ------------------------------------------------------------------ */

    private fun nagPi(context: Context, notifKey: Long, doseIds: LongArray): PendingIntent {
        val intent = Intent(context, DoseAlarmReceiver::class.java).apply {
            action = ACTION_NAG
            data = android.net.Uri.parse("dosewear://nag/$notifKey")
            putExtra(EXTRA_NOTIF_KEY, notifKey)
            putExtra(EXTRA_DOSE_IDS, doseIds)
        }
        return PendingIntent.getBroadcast(
            context,
            RC_NAG_BASE + (kotlin.math.abs(notifKey) % 100_000L).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun scheduleNag(context: Context, notifKey: Long, doseIds: LongArray, minutes: Int) {
        if (minutes <= 0 || doseIds.isEmpty()) return
        schedule(
            context,
            System.currentTimeMillis() + minutes * 60_000L,
            nagPi(context, notifKey, doseIds),
            "nag#$notifKey"
        )
    }

    fun cancelNag(context: Context, notifKey: Long) {
        am(context).cancel(nagPi(context, notifKey, LongArray(0)))
    }

    /* ------------------------------------------------------------------ */
    /*  Yeniden kurulum (boot, saat degisimi, uygulama guncellemesi)       */
    /* ------------------------------------------------------------------ */

    /**
     * Cihaz yeniden baslatildiginda AlarmManager'daki her sey silinir.
     * Burada hem tum aktif hatirlaticilar hem de bekleyen ertelemeler geri kurulur.
     */
    suspend fun rescheduleAll(context: Context) {
        val repo = DoseRepository.get(context)

        repo.reminders.allWithItems().forEach { rwi ->
            if (rwi.reminder.enabled) scheduleNext(context, rwi.reminder.id)
            else cancelReminder(context, rwi.reminder.id)
        }

        val now = System.currentTimeMillis()
        val open = repo.logs.openDoses()

        // Ertelenmis dozlar: her biri kendi zamaninda geri gelsin.
        open.filter { it.status == DoseStatus.SNOOZED }.forEach { log ->
            val at = log.nextRemindAt ?: return@forEach
            scheduleSnooze(context, log.id, maxOf(at, now + 60_000L))
        }

        // Onaylanmamis dozlar: israrli hatirlatmayi grup bazinda yeniden baslat.
        open.filter { it.status == DoseStatus.PENDING }
            .groupBy { it.groupKey }
            .forEach { (groupKey, list) ->
                scheduleNag(
                    context,
                    groupKey,
                    list.map { it.id }.toLongArray(),
                    repo.prefs.nagIntervalMinutes
                )
            }

        Log.i(TAG, "Tum alarmlar yeniden kuruldu (${open.size} acik doz)")
    }
}
