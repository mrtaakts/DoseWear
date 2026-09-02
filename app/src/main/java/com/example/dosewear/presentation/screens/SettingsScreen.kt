package com.example.dosewear.presentation.screens

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import com.example.dosewear.R
import com.example.dosewear.alarm.AlarmScheduler
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.presentation.Hint
import com.example.dosewear.presentation.NavChip
import com.example.dosewear.presentation.PlainChip
import com.example.dosewear.presentation.ScreenTitle
import com.example.dosewear.presentation.SectionTitle
import com.example.dosewear.presentation.StepperRow
import com.example.dosewear.presentation.listPadding
import com.example.dosewear.presentation.rememberResumeTick
import com.example.dosewear.presentation.rotaryScroll
import com.example.dosewear.presentation.stepInt
import com.example.dosewear.presentation.theme.Amber
import com.example.dosewear.presentation.theme.Coral
import com.example.dosewear.presentation.theme.Mint
import com.example.dosewear.presentation.theme.Sky
import com.example.dosewear.presentation.theme.Steel
import com.example.dosewear.presentation.theme.Violet
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val repo = remember { DoseRepository.get(ctx) }
    val prefs = repo.prefs
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    var highPrio by remember { mutableStateOf(prefs.highPriorityAlarms) }
    var fullScreen by remember { mutableStateOf(prefs.fullScreenAlarm) }
    var sound by remember { mutableStateOf(prefs.soundEnabled) }
    var alertSec by remember { mutableStateOf(prefs.alertDurationSeconds) }
    var nagInterval by remember { mutableStateOf(prefs.nagIntervalMinutes) }
    var maxNags by remember { mutableStateOf(prefs.maxNags) }
    var snooze by remember { mutableStateOf(prefs.defaultSnoozeMinutes) }
    var jitter by remember { mutableStateOf(prefs.defaultJitterMinutes) }
    var message by remember { mutableStateOf<String?>(null) }

    // Sistem ayarlarindan geri donunce izin durumlari yeniden okunsun.
    val resumeTick = rememberResumeTick()
    val exactOk = remember(resumeTick) { AlarmScheduler.canScheduleExact(ctx) }
    val batteryOk = remember(resumeTick) { isIgnoringBatteryOptimizations(ctx) }
    val fsiOk = remember(resumeTick) { canUseFullScreenIntent(ctx) }
    val overlayOk = remember(resumeTick) { canDrawOverlays(ctx) }
    val versionName = remember { appVersion(ctx) }

    val granted = stringResource(R.string.perm_granted)
    val missing = stringResource(R.string.perm_missing)

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth().rotaryScroll(listState),
            state = listState,
            contentPadding = listPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { ScreenTitle(stringResource(R.string.settings_title)) }

            /* -------- Kritik izinler -------- */
            item { SectionTitle(stringResource(R.string.section_reliability), Coral) }
            item {
                NavChip(
                    emoji = if (exactOk) "✅" else "❗",
                    label = stringResource(R.string.perm_exact),
                    secondary = if (exactOk) granted else missing,
                    color = if (exactOk) Mint else Coral,
                    onClick = {
                        message = if (openExactAlarmSettings(ctx)) null
                        else ctx.getString(R.string.settings_open_failed)
                    }
                )
            }
            item {
                NavChip(
                    emoji = if (batteryOk) "✅" else "❗",
                    label = stringResource(R.string.perm_battery),
                    secondary = if (batteryOk) stringResource(R.string.perm_battery_ok)
                    else stringResource(R.string.perm_battery_no),
                    color = if (batteryOk) Mint else Coral,
                    onClick = {
                        message = if (requestBatteryExemption(ctx)) null
                        else ctx.getString(R.string.settings_open_failed)
                    }
                )
            }
            item {
                NavChip(
                    emoji = if (fsiOk) "✅" else "❗",
                    label = stringResource(R.string.perm_fsi),
                    secondary = if (fsiOk) granted else missing,
                    color = if (fsiOk) Mint else Coral,
                    onClick = {
                        message = if (openFullScreenIntentSettings(ctx)) null
                        else ctx.getString(R.string.settings_open_failed)
                    }
                )
            }
            item {
                NavChip(
                    emoji = if (overlayOk) "✅" else "▫️",
                    label = stringResource(R.string.perm_overlay),
                    secondary = if (overlayOk) granted else missing,
                    // Zorunlu degil: amber, kirmizi degil.
                    color = if (overlayOk) Mint else Amber,
                    onClick = {
                        message = if (openOverlaySettings(ctx)) null
                        else ctx.getString(R.string.settings_open_failed)
                    }
                )
            }
            item { Hint(stringResource(R.string.perm_overlay_hint)) }
            item {
                NavChip(
                    emoji = "🔔",
                    label = stringResource(R.string.perm_notifications),
                    secondary = stringResource(R.string.perm_notifications_sub),
                    color = Sky,
                    onClick = {
                        message = if (openNotificationSettings(ctx)) null
                        else ctx.getString(R.string.settings_open_failed)
                    }
                )
            }
            message?.let { item { Hint(it) } }
            item {
                Hint(
                    if (batteryOk) stringResource(R.string.oem_hint_ok)
                    else stringResource(R.string.oem_hint)
                )
            }

            /* -------- Alarm davranisi -------- */
            item { SectionTitle(stringResource(R.string.section_alarm), Sky) }
            item {
                PlainChip(
                    label = if (highPrio) stringResource(R.string.high_prio_on)
                    else stringResource(R.string.high_prio_off),
                    secondary = if (highPrio) stringResource(R.string.high_prio_sub_on)
                    else stringResource(R.string.high_prio_sub_off),
                    onClick = {
                        highPrio = !highPrio
                        prefs.highPriorityAlarms = highPrio
                        scope.launch { AlarmScheduler.rescheduleAll(ctx) }
                    },
                    color = if (highPrio) Mint else Amber
                )
            }
            item {
                PlainChip(
                    label = if (fullScreen) stringResource(R.string.fullscreen_on)
                    else stringResource(R.string.fullscreen_off),
                    secondary = stringResource(R.string.fullscreen_sub),
                    onClick = {
                        fullScreen = !fullScreen
                        prefs.fullScreenAlarm = fullScreen
                    },
                    color = if (fullScreen) Mint else Steel
                )
            }
            item {
                PlainChip(
                    label = if (sound) stringResource(R.string.sound_on)
                    else stringResource(R.string.sound_off),
                    secondary = stringResource(R.string.sound_sub),
                    onClick = {
                        sound = !sound
                        prefs.soundEnabled = sound
                    },
                    color = if (sound) Mint else Steel
                )
            }
            item {
                StepperRow(
                    label = stringResource(R.string.field_alert_duration),
                    value = stringResource(R.string.seconds_short, alertSec),
                    color = Coral,
                    onMinus = {
                        alertSec = stepInt(alertSec, -1, 15, 15, 180)
                        prefs.alertDurationSeconds = alertSec
                    },
                    onPlus = {
                        alertSec = stepInt(alertSec, +1, 15, 15, 180)
                        prefs.alertDurationSeconds = alertSec
                    }
                )
            }
            item { Hint(stringResource(R.string.alert_duration_hint)) }
            item {
                StepperRow(
                    label = stringResource(R.string.field_nag_interval),
                    value = stringResource(R.string.minutes_short, nagInterval),
                    color = Amber,
                    onMinus = {
                        nagInterval = stepInt(nagInterval, -1, 1, 1, 60)
                        prefs.nagIntervalMinutes = nagInterval
                    },
                    onPlus = {
                        nagInterval = stepInt(nagInterval, +1, 1, 1, 60)
                        prefs.nagIntervalMinutes = nagInterval
                    }
                )
            }
            item {
                StepperRow(
                    label = stringResource(R.string.field_nag_count),
                    value = stringResource(R.string.times_count, maxNags),
                    color = Amber,
                    onMinus = {
                        maxNags = stepInt(maxNags, -1, 1, 0, 10)
                        prefs.maxNags = maxNags
                    },
                    onPlus = {
                        maxNags = stepInt(maxNags, +1, 1, 0, 10)
                        prefs.maxNags = maxNags
                    }
                )
            }
            item { Hint(stringResource(R.string.nag_hint)) }

            /* -------- Varsayilanlar -------- */
            item { SectionTitle(stringResource(R.string.section_defaults), Violet) }
            item {
                StepperRow(
                    label = stringResource(R.string.field_snooze),
                    value = stringResource(R.string.minutes_short, snooze),
                    color = Violet,
                    onMinus = {
                        snooze = stepInt(snooze, -1, 5, 5, 120)
                        prefs.defaultSnoozeMinutes = snooze
                    },
                    onPlus = {
                        snooze = stepInt(snooze, +1, 5, 5, 120)
                        prefs.defaultSnoozeMinutes = snooze
                    }
                )
            }
            item {
                StepperRow(
                    label = stringResource(R.string.field_jitter),
                    value = stringResource(R.string.minutes_plusminus, jitter),
                    color = Violet,
                    onMinus = {
                        jitter = stepInt(jitter, -1, 1, 0, 30)
                        prefs.defaultJitterMinutes = jitter
                    },
                    onPlus = {
                        jitter = stepInt(jitter, +1, 1, 0, 30)
                        prefs.defaultJitterMinutes = jitter
                    }
                )
            }

            /* -------- Bakim -------- */
            item { SectionTitle(stringResource(R.string.section_maintenance), Mint) }
            item {
                NavChip(
                    emoji = "🔄",
                    label = stringResource(R.string.reschedule),
                    secondary = stringResource(R.string.reschedule_sub),
                    color = Mint,
                    onClick = {
                        scope.launch {
                            AlarmScheduler.rescheduleAll(ctx)
                            message = ctx.getString(R.string.reschedule_done)
                        }
                    }
                )
            }
            item { Hint(stringResource(R.string.version, versionName)) }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sistem ayar ekranlari                                              */
/* ------------------------------------------------------------------ */

private fun appVersion(ctx: Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
}.getOrDefault("?")

private fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
}

private fun canDrawOverlays(ctx: Context): Boolean =
    runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

private fun canUseFullScreenIntent(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return true
    return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
}

/** Sirayla dener; ilki acilirsa true. Hicbiri acilmazsa false (kullaniciya not gosterilir). */
private fun tryStart(ctx: Context, vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }
    return false
}

private fun appUri(ctx: Context): Uri = Uri.parse("package:${ctx.packageName}")

private fun openExactAlarmSettings(ctx: Context): Boolean {
    val list = mutableListOf<Intent>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        list += Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(appUri(ctx))
    }
    list += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(appUri(ctx))
    return tryStart(ctx, *list.toTypedArray())
}

/**
 * Wear OS'te pil muafiyeti diyalogu her zaman bulunmuyor; bu yuzden
 * sirayla uc farkli ekran deneniyor. Hicbiri yoksa kullaniciya elle yol tarif ediliyor.
 */
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(ctx: Context): Boolean = tryStart(
    ctx,
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(appUri(ctx)),
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(appUri(ctx))
)

private fun openFullScreenIntentSettings(ctx: Context): Boolean {
    val list = mutableListOf<Intent>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        list += Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(appUri(ctx))
    }
    list += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(appUri(ctx))
    return tryStart(ctx, *list.toTypedArray())
}

/**
 * "Uzerinde gosterme" izni: OEM katmani arka plandan aktivite baslatmayi
 * engellediginde onay ekraninin yine de one gelmesini saglar.
 */
private fun openOverlaySettings(ctx: Context): Boolean = tryStart(
    ctx,
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(appUri(ctx)),
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(appUri(ctx))
)

private fun openNotificationSettings(ctx: Context): Boolean = tryStart(
    ctx,
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(appUri(ctx))
)
