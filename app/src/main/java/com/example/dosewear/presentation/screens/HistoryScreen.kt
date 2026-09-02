package com.example.dosewear.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import com.example.dosewear.presentation.Pill
import com.example.dosewear.presentation.ScreenTitle
import com.example.dosewear.presentation.SectionTitle
import com.example.dosewear.presentation.listPadding
import com.example.dosewear.presentation.rotaryScroll
import com.example.dosewear.presentation.tappable
import com.example.dosewear.presentation.theme.Coral
import com.example.dosewear.presentation.theme.Mint
import com.example.dosewear.presentation.theme.Slate
import com.example.dosewear.presentation.theme.SlateHi
import com.example.dosewear.presentation.theme.Steel
import com.example.dosewear.presentation.theme.Violet
import com.example.dosewear.util.Texts
import kotlinx.coroutines.launch

private data class HistoryRowUi(
    val log: DoseLog,
    val title: String,
    val sub: String,
    val time: String,
    val actedTime: String?,
    val color: Color,
    /** TAKEN disindaki her doz sonradan onaylanabilir. */
    val canConfirm: Boolean
)

private data class HistorySection(val header: String, val rows: List<HistoryRowUi>)

/**
 * Son 7 gun, en yeni ustte.
 * Onaylanmamis bir satira dokununca altinda "Simdi aldim" / "Atla" acilir:
 * gecmiste kacirdigin bir dozu sonradan isaretlersen alim saati O AN olur
 * ve stok o anda duser.
 */
@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val repo = remember { DoseRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    val logs by remember(repo) { repo.observeLastDays(7) }.collectAsState(initial = emptyList())

    var expandedId by remember { mutableStateOf(0L) }

    val sections = remember(logs) {
        logs.groupBy { Texts.dayLabel(ctx, it.scheduledAt) }
            .map { (day, dayLogs) ->
                val taken = dayLogs.count { it.status == DoseStatus.TAKEN }
                HistorySection(
                    header = "$day  •  $taken/${dayLogs.size}",
                    rows = dayLogs.map { log ->
                        HistoryRowUi(
                            log = log,
                            title = "${Texts.statusEmoji(log.status)} ${log.supplementName}",
                            sub = buildString {
                                append(Texts.statusText(ctx, log.status))
                                append(" • ")
                                append(Texts.amountText(ctx, log))
                                if (log.snoozeCount > 0) {
                                    append(" • ")
                                    append(ctx.getString(R.string.snoozed_times, log.snoozeCount))
                                }
                            },
                            time = log.scheduledText(),
                            actedTime = log.actedAt?.let { Fmt.hhmm(it) },
                            color = statusColor(log.status),
                            canConfirm = log.status != DoseStatus.TAKEN
                        )
                    }
                )
            }
    }
    val takenTotal = remember(logs) { logs.count { it.status == DoseStatus.TAKEN } }

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
            item { ScreenTitle(stringResource(R.string.history_title)) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
                ) {
                    Pill("✅ $takenTotal", Mint)
                    Pill(stringResource(R.string.history_total, logs.size), Violet)
                }
            }

            if (logs.isEmpty()) {
                item { Hint(stringResource(R.string.history_empty)) }
            } else {
                item { Hint(stringResource(R.string.history_tap_hint)) }
            }

            sections.forEach { section ->
                item { SectionTitle(section.header, Violet) }
                items(section.rows) { row ->
                    HistoryRow(
                        row = row,
                        expanded = expandedId == row.log.id,
                        onToggle = {
                            expandedId = if (expandedId == row.log.id) 0L else row.log.id
                        },
                        onTakeNow = {
                            expandedId = 0L
                            scope.launch { DoseUiActions.take(ctx, row.log) }
                        },
                        onSkip = {
                            expandedId = 0L
                            scope.launch { DoseUiActions.skip(ctx, row.log) }
                        }
                    )
                }
            }

            if (logs.isNotEmpty()) {
                item { Hint(stringResource(R.string.history_window)) }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    row: HistoryRowUi,
    expanded: Boolean,
    onToggle: () -> Unit,
    onTakeNow: () -> Unit,
    onSkip: () -> Unit
) {
    val shape = remember { RoundedCornerShape(18.dp) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(if (expanded) SlateHi else Slate, shape)
            .let { if (row.canConfirm) it.tappable(shape, onToggle) else it }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.68f)) {
                Text(
                    row.title,
                    color = row.color,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    row.sub,
                    color = Steel,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(row.time, color = Steel, fontSize = 11.sp, maxLines = 1)
                row.actedTime?.let { Text(it, color = row.color, fontSize = 9.sp, maxLines = 1) }
            }
        }

        if (expanded && row.canConfirm) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionButton(
                    text = "✓ " + stringResource(R.string.confirm_now),
                    color = Mint,
                    modifier = Modifier.fillMaxWidth(0.62f),
                    height = 34.dp,
                    fontSize = 11,
                    onClick = onTakeNow
                )
                ActionButton(
                    text = "⤼ " + stringResource(R.string.action_skip),
                    color = Coral,
                    modifier = Modifier.fillMaxWidth(1f),
                    height = 34.dp,
                    fontSize = 11,
                    onClick = onSkip
                )
            }
        }
    }
}
