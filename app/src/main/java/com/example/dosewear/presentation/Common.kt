package com.example.dosewear.presentation

import android.app.RemoteInput
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.example.dosewear.presentation.theme.Slate
import com.example.dosewear.presentation.theme.Steel
import kotlin.math.roundToInt

/* ------------------------------------------------------------------ */
/*  Tekerlek (rotary) kaydirma                                         */
/* ------------------------------------------------------------------ */

/**
 * Yan tekerlek icin Wear'in kendi fling + haptik davranisi.
 * Bu olmadan sistem jenerik kaydirmaya dusuyor ve takilarak ilerliyor.
 */
@Composable
fun Modifier.rotaryScroll(state: ScalingLazyListState): Modifier {
    val focusRequester = remember { FocusRequester() }
    return this
        .requestFocusOnHierarchyActive()
        .rotaryScrollable(
            RotaryScrollableDefaults.behavior(scrollableState = state),
            focusRequester = focusRequester
        )
}

/* ------------------------------------------------------------------ */
/*  Cizim yardimcilari                                                 */
/* ------------------------------------------------------------------ */

/**
 * Kart arka plani. `clip(shape) + background(color)` yerine tek cagri:
 * clip her seferinde ayri bir graphics layer aciyor ve ScalingLazyColumn'un
 * kendi olcekleme katmaniyla ust uste binince kare suresi ikiye katlaniyordu.
 */
fun Modifier.card(color: Color, radius: Dp = 20.dp): Modifier =
    this.background(color, RoundedCornerShape(radius))

/** Tiklanabilir yuzey: dalga efekti kose disina tasmasin diye burada clip sart. */
fun Modifier.tappable(shape: Shape, onClick: () -> Unit): Modifier =
    this.clip(shape).clickable(onClick = onClick)

/* ------------------------------------------------------------------ */
/*  Sayi adimlama                                                      */
/* ------------------------------------------------------------------ */

/** Degeri adim izgarasina oturtarak arttirir/azaltir: 5, 10, 15... */
fun stepInt(current: Int, direction: Int, step: Int, min: Int, max: Int): Int {
    val snapped = (current.toDouble() / step).roundToInt() * step
    return (snapped + direction * step).coerceIn(min, max)
}

fun stepDouble(current: Double, direction: Int, step: Double, min: Double, max: Double): Double {
    val snapped = Math.round(current / step) * step
    return (snapped + direction * step).coerceIn(min, max)
}

/* ------------------------------------------------------------------ */
/*  Yasam dongusu                                                      */
/* ------------------------------------------------------------------ */

private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/** Ekran her one geldiginde artan sayac (izin durumlarini tazelemek icin). */
@Composable
fun rememberResumeTick(): Int {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(ctx) {
        val owner = ctx.findLifecycleOwner()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    return tick
}

/* ------------------------------------------------------------------ */
/*  Parcalar                                                           */
/* ------------------------------------------------------------------ */

@Composable
fun SectionTitle(text: String, color: Color = Steel) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
    )
}

@Composable
fun ScreenTitle(text: String, color: Color = MaterialTheme.colors.onBackground) {
    Text(
        text = text,
        color = color,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)
    )
}

/** Renkli, emoji ikonlu satir butonu. */
@Composable
fun NavChip(
    emoji: String,
    label: String,
    secondary: String? = null,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Chip(
        label = {
            Text(
                label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold
            )
        },
        secondaryLabel = secondary?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) }
        },
        icon = { Text(emoji, fontSize = 16.sp, modifier = Modifier.size(22.dp)) },
        onClick = onClick,
        colors = ChipDefaults.primaryChipColors(
            backgroundColor = color.copy(alpha = 0.22f),
            contentColor = color,
            secondaryContentColor = Steel,
            iconColor = color
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** Sadece metinli chip. */
@Composable
fun PlainChip(
    label: String,
    secondary: String? = null,
    onClick: () -> Unit,
    color: Color = Color(0xFFB9C4D6),
    background: Color = Slate,
    modifier: Modifier = Modifier
) {
    Chip(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = secondary?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) }
        },
        onClick = onClick,
        colors = ChipDefaults.primaryChipColors(
            backgroundColor = background,
            contentColor = color,
            secondaryContentColor = Steel
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** Iki adimli silme: ilk dokunusta "emin misin?"e doner. */
@Composable
fun ConfirmChip(
    label: String,
    confirmLabel: String,
    secondary: String? = null,
    emoji: String = "🗑",
    color: Color,
    onConfirm: () -> Unit
) {
    var armed by remember { mutableStateOf(false) }
    NavChip(
        emoji = if (armed) "⚠️" else emoji,
        label = if (armed) confirmLabel else label,
        secondary = if (armed) null else secondary,
        color = color,
        onClick = { if (armed) onConfirm() else armed = true }
    )
}

/** -  deger  + seklinde sayi ayarlayici. */
@Composable
fun StepperRow(
    label: String,
    value: String,
    color: Color,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .card(Slate, 18.dp)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label,
            color = Steel,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundMini("−", color, onMinus)
            Text(
                value,
                color = color,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            RoundMini("+", color, onPlus)
        }
    }
}

@Composable
fun RoundMini(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.primaryButtonColors(
            backgroundColor = color.copy(alpha = 0.25f),
            contentColor = color
        ),
        modifier = Modifier.size(36.dp)
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .card(color.copy(alpha = 0.20f), 10.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Hint(text: String) {
    Text(
        text,
        color = Steel,
        fontSize = 10.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** Renkli, tam genislikte aksiyon dugmesi. */
@Composable
fun ActionButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    fontSize: Int = 13,
    onClick: () -> Unit
) {
    val shape = remember(height) { RoundedCornerShape(height / 2) }
    Box(
        modifier = modifier
            .height(height)
            // Once arka plan, sonra clip+clickable: dalga efekti arka planin
            // ustunde kalsin ve kose disina tasmasin.
            .background(color.copy(alpha = 0.22f), shape)
            .tappable(shape, onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = color,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Yuvarlak ekranda ust/alt bosluk. */
fun listPadding() = PaddingValues(top = 28.dp, bottom = 44.dp, start = 8.dp, end = 8.dp)

/* ------------------------------------------------------------------ */
/*  Wear metin girisi (klavye / el yazisi / sesli giris)               */
/* ------------------------------------------------------------------ */

private const val REMOTE_INPUT_KEY = "dosewear_text"

@Composable
fun rememberTextInput(onResult: (String) -> Unit): (String) -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)
            ?.getCharSequence(REMOTE_INPUT_KEY)?.toString()
        if (!text.isNullOrBlank()) onResult(text.trim())
    }
    return remember(launcher) {
        { label: String ->
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            val inputs = listOf(RemoteInput.Builder(REMOTE_INPUT_KEY).setLabel(label).build())
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, inputs)
            launcher.launch(intent)
        }
    }
}
