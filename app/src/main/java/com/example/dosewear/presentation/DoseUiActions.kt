package com.example.dosewear.presentation

import android.content.Context
import com.example.dosewear.alarm.AlarmAlertService
import com.example.dosewear.alarm.AlarmScheduler
import com.example.dosewear.data.DoseLog
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.notif.DoseNotifier
import com.example.dosewear.util.Surfaces

/**
 * Ekranlardan yapilan doz islemleri. Bildirim ile tam ayni is mantigini kullanir,
 * boylece "saatten onayladim ama bildirim duruyor" durumu olusmaz.
 */
object DoseUiActions {

    suspend fun take(context: Context, log: DoseLog) {
        AlarmAlertService.stop(context)
        val repo = DoseRepository.get(context)
        val low = repo.markTaken(log.id)
        low?.let { DoseNotifier.showLowStock(context, it) }
        AlarmScheduler.cancelSnooze(context, log.id)
        cleanupNotifications(context, log)
        Surfaces.refreshAll(context)
    }

    suspend fun snooze(context: Context, log: DoseLog) {
        AlarmAlertService.stop(context)
        val repo = DoseRepository.get(context)
        val next = repo.snooze(log.id)
        if (next != null) AlarmScheduler.scheduleSnooze(context, log.id, next)
        cleanupNotifications(context, log)
        Surfaces.refreshAll(context)
    }

    suspend fun skip(context: Context, log: DoseLog) {
        AlarmAlertService.stop(context)
        val repo = DoseRepository.get(context)
        repo.markSkipped(log.id)
        AlarmScheduler.cancelSnooze(context, log.id)
        cleanupNotifications(context, log)
        Surfaces.refreshAll(context)
    }

    /**
     * Islemden sonra bildirimleri gercek duruma esitler:
     * gruptaki tum dozlar kapandiysa bildirimi kaldirir, kalan varsa sessizce gunceller.
     */
    private suspend fun cleanupNotifications(context: Context, log: DoseLog) {
        val repo = DoseRepository.get(context)
        // Ertelenmis tek doz bildirimi (varsa) her halukarda kalksin.
        DoseNotifier.cancelDose(context, DoseNotifier.SNOOZE_KEY_OFFSET + log.id)
        AlarmScheduler.cancelNag(context, DoseNotifier.SNOOZE_KEY_OFFSET + log.id)

        val remaining = repo.openDosesOfGroup(log.groupKey)
        if (remaining.isEmpty()) {
            DoseNotifier.cancelDose(context, log.groupKey)
            AlarmScheduler.cancelNag(context, log.groupKey)
        } else {
            DoseNotifier.showDoseNotification(
                context = context,
                notifKey = log.groupKey,
                pending = remaining,
                nagLevel = 0,
                fullScreen = false,
                alert = false
            )
        }
    }

    /** Hatirlatici kaydedildikten/degistikten sonra alarm zincirini tazeler. */
    suspend fun rescheduleReminder(context: Context, reminderId: Long) {
        AlarmScheduler.scheduleNext(context, reminderId)
        Surfaces.refreshAll(context)
    }
}
