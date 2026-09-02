package com.example.dosewear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.example.dosewear.R
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.data.DoseStatus
import com.example.dosewear.data.Fmt
import com.example.dosewear.presentation.MainActivity
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future

/**
 * Saat yuzunun yanindaki kart: siradaki 5 doz alt alta.
 * Periyodik guncelleme YOK; veri degistiginde Surfaces.refreshTile() ile durtulur.
 */
class MainTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class TileData(
        val rows: List<String>,
        val footer: String,
        val accent: Int
    )

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val repo = DoseRepository.get(applicationContext)
        val pending = repo.logs.openDoses().filter { it.status == DoseStatus.PENDING }
        val (taken, total) = repo.todayAdherence()

        val data = when {
            // Onay bekleyen varsa oncelik onlarin.
            pending.isNotEmpty() -> TileData(
                rows = pending.take(MAX_ROWS).map {
                    "${Fmt.hhmm(it.scheduledAt)} · ${it.supplementName}"
                },
                footer = footer(taken, total),
                accent = COLOR_AMBER
            )

            else -> {
                val next = repo.upcoming(MAX_ROWS)
                if (next.isEmpty()) {
                    TileData(
                        rows = listOf(
                            getString(R.string.tile_none),
                            getString(R.string.tile_none_sub)
                        ),
                        footer = footer(taken, total),
                        accent = COLOR_STEEL
                    )
                } else {
                    val fallback = getString(R.string.reminder_fallback)
                    TileData(
                        rows = next.map {
                            "${Fmt.hhmm(it.triggerAt)} · ${it.title.ifBlank { fallback }}"
                        },
                        footer = footer(taken, total),
                        accent = COLOR_MINT
                    )
                }
            }
        }

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(data)))
            .build()
    }

    private fun footer(taken: Int, total: Int): String =
        if (total > 0) getString(R.string.tile_today, taken, total)
        else getString(R.string.tile_today_none)

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    private fun layout(data: TileData): LayoutElementBuilders.LayoutElement {

        val openApp = ModifiersBuilders.Modifiers.Builder()
            .setClickable(
                ModifiersBuilders.Clickable.Builder()
                    .setId("open_dosewear")
                    .setOnClick(
                        ActionBuilders.LaunchAction.Builder()
                            .setAndroidActivity(
                                ActionBuilders.AndroidActivity.Builder()
                                    .setPackageName(packageName)
                                    .setClassName(MainActivity::class.java.name)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .setBackground(
                ModifiersBuilders.Background.Builder()
                    .setColor(argb(COLOR_BG))
                    .build()
            )
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            // wrap(): sutun yalnizca icerigi kadar yer kaplasin ki disindaki Box
            // onu gercekten dikeyde ORTALAYABILSIN. expand() oldugunda icerik
            // yukariya yapisiyordu.
            .setHeight(wrap())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                text("DOSEWEAR", 10f, COLOR_STEEL, LayoutElementBuilders.FONT_WEIGHT_BOLD)
            )
            .addContent(spacer(5f))
            .apply {
                data.rows.forEachIndexed { index, row ->
                    if (index > 0) addContent(spacer(2f))
                    // Ilk satir vurgulu; sonrakiler daha sonik ki goz once
                    // siradakine gitsin.
                    addContent(
                        if (index == 0) text(
                            row, 14f, data.accent, LayoutElementBuilders.FONT_WEIGHT_BOLD
                        ) else text(
                            row, 12f, COLOR_TEXT, LayoutElementBuilders.FONT_WEIGHT_NORMAL
                        )
                    )
                }
            }
            .addContent(spacer(6f))
            .addContent(
                text(data.footer, 10f, COLOR_STEEL, LayoutElementBuilders.FONT_WEIGHT_NORMAL)
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(openApp)
            .addContent(column)
            .build()
    }

    private fun spacer(height: Float): LayoutElementBuilders.Spacer =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    private fun text(
        value: String,
        size: Float,
        color: Int,
        weight: Int,
        maxLines: Int = 1
    ): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(maxLines)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(weight)
                    .build()
            )
            .build()

    companion object {
        private const val RESOURCES_VERSION = "1"

        /** Kartta gosterilecek en fazla doz satiri. */
        private const val MAX_ROWS = 5

        /** Sistem kartı en fazla bu sıklıkta kendi tazeler (pil dostu, 30 dk). */
        private const val FRESHNESS_MS = 30L * 60L * 1000L

        private const val COLOR_BG = 0xFF05070C.toInt()
        private const val COLOR_TEXT = 0xFFF2F6FF.toInt()
        private const val COLOR_STEEL = 0xFF8B98AC.toInt()
        private const val COLOR_MINT = 0xFF35E0A1.toInt()
        private const val COLOR_AMBER = 0xFFFFB74D.toInt()
    }
}
