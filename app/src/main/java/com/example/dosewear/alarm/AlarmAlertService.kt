package com.example.dosewear.alarm

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.dosewear.data.Prefs
import com.example.dosewear.notif.DoseNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Alarm caldigi surece yasayan tek bilesen: sesin ve titresimin SAHIBI budur.
 *
 * Neden servis: bir BroadcastReceiver birkac yuz milisaniyede oluyor; icinde
 * baslatilan MediaPlayer da onunla birlikte oluyor (bu yuzden onceki surumde ses
 * hic calmiyordu) ve titresimi durduracak net bir sahip kalmiyordu.
 *
 * Yasam suresi alarmin caldigi 15-180 saniye ile sinirli; is bitince stopSelf().
 * Foreground bildirimi olarak dozun kendi bildirimini kullanir, yani ekranda
 * ikinci bir bildirim cikmaz.
 */
class AlarmAlertService : Service() {

    private var player: MediaPlayer? = null
    private var autoStopJob: Job? = null
    private var soundJob: Job? = null
    private val scope: CoroutineScope = MainScope()
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_NOTIFICATION, Notification::class.java)
        }
        val notifId = intent?.getIntExtra(EXTRA_NOTIF_ID, 0) ?: 0

        if (notification == null || notifId == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ foreground servis turunu acikca istiyor.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, notifId, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(notifId, notification)
            }
        }.onFailure {
            Log.e(TAG, "startForeground basarisiz", it)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            startAlert()
        }
        return START_NOT_STICKY
    }

    /* ------------------------------------------------------------------ */

    private fun startAlert() {
        val prefs = Prefs(this)

        DoseNotifier.startAlarmVibration(this)
        if (prefs.soundEnabled) startSoundLoop()

        // Sure dolunca kendi kendine sussun: bir sey ters giderse saat sonsuza
        // kadar calmasin. Bildirim kalici oldugu icin doz kaybolmuyor.
        autoStopJob = scope.launch {
            delay(prefs.alertDurationSeconds * 1000L)
            stopSelf()
        }
    }

    /**
     * Kesintisiz calan bir alarm melodisi yerine, saatin BILDIRIM sesini
     * [SOUND_REPEAT_MS] araliginda tekrarliyoruz: kisa "ding"ler halinde,
     * titresim dongusuyle ayni ritimde. Uyandirir ama rahatsiz etmez.
     */
    private fun startSoundLoop() {
        val uri = notificationUri() ?: return
        val prepared = runCatching {
            MediaPlayer().apply {
                setDataSource(this@AlarmAlertService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_ALARM: sessiz modda bile duyulur, alarm ses
                        // seviyesini kullanir.
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = false
                setOnErrorListener { _, _, _ -> true }
                prepare()
            }
        }.getOrElse {
            Log.w(TAG, "Bildirim sesi hazirlanamadi: ${it.message}")
            return
        }
        player = prepared

        soundJob = scope.launch {
            while (isActive) {
                runCatching {
                    prepared.seekTo(0)
                    prepared.start()
                }
                delay(SOUND_REPEAT_MS)
            }
        }
    }

    /** Once sistemin bildirim sesi, yoksa alarm/zil sesi, yoksa fabrika varsayilani. */
    private fun notificationUri(): Uri? =
        RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI

    private fun releasePlayer() {
        soundJob?.cancel()
        soundJob = null
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
    }

    override fun onDestroy() {
        autoStopJob?.cancel()
        releasePlayer()
        DoseNotifier.stopAlarmVibration(this)
        // DETACH: bildirim ekranda kalsin. Doz onaylanana kadar kaybolmamali.
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
        scope.cancel()
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DoseAlert"

        /** Bildirim sesinin tekrar araligi. */
        private const val SOUND_REPEAT_MS = 2_000L
        private const val EXTRA_NOTIFICATION = "notification"
        private const val EXTRA_NOTIF_ID = "notif_id"

        /**
         * Alarmi baslatir. [notification] dozun kendi bildirimi; servis onu
         * foreground bildirimi olarak kullanir.
         *
         * Arka plandan foreground servis baslatma kisiti bizi engellemiyor:
         * setAlarmClock / setExact* ile tetiklenen alarm yayinlari Android'in
         * muafiyet listesinde.
         */
        fun start(context: Context, notifId: Int, notification: Notification) {
            val intent = Intent(context, AlarmAlertService::class.java).apply {
                putExtra(EXTRA_NOTIF_ID, notifId)
                putExtra(EXTRA_NOTIFICATION, notification)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    Log.w(TAG, "Servis baslatilamadi, sadece titresim: ${it.message}")
                    // En azindan titresim olsun.
                    DoseNotifier.startAlarmVibration(context)
                }
        }

        /** Her onay/erteleme/atlama yolunun cagirdigi TEK durdurma noktasi. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, AlarmAlertService::class.java))
            }
            // Servis hic calismadiysa (baslatilamadiysa) titresim yine de sussun.
            DoseNotifier.stopAlarmVibration(context)
        }
    }
}
