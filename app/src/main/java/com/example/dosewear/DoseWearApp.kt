package com.example.dosewear

import android.app.Application
import com.example.dosewear.alarm.AlarmScheduler
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.notif.DoseNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DoseWearApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DoseNotifier.createChannels(this)

        // Uygulama her acildiginda alarm zincirini dogrula.
        // Bu, OEM'in (Xiaomi HyperOS vb.) alarmi sessizce dusurdugu durumlarda
        // ucuz bir kendini toparlama mekanizmasi olarak calisir.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                AlarmScheduler.rescheduleAll(this@DoseWearApp)
                DoseRepository.get(this@DoseWearApp).purgeOldLogs()
            }
        }
    }
}
