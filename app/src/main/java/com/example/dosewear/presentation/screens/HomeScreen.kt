package com.example.dosewear.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.example.dosewear.presentation.ActionButton
import com.example.dosewear.presentation.DoseUiActions
import com.example.dosewear.presentation.Hint
import com.example.dosewear.presentation.NavChip
import com.example.dosewear.presentation.Pill
import com.example.dosewear.presentation.SectionTitle
import com.example.dosewear.presentation.card
import com.example.dosewear.presentation.listPadding
import com.example.dosewear.presentation.rotaryScroll
import com.example.dosewear.presentation.theme.Amber
import com.example.dosewear.presentation.theme.Coral
import com.example.dosewear.presentation.theme.Mint
import com.example.dosewear.presentation.theme.Sky
import com.example.dosewear.presentation.theme.Slate
import com.example.dosewear.presentation.theme.Steel
import com.example.dosewear.presentation.theme.Violet
import com.example.dosewear.util.Texts
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ */
/*  Ekran modelleri                                                     */
/*                                                                      */
/*  Metin uretimi (getString, java.time, birim etiketleri) liste        */
/*  ogesinin ICINDE degil, veri degistiginde BIR KEZ yapiliyor.         */
/*  ScalingLazyColumn kaydirirken her ogeyi yeniden compose ettigi icin */
/*  bu, kaydirma akiciligindaki en buyuk farki yaratan degisiklik.      */
/* ------------------------------------------------------------------ */

private data class OpenDoseUi(
    val log: DoseLog,
    val name: String,
    val sub: String,
    val snoozePill: String?
)

private data class LowStockUi(
    val id: Long,
    val emoji: String,
    val name: String,
    val sub: String
)

private data class HomeStats(val streak: Int, val nextTime: String?, val nextTitle: String, val nextRel: String)

@Composable
fun HomeScreen(
    onSupplements: () -> Unit,
    onReminders: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onSupplement: (Long) -> Unit
) {
    val ctx = LocalContext.current
    val repo = remember { DoseRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    // DIKKAT: repo.observeX() her cagrida YENI bir Flow uretiyor. remember olmadan
    // collectAsState her recomposition'da aboneligi bozup yeni SQLite sorgusu aciyordu.
    val today by remember(repo) { repo.observeToday() }.collectAsState(initial = emptyList())
    val lowStock by remember(repo) { repo.observeLowStock() }.collectAsState(initial = emptyList())
    val open by remember(repo) { repo.observeOpenDoses() }.collectAsState(initial = emptyList())

    var stats by remember { mutableStateOf(HomeStats(0, null, "", "")) }

    LaunchedEffect(today.size, open.size) {
        val upcoming = repo.nextUpcoming()
        val streak = repo.streakDays()
        stats = HomeStats(
            streak = streak,
            nextTime = upcoming?.let { Fmt.hhmm(it.triggerAt) },
            nextTitle = upcoming?.title?.ifBlank { ctx.getString(R.string.reminder_fallback) } ?: "",
            nextRel = upcoming?.let { Texts.relative(ctx, it.triggerAt) } ?: ""
        )
    }

    val openUi = remember(open) {
        open.map { log ->
            OpenDoseUi(
                log = log,
                name = log.supplementName,
                sub = "${log.scheduledText()} • ${Texts.amountText(ctx, log)}",
                snoozePill = if (log.status == DoseStatus.SNOOZED)
                    "⏳ " + (log.nextRemindAt?.let { Fmt.hhmm(it) } ?: "") else null
            )
        }
    }

    val lowUi = remember(lowStock) {
        lowStock.map { s ->
            LowStockUi(
                id = s.id,
                emoji = if (s.stock <= 0) "⛔" else "⚠️",
                name = s.name,
                sub = if (s.stock <= 0) ctx.getString(R.string.stock_out)
                else ctx.getString(R.string.stock_left, Texts.stockText(ctx, s))
            )
        }
    }

    val taken = today.count { it.status == DoseStatus.TAKEN }
    val total = today.size

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
            item { TodayCard(taken = taken, total = total, streak = stats.streak) }
            item { UpcomingCard(stats) }

            if (openUi.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_pending), Amber) }
                items(openUi) { ui ->
                    OpenDoseRow(
                        ui = ui,
                        onTake = { scope.launch { DoseUiActions.take(ctx, ui.log) } },
                        onSnooze = { scope.launch { DoseUiActions.snooze(ctx, ui.log) } }
                    )
                }
            }

            if (lowUi.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_low_stock), Coral) }
                items(lowUi) { ui ->
                    NavChip(
                        emoji = ui.emoji,
                        label = ui.name,
                        secondary = ui.sub,
                        color = Coral,
                        onClick = { onSupplement(ui.id) }
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.home_menu)) }
            item {
                NavChip(
                    "💊",
                    stringResource(R.string.menu_supplements),
                    stringResource(R.string.menu_supplements_sub),
                    Mint,
                    onSupplements
                )
            }
            item {
                NavChip(
                    "⏰",
                    stringResource(R.string.menu_reminders),
                    stringResource(R.string.menu_reminders_sub),
                    Sky,
                    onReminders
                )
            }
            item {
                NavChip(
                    "📜",
                    stringResource(R.string.menu_history),
                    stringResource(R.string.menu_history_sub),
                    Violet,
                    onHistory
                )
            }
            item {
                NavChip(
                    "⚙️",
                    stringResource(R.string.menu_settings),
                    stringResource(R.string.menu_settings_sub),
                    Steel,
                    onSettings
                )
            }
            item { Hint(stringResource(R.string.home_hint)) }
        }
    }
}

/* ------------------------------------------------------------------ */

@Composable
private fun TodayCard(taken: Int, total: Int, streak: Int) {
    val ratio = if (total == 0) 0f else taken.toFloat() / total.toFloat()
    val color = when {
        total == 0 -> Steel
        ratio >= 1f -> Mint
        ratio >= 0.5f -> Amber
        else -> Coral
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .card(Slate, 22.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.home_today),
            color = Steel,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (total == 0) stringResource(R.string.home_no_doses) else "$taken / $total",
            color = color,
            fontSize = if (total == 0) 14.sp else 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .card(Color(0xFF2A3446), 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio.coerceIn(0f, 1f))
                    .height(6.dp)
                    .card(color, 3.dp)
            )
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Pill(
                if (streak > 0) "🔥 " + stringResource(R.string.home_streak, streak)
                else stringResource(R.string.home_no_streak),
                if (streak > 0) Amber else Steel
            )
            if (total > 0 && taken == total) {
                Pill(stringResource(R.string.home_all_done) + " ✓", Mint)
            }
        }
    }
}

@Composable
private fun UpcomingCard(stats: HomeStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .card(Sky.copy(alpha = 0.14f), 22.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.home_next),
            color = Sky,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        if (stats.nextTime == null) {
            Text(
                stringResource(R.string.home_no_reminder),
                color = Steel,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        } else {
            Text(
                stats.nextTime,
                color = Sky,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                stats.nextTitle,
                color = Color(0xFFDCE6F5),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(stats.nextRel, color = Steel, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun OpenDoseRow(ui: OpenDoseUi, onTake: () -> Unit, onSnooze: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .card(Slate, 20.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.72f)) {
                Text(
                    ui.name,
                    color = Color(0xFFF2F6FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(ui.sub, color = Steel, fontSize = 10.sp, maxLines = 1)
            }
            ui.snoozePill?.let { Pill(it, Amber) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ActionButton(
                "✓ " + stringResource(R.string.action_take),
                Mint,
                Modifier.fillMaxWidth(0.55f),
                36.dp,
                12,
                onTake
            )
            ActionButton(
                "⏰ " + stringResource(R.string.action_snooze),
                Amber,
                Modifier.fillMaxWidth(1f),
                36.dp,
                12,
                onSnooze
            )
        }
    }
}
