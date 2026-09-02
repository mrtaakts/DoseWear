package com.example.dosewear.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.notif.DoseNotifier
import com.example.dosewear.presentation.AlarmActivity
import com.example.dosewear.util.Surfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Alarmlarin dustugu yer. Uc is yapar:
 *  1) Hatirlatici zamani geldi  -> doz kayitlarini ac, bildirim goster, sonraki alarmi kur
 *  2) Erteleme suresi doldu     -> o dozu tekrar hatirlat
 *  3) Israr (nag)               -> hala onaylanmadiysa yeniden durt, limit dolunca MISSED
 *
 * Not: goAsync() sistem tarafindan ~10 sn'lik bir wakelock ile korunur; ustune kisa
 * sureli kendi wakelock'umuzu da aliyoruz ki Room okumasi yarida kalmasin.
 */
class DoseAlarmReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext
        val firedAt = System.currentTimeMillis()

        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dosewear:alarm")
        wl.acquire(30_000L)

        scope.launch {
            try {
                handle(app, intent, firedAt)
            } catch (t: Throwable) {
                Log.e(TAG, "Alarm islenemedi", t)
            } finally {
                runCatching { if (wl.isHeld) wl.release() }
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent, firedAt: Long) {
        val repo = DoseRepository.get(context)
        val prefs = repo.prefs

        when (intent.action) {

            /* ---------------- 1) Hatirlatici zamani ---------------- */
            AlarmScheduler.ACTION_FIRE_REMINDER -> {
                val reminderId = intent.getLongExtra(AlarmScheduler.EXTRA_REMINDER_ID, -1L)
                val scheduledAt =
                    intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, firedAt)
                if (reminderId <= 0) return

                prefs.recordAlarmDelivery(scheduledAt, firedAt, "hatırlatıcı #$reminderId")

                val created = repo.materializeDoses(reminderId, scheduledAt)

                // Bir sonraki tekrari HER DURUMDA kur (zincir kopmasin).
                AlarmScheduler.scheduleNext(context, reminderId)

                if (created.isEmpty()) {
                    Log.i(TAG, "Hatirlatici #$reminderId icin acilacak doz yok")
                    Surfaces.refreshAll(context)
                    return
                }

                val groupKey = created.first().groupKey
                val notification = DoseNotifier.showDoseNotification(
                    context = context,
                    notifKey = groupKey,
                    pending = created,
                    nagLevel = 0,
                    fullScreen = prefs.fullScreenAlarm
                )
                notification?.let {
                    AlarmAlertService.start(
                        context, DoseNotifier.doseNotificationId(groupKey), it
                    )
                }
                if (prefs.fullScreenAlarm) {
                    openConfirmScreen(context, groupKey, created.map { it.id }.toLongArray())
                }
                AlarmScheduler.scheduleNag(
                    context, groupKey, created.map { it.id }.toLongArray(),
                    prefs.nagIntervalMinutes
                )
                Surfaces.refreshAll(context)
            }

            /* ---------------- 2) Erteleme suresi doldu ---------------- */
            AlarmScheduler.ACTION_FIRE_SNOOZE -> {
                val doseId = intent.getLongExtra(AlarmScheduler.EXTRA_DOSE_LOG_ID, -1L)
                if (doseId <= 0) return
                val log = repo.reactivate(doseId) ?: return
                prefs.recordAlarmDelivery(
                    log.nextRemindAt ?: firedAt, firedAt, "erteleme #$doseId"
                )

                val notifKey = DoseNotifier.SNOOZE_KEY_OFFSET + doseId
                val notification = DoseNotifier.showDoseNotification(
                    context = context,
                    notifKey = notifKey,
                    pending = listOf(log),
                    nagLevel = 0,
                    fullScreen = prefs.fullScreenAlarm
                )
                notification?.let {
                    AlarmAlertService.start(
                        context, DoseNotifier.doseNotificationId(notifKey), it
                    )
                }
                if (prefs.fullScreenAlarm) {
                    openConfirmScreen(context, notifKey, longArrayOf(doseId))
                }
                AlarmScheduler.scheduleNag(
                    context, notifKey, longArrayOf(doseId), prefs.nagIntervalMinutes
                )
                Surfaces.refreshAll(context)
            }

            /* ---------------- 3) Israrli hatirlatma ---------------- */
            AlarmScheduler.ACTION_NAG -> {
                val notifKey = intent.getLongExtra(AlarmScheduler.EXTRA_NOTIF_KEY, 0L)
                val ids = intent.getLongArrayExtra(AlarmScheduler.EXTRA_DOSE_IDS)?.toList()
                    ?: return
                val stillPending = repo.pendingOf(ids)
                if (stillPending.isEmpty()) {
                    AlarmAlertService.stop(context)
                    DoseNotifier.cancelDose(context, notifKey)
                    return
                }

                val nag = repo.bumpNag(stillPending.first().id)
                stillPending.drop(1).forEach { repo.bumpNag(it.id) }

                if (nag > prefs.maxNags) {
                    // Sabir bitti: kacirildi olarak isaretle, bildirimi kaldir.
                    stillPending.forEach { repo.markMissed(it.id) }
                    AlarmAlertService.stop(context)
                    DoseNotifier.cancelDose(context, notifKey)
                    Surfaces.refreshAll(context)
                    return
                }

                // Israrda tam ekran ACILMIYOR: ekran zorla one gelmesin,
                // sadece ses + titresim + kalici bildirim.
                val notification = DoseNotifier.showDoseNotification(
                    context = context,
                    notifKey = notifKey,
                    pending = stillPending,
                    nagLevel = nag,
                    fullScreen = false
                )
                notification?.let {
                    AlarmAlertService.start(
                        context, DoseNotifier.doseNotificationId(notifKey), it
                    )
                }
                AlarmScheduler.scheduleNag(
                    context, notifKey, stillPending.map { it.id }.toLongArray(),
                    prefs.nagIntervalMinutes
                )
            }
        }
    }

    /**
     * Onay ekranini ZORLA one getirir.
     *
     * setFullScreenIntent tek basina yetmiyor: Android o intent'i yalnizca cihaz
     * kilitliyken / ekran kapaliyken aktiviteye cevirir, saat acik ve kullanimdayken
     * bilerek sadece heads-up bildirim gosterir. Bu yuzden aktiviteyi ayrica
     * dogrudan baslatiyoruz.
     *
     * Arka plandan aktivite baslatma normalde engelli; kesin alarm (setAlarmClock /
     * setExact*) tetiklenen uygulama gecici izin listesine alindigi icin burada
     * calisiyor. OEM katmani yine de engellerse "uzerinde gosterme" izni devreye
     * girer (Ayarlar ekraninda), o da yoksa tam ekran bildirimi yedek kalir.
     */
    private fun openConfirmScreen(context: Context, notifKey: Long, doseIds: LongArray) {
        val intent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
            data = Uri.parse("dosewear://alarm/$notifKey")
            putExtra(AlarmActivity.EXTRA_NOTIF_KEY, notifKey)
            putExtra(AlarmActivity.EXTRA_DOSE_IDS, doseIds)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Onay ekrani acilamadi: ${it.message}") }
    }

    companion object {
        private const val TAG = "DoseAlarm"
    }
}
