package com.example.dosewear.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.notif.DoseNotifier
import com.example.dosewear.util.Surfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bildirim butonlarinin dustugu yer.
 *
 * "Onay mekanizmasi" tam olarak burasi: her aksiyon DoseLog'a yazar,
 * gerekiyorsa stogu dusurur / arttirir ve bir sonraki alarmi kurar.
 */
class DoseActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val app = context.applicationContext
        scope.launch {
            try {
                handle(app, intent)
            } catch (t: Throwable) {
                Log.e("DoseAction", "Aksiyon islenemedi", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        val repo = DoseRepository.get(context)
        val doseId = intent.getLongExtra(EXTRA_DOSE_LOG_ID, 0L)
        val notifKey = intent.getLongExtra(EXTRA_NOTIF_KEY, 0L)
        val supplementId = intent.getLongExtra(EXTRA_SUPPLEMENT_ID, 0L)
        val doseIds = intent.getLongArrayExtra(EXTRA_DOSE_IDS)?.toList() ?: emptyList()

        // Kullanici bir sey yapti -> alarm titresimi hemen sussun.
        DoseNotifier.stopAlarmVibration(context)

        when (intent.action) {

            ACTION_TAKE -> {
                val lowStock = repo.markTaken(doseId)
                lowStock?.let { DoseNotifier.showLowStock(context, it) }
                AlarmScheduler.cancelSnooze(context, doseId)
                refreshOrClose(context, repo, notifKey, doseIds)
            }

            ACTION_TAKE_ALL -> {
                repo.pendingOf(doseIds).forEach { log ->
                    val lowStock = repo.markTaken(log.id)
                    lowStock?.let { DoseNotifier.showLowStock(context, it) }
                    AlarmScheduler.cancelSnooze(context, log.id)
                }
                refreshOrClose(context, repo, notifKey, doseIds)
            }

            ACTION_SNOOZE -> {
                snoozeOne(context, repo, doseId)
                refreshOrClose(context, repo, notifKey, doseIds)
            }

            ACTION_SNOOZE_ALL -> {
                // Her doz AYRI rastgele sapma alir -> ayni dakikada ust uste binmezler.
                repo.pendingOf(doseIds).forEach { snoozeOne(context, repo, it.id) }
                refreshOrClose(context, repo, notifKey, doseIds)
            }

            ACTION_SKIP -> {
                repo.markSkipped(doseId)
                AlarmScheduler.cancelSnooze(context, doseId)
                refreshOrClose(context, repo, notifKey, doseIds)
            }

            ACTION_REFILL -> {
                repo.refill(supplementId)
                DoseNotifier.cancelLowStock(context, supplementId)
                Surfaces.refreshAll(context)
            }

            ACTION_DISMISS_STOCK -> {
                // "Sonra": uyari kapanir ama bayrak acik kalir ki spam olmasin.
                DoseNotifier.cancelLowStock(context, supplementId)
            }
        }
    }

    private suspend fun snoozeOne(context: Context, repo: DoseRepository, doseLogId: Long) {
        val next = repo.snooze(doseLogId)
        if (next != null) {
            AlarmScheduler.scheduleSnooze(context, doseLogId, next)
        } else {
            // Erteleme hakki bitti -> MISSED olarak isaretlendi.
            AlarmScheduler.cancelSnooze(context, doseLogId)
        }
    }

    /**
     * Kalan bekleyen doz varsa bildirimi sessizce guncelle,
     * hepsi kapandiysa bildirimi ve israr alarmini kaldir.
     */
    private suspend fun refreshOrClose(
        context: Context,
        repo: DoseRepository,
        notifKey: Long,
        doseIds: List<Long>
    ) {
        val stillPending = repo.pendingOf(doseIds)
        if (stillPending.isEmpty()) {
            DoseNotifier.cancelDose(context, notifKey)
            AlarmScheduler.cancelNag(context, notifKey)
        } else {
            DoseNotifier.showDoseNotification(
                context = context,
                notifKey = notifKey,
                pending = stillPending,
                nagLevel = 0,
                fullScreen = false,
                alert = false
            )
        }
        Surfaces.refreshAll(context)
    }

    companion object {
        const val ACTION_TAKE = "com.example.dosewear.action.TAKE"
        const val ACTION_TAKE_ALL = "com.example.dosewear.action.TAKE_ALL"
        const val ACTION_SNOOZE = "com.example.dosewear.action.SNOOZE"
        const val ACTION_SNOOZE_ALL = "com.example.dosewear.action.SNOOZE_ALL"
        const val ACTION_SKIP = "com.example.dosewear.action.SKIP"
        const val ACTION_REFILL = "com.example.dosewear.action.REFILL"
        const val ACTION_DISMISS_STOCK = "com.example.dosewear.action.DISMISS_STOCK"

        const val EXTRA_DOSE_LOG_ID = "dose_log_id"
        const val EXTRA_NOTIF_KEY = "notif_key"
        const val EXTRA_DOSE_IDS = "dose_ids"
        const val EXTRA_SUPPLEMENT_ID = "supplement_id"
    }
}
