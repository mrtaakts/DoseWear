package com.example.dosewear.presentation

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.example.dosewear.R
import com.example.dosewear.data.DoseLog
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.data.DoseStatus
import com.example.dosewear.data.Fmt
import com.example.dosewear.notif.DoseNotifier
import com.example.dosewear.presentation.theme.Amber
import com.example.dosewear.presentation.theme.Coral
import com.example.dosewear.presentation.theme.DoseWearTheme
import com.example.dosewear.presentation.theme.Mint
import com.example.dosewear.presentation.theme.Slate
import com.example.dosewear.presentation.theme.Steel
import com.example.dosewear.util.Texts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tam ekran doz onay ekrani.
 * Kilit ekraninin ustunde acilir, ekrani uyandirir ve her ilaci AYRI onaylatir.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // FLAG_DISMISS_KEYGUARD deprecated; setShowWhenLocked(true) zaten
        // kilit ekraninin ustunde gostermeyi ustleniyor.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    /**
     * Kullanici ekrana bakiyor -> titresim sussun. Bildirim kalici oldugu icin
     * ekrandan cikilsa bile doz kaybolmaz, israr alarmi devrede kalir.
     */
    override fun onResume() {
        super.onResume()
        DoseNotifier.stopAlarmVibration(this)
    }

    private fun render(intent: Intent?) {
        val ids = intent?.getLongArrayExtra(EXTRA_DOSE_IDS)?.toList() ?: emptyList()
        setContent {
            DoseWearTheme {
                AlarmScreen(doseIds = ids, onClose = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_NOTIF_KEY = "notif_key"
        const val EXTRA_DOSE_IDS = "dose_ids"
    }
}

@Composable
private fun AlarmScreen(doseIds: List<Long>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { DoseRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    val logs by remember(doseIds) { repo.logs.observeByIds(doseIds) }
        .collectAsState(initial = emptyList())

    val pending = logs.filter { it.status == DoseStatus.PENDING }

    LaunchedEffect(pending.size, logs.size) {
        if (logs.isNotEmpty() && pending.isEmpty()) {
            delay(700)
            onClose()
        }
    }

    // Kimse dokunmazsa 2 dakika sonra kapan.
    LaunchedEffect(Unit) {
        delay(120_000)
        onClose()
    }

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
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("💊", fontSize = 26.sp)
                    Text(
                        if (pending.isEmpty()) stringResource(R.string.dose_all_done)
                        else stringResource(R.string.dose_time),
                        color = if (pending.isEmpty()) Mint else Amber,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    logs.firstOrNull()?.let {
                        Text(Fmt.hhmm(it.scheduledAt), color = Steel, fontSize = 12.sp)
                    }
                }
            }

            if (pending.size > 1) {
                item {
                    ActionButton(
                        text = "✓  " + stringResource(R.string.action_take_all, pending.size),
                        color = Mint,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        height = 46.dp,
                        fontSize = 14,
                        onClick = {
                            scope.launch { pending.forEach { DoseUiActions.take(ctx, it) } }
                        }
                    )
                }
            }

            items(pending) { log ->
                DoseCard(
                    log = log,
                    onTake = { scope.launch { DoseUiActions.take(ctx, log) } },
                    onSnooze = { scope.launch { DoseUiActions.snooze(ctx, log) } },
                    onSkip = { scope.launch { DoseUiActions.skip(ctx, log) } }
                )
            }

            val handled = logs.filter { it.status != DoseStatus.PENDING }
            if (handled.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.dose_processed)) }
                items(handled) { log ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                            .card(Slate, 16.dp)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            log.supplementName,
                            color = Steel,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(0.62f)
                        )
                        Text(
                            Texts.statusEmoji(log.status) + " " +
                                if (log.status == DoseStatus.SNOOZED)
                                    (log.nextRemindAt?.let { Fmt.hhmm(it) } ?: "")
                                else Texts.statusText(ctx, log.status),
                            color = when (log.status) {
                                DoseStatus.TAKEN -> Mint
                                DoseStatus.SNOOZED -> Amber
                                else -> Steel
                            },
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            item {
                ActionButton(
                    text = stringResource(R.string.close),
                    color = Steel,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    height = 40.dp,
                    fontSize = 13,
                    onClick = onClose
                )
            }
        }
    }
}

@Composable
private fun DoseCard(
    log: DoseLog,
    onTake: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .card(Slate, 22.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            log.supplementName,
            color = Color(0xFFF2F6FF),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(Texts.amountText(ctx, log), color = Steel, fontSize = 11.sp)
        if (log.snoozeCount > 0) {
            Text(
                stringResource(R.string.snoozed_times, log.snoozeCount),
                color = Amber,
                fontSize = 9.sp
            )
        }
        ActionButton(
            text = "✓  " + stringResource(R.string.action_take),
            color = Mint,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            height = 46.dp,
            fontSize = 14,
            onClick = onTake
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ActionButton(
                text = "⏰ " + stringResource(R.string.action_snooze),
                color = Amber,
                modifier = Modifier.fillMaxWidth(0.55f),
                height = 36.dp,
                fontSize = 11,
                onClick = onSnooze
            )
            ActionButton(
                text = "⤼ " + stringResource(R.string.action_skip),
                color = Coral,
                modifier = Modifier.fillMaxWidth(1f),
                height = 36.dp,
                fontSize = 11,
                onClick = onSkip
            )
        }
    }
}
