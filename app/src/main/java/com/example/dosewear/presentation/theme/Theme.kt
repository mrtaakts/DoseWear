package com.example.dosewear.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/* ------------------------------------------------------------------ */
/*  Palet                                                              */
/* ------------------------------------------------------------------ */

val Ink = Color(0xFF05070C)          // arka plan
val Slate = Color(0xFF161C27)        // kart / yuzey
val SlateHi = Color(0xFF223044)      // secili yuzey

val Mint = Color(0xFF35E0A1)         // ana vurgu / "aldım"
val MintDark = Color(0xFF127A56)
val Sky = Color(0xFF4FC3F7)          // hatirlaticilar
val Amber = Color(0xFFFFB74D)        // ertele
val Coral = Color(0xFFFF6B6B)        // kacirildi / stok bitti
val Violet = Color(0xFFB388FF)       // gecmis
val Steel = Color(0xFF8B98AC)        // ikincil metin

val OnDark = Color(0xFFF2F6FF)

/** Takviye kartlari icin renk paleti (colorIndex ile secilir). */
val SupplementColors = listOf(
    Color(0xFF35E0A1),
    Color(0xFF4FC3F7),
    Color(0xFFFFB74D),
    Color(0xFFB388FF),
    Color(0xFFFF8A80),
    Color(0xFF80CBC4),
    Color(0xFFFFD54F),
    Color(0xFF9FA8DA)
)

fun supplementColor(index: Int): Color =
    SupplementColors[((index % SupplementColors.size) + SupplementColors.size) % SupplementColors.size]

private val DoseColors = Colors(
    primary = Mint,
    primaryVariant = MintDark,
    secondary = Sky,
    secondaryVariant = Violet,
    background = Ink,
    surface = Slate,
    error = Coral,
    onPrimary = Color(0xFF06231A),
    onSecondary = Color(0xFF04202B),
    onBackground = OnDark,
    onSurface = OnDark,
    onSurfaceVariant = Steel,
    onError = Color(0xFF2B0A0A)
)

@Composable
fun DoseWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = DoseColors, content = content)
}
