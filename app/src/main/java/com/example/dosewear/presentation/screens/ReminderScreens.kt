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
import com.example.dosewear.alarm.AlarmScheduler
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.data.Fmt
import com.example.dosewear.data.Reminder
import com.example.dosewear.data.Supplement
import com.example.dosewear.presentation.ConfirmChip
import com.example.dosewear.presentation.DoseUiActions
import com.example.dosewear.presentation.Hint
import com.example.dosewear.presentation.NavChip
import com.example.dosewear.presentation.PlainChip
import com.example.dosewear.presentation.ReminderDraft
import com.example.dosewear.presentation.ScreenTitle
import com.example.dosewear.presentation.SectionTitle
import com.example.dosewear.presentation.StepperRow
import com.example.dosewear.presentation.card
import com.example.dosewear.presentation.listPadding
import com.example.dosewear.presentation.rememberTextInput
import com.example.dosewear.presentation.rotaryScroll
import com.example.dosewear.presentation.stepDouble
import com.example.dosewear.presentation.stepInt
import com.example.dosewear.presentation.theme.Amber
import com.example.dosewear.presentation.theme.Coral
import com.example.dosewear.presentation.theme.Mint
import com.example.dosewear.presentation.theme.Sky
import com.example.dosewear.presentation.theme.Slate
import com.example.dosewear.presentation.theme.SlateHi
import com.example.dosewear.presentation.theme.Steel
import com.example.dosewear.presentation.theme.supplementColor
import com.example.dosewear.util.Surfaces
import com.example.dosewear.util.Texts
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/* ================================================================== */
/*  Liste                                                              */
/* ================================================================== */

private data class ReminderUi(
    val id: Long,
    val emoji: String,
    val label: String,
    val sub: String,
    val color: Color
)

@Composable
fun RemindersScreen(onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { DoseRepository.get(ctx) }
    val listState = rememberScalingLazyListState()
    val all by remember(repo) { repo.observeReminders() }.collectAsState(initial = emptyList())

    val ui = remember(all) {
        val fallback = ctx.getString(R.string.reminder_fallback)
        all.map { rwi ->
            val r = rwi.reminder
            ReminderUi(
                id = r.id,
                emoji = if (r.enabled) "⏰" else "⏸",
                label = "${r.timeText()}  ${rwi.title().ifBlank { fallback }}",
                sub = "${Texts.daysText(ctx, r.daysMask)} • " +
                    ctx.getString(R.string.reminder_count, rwi.items.size),
                color = if (r.enabled) Sky else Steel
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
            item { ScreenTitle(stringResource(R.string.reminders_title)) }
            item {
                NavChip(
                    "➕",
                    stringResource(R.string.reminder_new),
                    stringResource(R.string.reminder_new_sub),
                    Sky,
                    onAdd
                )
            }

            if (ui.isEmpty()) {
                item { Hint(stringResource(R.string.reminders_empty)) }
            }

            items(ui) { row ->
                NavChip(
                    emoji = row.emoji,
                    label = row.label,
                    secondary = row.sub,
                    color = row.color,
                    onClick = { onOpen(row.id) }
                )
            }
            if (ui.isNotEmpty()) {
                item { Hint(stringResource(R.string.reminders_hint)) }
            }
        }
    }
}

/* ================================================================== */
/*  Ekle / duzenle / kopyala                                           */
/* ================================================================== */

private data class PickerRow(
    val supplement: Supplement,
    val color: Color,
    val stockText: String
)

@Composable
fun ReminderEditScreen(
    reminderId: Long,
    onDone: () -> Unit,
    onAddSupplement: () -> Unit,
    onCopy: () -> Unit
) {
    val ctx = LocalContext.current
    val repo = remember { DoseRepository.get(ctx) }
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    val existing by remember(reminderId) {
        if (reminderId > 0) repo.observeReminder(reminderId) else flowOf(null)
    }.collectAsState(initial = null)

    val supplements by remember(repo) { repo.observeActiveSupplements() }
        .collectAsState(initial = emptyList())

    LaunchedEffect(reminderId, existing) {
        if (reminderId == 0L) {
            // Kopyalama akisinda taslak dolu geliyor; sifirlamayacagiz.
            if (ReminderDraft.pendingCopy) ReminderDraft.pendingCopy = false
            else ReminderDraft.startNew(repo.prefs)
        } else {
            existing?.let { ReminderDraft.loadOnce(it) }
        }
    }

    // Sabit kisimlar (isim, renk, stok metni) veri degistiginde bir kez uretiliyor.
    val pickerRows = remember(supplements) {
        supplements.map { s ->
            PickerRow(
                supplement = s,
                color = supplementColor(s.colorIndex),
                stockText = ctx.getString(R.string.stock_of, Texts.stockText(ctx, s))
            )
        }
    }

    val labelInput = rememberTextInput { ReminderDraft.label = it }
    val copySuffix = stringResource(R.string.copy_suffix)

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
                    if (reminderId > 0) stringResource(R.string.reminder_edit_title)
                    else stringResource(R.string.reminder_new_title)
                )
            }

            /* ---------------- Saat ---------------- */
            item {
                Text(
                    Fmt.hhmm(ReminderDraft.hour, ReminderDraft.minute),
                    color = Sky,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
            item {
                StepperRow(
                    label = stringResource(R.string.field_hour),
                    value = Fmt.hhmm(ReminderDraft.hour, 0).substring(0, 2),
                    color = Sky,
                    onMinus = { ReminderDraft.hour = (ReminderDraft.hour + 23) % 24 },
                    onPlus = { ReminderDraft.hour = (ReminderDraft.hour + 1) % 24 }
                )
            }
            item {
                StepperRow(
                    label = stringResource(R.string.field_minute),
                    value = Fmt.hhmm(0, ReminderDraft.minute).substring(3),
                    color = Sky,
                    onMinus = { ReminderDraft.minute = (ReminderDraft.minute + 55) % 60 },
                    onPlus = { ReminderDraft.minute = (ReminderDraft.minute + 5) % 60 }
                )
            }

            /* ---------------- Gunler ---------------- */
            item { SectionTitle(stringResource(R.string.section_days)) }
            item { DayPickerRow() }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                ) {
                    PresetChip(stringResource(R.string.preset_everyday), Reminder.ALL_DAYS)
                    PresetChip(stringResource(R.string.preset_weekdays), Reminder.WEEKDAYS)
                    PresetChip(stringResource(R.string.preset_weekend), Reminder.WEEKEND)
                }
            }

            /* ---------------- Takviyeler ---------------- */
            item { SectionTitle(stringResource(R.string.section_pick), Mint) }
            if (pickerRows.isEmpty()) {
                item {
                    NavChip(
                        "➕",
                        stringResource(R.string.add_supplement_first),
                        stringResource(R.string.add_supplement_first_sub),
                        Mint,
                        onAddSupplement
                    )
                }
            }
            items(pickerRows) { row ->
                val s = row.supplement
                val selected = ReminderDraft.isSelected(s.id)
                Column(modifier = Modifier.fillMaxWidth()) {
                    PlainChip(
                        label = "${if (selected) "✓" else "○"}  ${s.name}",
                        secondary = if (selected)
                            "${Texts.amount(ctx, ReminderDraft.amountOf(s.id), s.unit)} • ${row.stockText}"
                        else row.stockText,
                        onClick = { ReminderDraft.toggle(s.id) },
                        color = if (selected) row.color else Steel,
                        background = if (selected) SlateHi else Slate
                    )
                    if (selected) {
                        StepperRow(
                            label = stringResource(R.string.amount_of, s.name),
                            value = Texts.amount(ctx, ReminderDraft.amountOf(s.id), s.unit),
                            color = row.color,
                            onMinus = {
                                ReminderDraft.setAmount(
                                    s.id,
                                    stepDouble(ReminderDraft.amountOf(s.id), -1, 0.5, 0.5, 20.0)
                                )
                            },
                            onPlus = {
                                ReminderDraft.setAmount(
                                    s.id,
                                    stepDouble(ReminderDraft.amountOf(s.id), +1, 0.5, 0.5, 20.0)
                                )
                            }
                        )
                    }
                }
            }
            item {
                Hint(
                    if (ReminderDraft.items.size > 1)
                        stringResource(R.string.multi_hint, ReminderDraft.items.size)
                    else stringResource(R.string.single_hint)
                )
            }

            /* ---------------- Erteleme ---------------- */
            item { SectionTitle(stringResource(R.string.section_snooze), Amber) }
            item {
                StepperRow(
                    label = stringResource(R.string.field_snooze),
                    value = stringResource(R.string.minutes_short, ReminderDraft.snoozeMinutes),
                    color = Amber,
                    onMinus = {
                        ReminderDraft.snoozeMinutes =
                            stepInt(ReminderDraft.snoozeMinutes, -1, 5, 5, 120)
                    },
                    onPlus = {
                        ReminderDraft.snoozeMinutes =
                            stepInt(ReminderDraft.snoozeMinutes, +1, 5, 5, 120)
                    }
                )
            }
            item {
                StepperRow(
                    label = stringResource(R.string.field_jitter),
                    value = stringResource(R.string.minutes_plusminus, ReminderDraft.jitterMinutes),
                    color = Amber,
                    onMinus = {
                        ReminderDraft.jitterMinutes =
                            stepInt(ReminderDraft.jitterMinutes, -1, 1, 0, 30)
                    },
                    onPlus = {
                        ReminderDraft.jitterMinutes =
                            stepInt(ReminderDraft.jitterMinutes, +1, 1, 0, 30)
                    }
                )
            }
            item {
                StepperRow(
                    label = stringResource(R.string.field_max_snooze),
                    value = stringResource(R.string.times_count, ReminderDraft.maxSnoozes),
                    color = Amber,
                    onMinus = {
                        ReminderDraft.maxSnoozes = stepInt(ReminderDraft.maxSnoozes, -1, 1, 0, 10)
                    },
                    onPlus = {
                        ReminderDraft.maxSnoozes = stepInt(ReminderDraft.maxSnoozes, +1, 1, 0, 10)
                    }
                )
            }
            item { Hint(stringResource(R.string.snooze_hint)) }

            /* ---------------- Etiket + durum ---------------- */
            item { SectionTitle(stringResource(R.string.section_other)) }
            item {
                val field = stringResource(R.string.label_field)
                PlainChip(
                    label = ReminderDraft.label.ifBlank {
                        stringResource(R.string.label_placeholder)
                    },
                    secondary = stringResource(R.string.label_hint),
                    onClick = { labelInput(field) },
                    color = Steel
                )
            }
            item {
                PlainChip(
                    label = if (ReminderDraft.enabled) stringResource(R.string.reminder_on)
                    else stringResource(R.string.reminder_off),
                    secondary = stringResource(R.string.reminder_status_sub),
                    onClick = { ReminderDraft.enabled = !ReminderDraft.enabled },
                    color = if (ReminderDraft.enabled) Mint else Steel
                )
            }

            /* ---------------- Kaydet / kopyala / sil ---------------- */
            item {
                val nextPreview = ReminderDraft.toReminder().nextTriggerAt()
                Hint(
                    when {
                        !ReminderDraft.enabled -> stringResource(R.string.reminder_disabled)
                        nextPreview > 0 -> stringResource(
                            R.string.next_preview,
                            Texts.dayLabel(ctx, nextPreview),
                            Fmt.hhmm(nextPreview),
                            Texts.relative(ctx, nextPreview)
                        )
                        else -> stringResource(R.string.days_none)
                    }
                )
            }
            item {
                NavChip(
                    emoji = "💾",
                    label = stringResource(R.string.save),
                    secondary = if (ReminderDraft.items.isEmpty())
                        stringResource(R.string.need_supplement) else null,
                    color = if (ReminderDraft.items.isEmpty()) Steel else Mint,
                    onClick = {
                        if (ReminderDraft.items.isEmpty()) return@NavChip
                        scope.launch {
                            val id = repo.saveReminder(
                                ReminderDraft.toReminder(),
                                ReminderDraft.toItems()
                            )
                            ReminderDraft.invalidate()
                            DoseUiActions.rescheduleReminder(ctx, id)
                            onDone()
                        }
                    }
                )
            }
            if (reminderId > 0) {
                item {
                    NavChip(
                        emoji = "📋",
                        label = stringResource(R.string.reminder_copy),
                        secondary = stringResource(R.string.reminder_copy_sub),
                        color = Sky,
                        onClick = {
                            ReminderDraft.prepareCopy(copySuffix)
                            onCopy()
                        }
                    )
                }
                item {
                    ConfirmChip(
                        label = stringResource(R.string.delete),
                        confirmLabel = stringResource(R.string.delete_confirm),
                        color = Coral,
                        onConfirm = {
                            scope.launch {
                                existing?.let { rwi ->
                                    AlarmScheduler.cancelReminder(ctx, rwi.reminder.id)
                                    repo.deleteReminder(rwi.reminder)
                                }
                                ReminderDraft.invalidate()
                                Surfaces.refreshAll(ctx)
                                onDone()
                            }
                        }
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */

@Composable
private fun DayPickerRow() {
    val ctx = LocalContext.current
    val labels = remember { (0..6).map { Texts.dayShort(ctx, it).take(2) } }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        (0..6).forEach { bit ->
            val on = ((ReminderDraft.daysMask shr bit) and 1) == 1
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable {
                        ReminderDraft.daysMask = ReminderDraft.daysMask xor (1 shl bit)
                    }
                    .card(if (on) Sky.copy(alpha = 0.30f) else Slate, 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    labels[bit],
                    color = if (on) Sky else Steel,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PresetChip(text: String, mask: Int) {
    val selected = ReminderDraft.daysMask == mask
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .clickable { ReminderDraft.daysMask = mask }
            .card(if (selected) Sky.copy(alpha = 0.28f) else Slate, 14.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (selected) Sky else Steel, fontSize = 10.sp, maxLines = 1)
    }
}
