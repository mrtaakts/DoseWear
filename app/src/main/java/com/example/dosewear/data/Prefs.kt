package com.example.dosewear.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Kucuk ayarlar + alarm teshis kayitlari.
 * DataStore yerine SharedPreferences: BroadcastReceiver icinde senkron okunabiliyor,
 * ek bagimlilik ve arka plan is parcacigi gerektirmiyor -> pil dostu.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("dosewear", Context.MODE_PRIVATE)

    /** Yeni hatirlaticilar icin varsayilan erteleme suresi (dk). */
    var defaultSnoozeMinutes: Int
        get() = sp.getInt(K_SNOOZE, 10)
        set(v) = sp.edit().putInt(K_SNOOZE, v.coerceIn(1, 120)).apply()

    /** Ayni anda ertelenen dozlarin cakismamasi icin rastgele sapma (dk). */
    var defaultJitterMinutes: Int
        get() = sp.getInt(K_JITTER, 3)
        set(v) = sp.edit().putInt(K_JITTER, v.coerceIn(0, 30)).apply()

    /** Bir doz en fazla kac kez ertelenebilir. */
    var defaultMaxSnoozes: Int
        get() = sp.getInt(K_MAX_SNOOZE, 3)
        set(v) = sp.edit().putInt(K_MAX_SNOOZE, v.coerceIn(0, 10)).apply()

    /**
     * true -> AlarmManager.setAlarmClock() kullanilir.
     * Bu, Doze ve OEM (Xiaomi HyperOS) arka plan kisitlarindan muaf tutulan
     * en yuksek oncelikli alarm turudur. Saatte kucuk bir alarm ikonu gorunur.
     * false -> setExactAndAllowWhileIdle().
     */
    var highPriorityAlarms: Boolean
        get() = sp.getBoolean(K_HIGH_PRIO, true)
        set(v) = sp.edit().putBoolean(K_HIGH_PRIO, v).apply()

    /** Onaylanmayan dozu kac dakikada bir yeniden durtsun. */
    var nagIntervalMinutes: Int
        get() = sp.getInt(K_NAG_INTERVAL, 5)
        set(v) = sp.edit().putInt(K_NAG_INTERVAL, v.coerceIn(1, 60)).apply()

    /** Kac durtmeden sonra doz "kacirildi" sayilsin. */
    var maxNags: Int
        get() = sp.getInt(K_MAX_NAGS, 3)
        set(v) = sp.edit().putInt(K_MAX_NAGS, v.coerceIn(0, 10)).apply()

    /**
     * Alarm caldiginda kesintisiz titresim suresi (sn).
     * Onaylanana/ertelenene kadar titrer, en fazla bu kadar.
     */
    var alertDurationSeconds: Int
        get() = sp.getInt(K_ALERT_SEC, 60)
        set(v) = sp.edit().putInt(K_ALERT_SEC, v.coerceIn(15, 180)).apply()

    /** Tam ekran doz onay ekrani acilsin mi. */
    var fullScreenAlarm: Boolean
        get() = sp.getBoolean(K_FULLSCREEN, true)
        set(v) = sp.edit().putBoolean(K_FULLSCREEN, v).apply()

    /* ---------------- Teshis: alarm gercekten zamaninda geldi mi? -------- */

    fun recordAlarmDelivery(scheduledAt: Long, firedAt: Long, tag: String) {
        sp.edit()
            .putLong(K_LAST_SCHED, scheduledAt)
            .putLong(K_LAST_FIRED, firedAt)
            .putString(K_LAST_TAG, tag)
            .putLong(K_WORST_DELAY, maxOf(worstDelayMs, firedAt - scheduledAt))
            .apply()
    }

    val lastScheduledAt: Long get() = sp.getLong(K_LAST_SCHED, 0L)
    val lastFiredAt: Long get() = sp.getLong(K_LAST_FIRED, 0L)
    val lastAlarmTag: String get() = sp.getString(K_LAST_TAG, "") ?: ""
    val worstDelayMs: Long get() = sp.getLong(K_WORST_DELAY, 0L)

    fun resetDiagnostics() {
        sp.edit()
            .remove(K_LAST_SCHED).remove(K_LAST_FIRED)
            .remove(K_LAST_TAG).remove(K_WORST_DELAY)
            .apply()
    }

    /** Son alarm kurulum turu (kullaniciya gostermek icin). */
    var lastScheduleMode: String
        get() = sp.getString(K_SCHED_MODE, "-") ?: "-"
        set(v) = sp.edit().putString(K_SCHED_MODE, v).apply()

    companion object {
        private const val K_SNOOZE = "snooze_minutes"
        private const val K_JITTER = "snooze_jitter"
        private const val K_MAX_SNOOZE = "max_snoozes"
        private const val K_HIGH_PRIO = "high_priority_alarms"
        private const val K_NAG_INTERVAL = "nag_interval"
        private const val K_MAX_NAGS = "max_nags"
        private const val K_FULLSCREEN = "full_screen_alarm"
        private const val K_ALERT_SEC = "alert_duration_sec"
        private const val K_LAST_SCHED = "diag_last_scheduled"
        private const val K_LAST_FIRED = "diag_last_fired"
        private const val K_LAST_TAG = "diag_last_tag"
        private const val K_WORST_DELAY = "diag_worst_delay"
        private const val K_SCHED_MODE = "diag_sched_mode"
    }
}
