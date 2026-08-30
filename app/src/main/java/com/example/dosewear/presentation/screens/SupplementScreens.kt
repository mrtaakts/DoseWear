package com.example.dosewear.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.data.DoseStatus
import com.example.dosewear.data.Fmt
import com.example.dosewear.notif.DoseNotifier
import com.example.dosewear.presentation.ConfirmChip
import com.example.dosewear.presentation.Hint
import com.example.dosewear.presentation.NavChip
import com.example.dosewear.presentation.Pill
import com.example.dosewear.presentation.PlainChip
import com.example.dosewear.presentation.ScreenTitle
import com.example.dosewear.presentation.SectionTitle
import com.example.dosewear.presentation.StepperRow
import com.example.dosewear.presentation.SupplementDraft
import com.example.dosewear.presentation.card
import com.example.dosewear.presentation.listPadding
import com.example.dosewear.presentation.rememberTextInput
import com.example.dosewear.presentation.rotaryScroll
import com.example.dosewear.presentation.stepDouble
import com.example.dosewear.presentation.theme.Amber
import com.example.dosewear.presentation.theme.Coral
import com.example.dosewear.presentation.theme.Mint
import com.example.dosewear.presentation.theme.Sky
import com.example.dosewear.presentation.theme.Steel
import com.example.dosewear.presentation.theme.Violet
import com.example.dosewear.presentation.theme.supplementColor
import com.example.dosewear.util.Surfaces
import com.example.dosewear.util.Texts
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/* ================================================================== */
/*  Liste                                                              */
/* ================================================================== */

private data class SupplementUi(
    val id: Long,
    val emoji: String,
    val name: String,
    val sub: String,
    val color: Color
)

@Composable
fun SupplementsScreen(onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { DoseRepository.get(ctx) }
    val listState = rememberScalingLazyListState()
    val all by remember(repo) { repo.observeSupplements() }.collectAsState(initial = emptyList())

    // Metinler veri degistiginde bir kez uretiliyor, kaydirirken degil.
    val ui = remember(all) {
        val passive = ctx.getString(R.string.supplement_passive)
        all.map { s ->
            SupplementUi(
                id = s.id,
                emoji = when {
                    !s.active -> "⏸"
                    s.isEmpty -> "⛔"
                    s.isLow -> "⚠️"
                    else -> "💊"
                },
                name = s.name,
                sub = buildString {
                    append(Texts.stockText(ctx, s))
                    if (s.strength.isNotBlank()) append(" • ${s.strength}")
                    if (!s.active) append(" • $passive")
                },
                color = if (s.isLow && s.active) Coral else supplementColor(s.colorIndex)
            )
        }
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
            item { ScreenTitle(stringResource(R.string.supplements_title)) }
            item {
                NavChip(
                    "➕",
                    stringResource(R.string.supplement_new),
                    stringResource(R.string.supplement_new_sub),
                    Mint,
                    onAdd
                )
            }

            if (ui.isEmpty()) {
                item { Hint(stringResource(R.string.supplements_empty)) }
            }

            items(ui) { row ->
                NavChip(
                    emoji = row.emoji,
                    label = row.name,
                    secondary = row.sub,
                    color = row.color,
                    onClick = { onOpen(row.id) }
                )
            }
        }
    }
}

/* ================================================================== */
/*  Detay + stok sayfasi                                               */
/* ================================================================== */

private data class MovementUi(val title: String, val sub: String, val color: Color)

@Composable
fun SupplementDetailScreen(
    supplementId: Long,
    onEdit: () -> Unit,
    onDeleted: () -> Unit
) {
    val ctx = LocalContext.current
    val repo = remember { DoseRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    val s by remember(supplementId) { repo.observeSupplement(supplementId) }
        .collectAsState(initial = null)
    val logs by remember(supplementId) { repo.observeLogsForSupplement(supplementId, 20) }
        .collectAsState(initial = emptyList())

    val movements = remember(logs) {
        logs.map { log ->
            MovementUi(
                title = "${Texts.statusEmoji(log.status)} ${Texts.dayLabel(ctx, log.scheduledAt)} ${log.scheduledText()}",
                sub = "${Texts.statusText(ctx, log.status)} • ${Texts.amountText(ctx, log)}",
                color = statusColor(log.status)
            )
        }
    }

    val sup = s
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
            if (sup == null) {
                item { Hint(stringResource(R.string.loading)) }
                return@ScalingLazyColumn
            }
            val c = supplementColor(sup.colorIndex)
            val unitLabel = Texts.unitLabel(ctx, sup.unit)

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .card(c.copy(alpha = 0.16f), 22.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        sup.name,
                        color = c,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (sup.strength.isNotBlank()) {
                        Text(sup.strength, color = Steel, fontSize = 11.sp, maxLines = 1)
                    }
                    Text(
                        Fmt.num(sup.stock),
                        color = if (sup.isLow) Coral else c,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(unitLabel, color = Steel, fontSize = 11.sp, maxLines = 1)
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when {
                            sup.isEmpty -> Pill(stringResource(R.string.pill_out), Coral)
                            sup.isLow -> Pill(stringResource(R.string.pill_low), Coral)
                            else -> Pill(stringResource(R.string.pill_ok), Mint)
                        }
                        if (!sup.active) Pill(stringResource(R.string.pill_passive), Steel)
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.section_adjust_stock)) }
            item {
                StepperRow(
                    label = stringResource(R.string.stock_on_hand, unitLabel),
                    value = Fmt.num(sup.stock),
                    color = c,
                    onMinus = {
                        scope.launch { repo.adjustStock(sup.id, -1.0); Surfaces.refreshAll(ctx) }
                    },
                    onPlus = {
                        scope.launch { repo.adjustStock(sup.id, 1.0); Surfaces.refreshAll(ctx) }
                    }
                )
            }
            item {
                NavChip(
                    emoji = "🛒",
                    label = stringResource(R.string.bought_more),
                    secondary = stringResource(
                        R.string.bought_more_sub,
                        "+" + Texts.amount(ctx, sup.refillAmount, sup.unit)
                    ),
                    color = Mint,
                    onClick = {
                        scope.launch {
                            repo.refill(sup.id)
                            DoseNotifier.cancelLowStock(ctx, sup.id)
                            Surfaces.refreshAll(ctx)
                        }
                    }
                )
            }
            item {
                Hint(
                    stringResource(
                        R.string.threshold_hint,
                        Texts.amount(ctx, sup.lowStockThreshold, sup.unit)
                    )
                )
            }

            item { SectionTitle(stringResource(R.string.section_recent), Violet) }
            if (movements.isEmpty()) {
                item { Hint(stringResource(R.string.no_records)) }
            }
            items(movements) { m ->
                PlainChip(label = m.title, secondary = m.sub, onClick = { }, color = m.color)
            }

            item { SectionTitle(stringResource(R.string.section_card)) }
            item {
                NavChip(
                    "✏️",
                    stringResource(R.string.edit),
                    stringResource(R.string.supplement_edit_sub),
                    Sky,
                    onEdit
                )
            }
            item {
                ConfirmChip(
                    label = stringResource(R.string.delete),
                    confirmLabel = stringResource(R.string.delete_confirm),
                    secondary = stringResource(R.string.supplement_delete_sub),
                    color = Coral,
                    onConfirm = {
                        scope.launch {
                            repo.deleteSupplement(sup)
                            DoseNotifier.cancelLowStock(ctx, sup.id)
                            Surfaces.refreshAll(ctx)
                            onDeleted()
                        }
                    }
                )
            }
        }
    }
}

/* ================================================================== */
/*  Ekle / duzenle                                                     */
/* ================================================================== */

@Composable
fun SupplementEditScreen(supplementId: Long, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { DoseRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    val existing by remember(supplementId) {
        if (supplementId > 0) repo.observeSupplement(supplementId) else flowOf(null)
    }.collectAsState(initial = null)

    LaunchedEffect(existing) { existing?.let { SupplementDraft.loadOnce(it) } }

    val nameInput = rememberTextInput { SupplementDraft.name = it }
    val strengthInput = rememberTextInput { SupplementDraft.strength = it }

    val c = supplementColor(SupplementDraft.colorIndex)
    val unitLabel = Texts.unitLabel(ctx, SupplementDraft.unit)

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
                ScreenTitle(
                    if (supplementId > 0) stringResource(R.string.supplement_edit_title)
                    else stringResource(R.string.supplement_new_title)
                )
            }

            item {
                val field = stringResource(R.string.name_field)
                PlainChip(
                    label = SupplementDraft.name.ifBlank { stringResource(R.string.name_placeholder) },
                    secondary = stringResource(R.string.name_hint),
                    onClick = { nameInput(field) },
                    color = if (SupplementDraft.name.isBlank()) Steel else c
                )
            }
            item {
                val field = stringResource(R.string.strength_field)
                PlainChip(
                    label = SupplementDraft.strength.ifBlank {
                        stringResource(R.string.strength_placeholder)
                    },
                    secondary = stringResource(R.string.strength_hint),
                    onClick = { strengthInput(field) },
                    color = Steel
                )
            }
            item {
                PlainChip(
                    label = stringResource(R.string.unit_row, unitLabel),
                    secondary = stringResource(R.string.unit_row_sub),
                    onClick = { SupplementDraft.unit = Texts.nextUnit(SupplementDraft.unit) },
                    color = Sky
                )
            }

            item { SectionTitle(stringResource(R.string.section_stock)) }
            item {
                StepperRow(
                    label = stringResource(R.string.field_on_hand),
                    value = Fmt.num(SupplementDraft.stock),
                    color = Mint,
                    onMinus = {
                        SupplementDraft.stock =
                            stepDouble(SupplementDraft.stock, -1, 1.0, 0.0, 9999.0)
                    },
                    onPlus = {
                        SupplementDraft.stock =
                            stepDouble(SupplementDraft.stock, +1, 1.0, 0.0, 9999.0)
                    }
                )
            }
            item {
                StepperRow(
                    label = stringResource(R.string.field_threshold),
                    value = Fmt.num(SupplementDraft.threshold),
                    color = Coral,
                    onMinus = {
                        SupplementDraft.threshold =
                            stepDouble(SupplementDraft.threshold, -1, 1.0, 0.0, 999.0)
                    },
                    onPlus = {
                        SupplementDraft.threshold =
                            stepDouble(SupplementDraft.threshold, +1, 1.0, 0.0, 999.0)
                    }
                )
            }
            item {
                StepperRow(
                    label = stringResource(R.string.field_box_size, unitLabel),
                    value = Fmt.num(SupplementDraft.refill),
                    color = Amber,
                    onMinus = {
                        SupplementDraft.refill =
                            stepDouble(SupplementDraft.refill, -1, 5.0, 5.0, 999.0)
                    },
                    onPlus = {
                        SupplementDraft.refill =
                            stepDouble(SupplementDraft.refill, +1, 5.0, 5.0, 999.0)
                    }
                )
            }

            item { SectionTitle(stringResource(R.string.section_color)) }
            item { ColorPickerRow() }

            item {
                PlainChip(
                    label = if (SupplementDraft.active) stringResource(R.string.status_row_active)
                    else stringResource(R.string.status_row_passive),
                    secondary = stringResource(R.string.status_row_sub),
                    onClick = { SupplementDraft.active = !SupplementDraft.active },
                    color = if (SupplementDraft.active) Mint else Steel
                )
            }

            item {
                val untitled = stringResource(R.string.untitled)
                NavChip(
                    emoji = "💾",
                    label = stringResource(R.string.save),
                    secondary = null,
                    color = Mint,
                    onClick = {
                        scope.launch {
                            repo.upsertSupplement(SupplementDraft.toSupplement(existing, untitled))
                            SupplementDraft.invalidate()
                            Surfaces.refreshAll(ctx)
                            onDone()
                        }
                    }
                )
            }
            item { Hint(stringResource(R.string.supplement_save_hint)) }
        }
    }
}

@Composable
private fun ColorPickerRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        (0..7).forEach { i ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (SupplementDraft.colorIndex == i) 24.dp else 18.dp)
                    .clip(CircleShape)
                    .clickable { SupplementDraft.colorIndex = i }
                    .card(supplementColor(i), 12.dp)
            )
        }
    }
}

/* ------------------------------------------------------------------ */

fun statusColor(s: DoseStatus): Color = when (s) {
    DoseStatus.TAKEN -> Mint
    DoseStatus.SNOOZED -> Amber
    DoseStatus.SKIPPED -> Steel
    DoseStatus.MISSED -> Coral
    DoseStatus.PENDING -> Sky
}
