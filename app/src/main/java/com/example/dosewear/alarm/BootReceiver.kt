package com.example.dosewear.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.dosewear.notif.DoseNotifier
import com.example.dosewear.util.Surfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Yeniden baslatmadan sonra AlarmManager'daki her sey silinir.
 * Bu receiver olmadan reboot sonrasi hicbir hatirlatici calismaz.
 *
 * Ayrica saat/zaman dilimi degisiminde ve uygulama guncellemesinden sonra da
 * tum alarmlari yeniden kurar.
 */
class BootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return

        val pendingResult = goAsync()
        val app = context.applicationContext

        scope.launch {
            try {
                DoseNotifier.createChannels(app)
                AlarmScheduler.rescheduleAll(app)
                Surfaces.refreshAll(app)
                Log.i("DoseAlarm", "BootReceiver: alarmlar yeniden kuruldu ($action)")
            } catch (t: Throwable) {
                Log.e("DoseAlarm", "BootReceiver hatasi", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
