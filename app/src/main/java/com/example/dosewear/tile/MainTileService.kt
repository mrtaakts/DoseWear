package com.example.dosewear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
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
 * Saat yuzunun yanindaki kart: siradaki doz ya da onay bekleyen dozlar.
 * Periyodik guncelleme YOK; veri degistiginde Surfaces.refreshTile() ile durtulur.
 */
class MainTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val repo = DoseRepository.get(applicationContext)
        val open = repo.logs.openDoses()
        val pending = open.filter { it.status == DoseStatus.PENDING }
        val next = repo.nextUpcoming()
        val (taken, total) = repo.todayAdherence()

        val headline: String
        val sub: String
        val accent: Int

        when {
            pending.isNotEmpty() -> {
                headline = if (pending.size == 1) pending.first().supplementName
                else getString(R.string.tile_pending_many, pending.size)
                sub = getString(
                    R.string.tile_pending_sub, Fmt.hhmm(pending.first().scheduledAt)
                )
                accent = COLOR_AMBER
            }
            next != null -> {
                headline = Fmt.hhmm(next.triggerAt)
                sub = next.title.ifBlank { getString(R.string.reminder_fallback) }
                accent = COLOR_MINT
            }
            else -> {
                headline = getString(R.string.tile_none)
                sub = getString(R.string.tile_none_sub)
                accent = COLOR_STEEL
            }
        }

        val footer = if (total > 0) getString(R.string.tile_today, taken, total)
        else getString(R.string.tile_today_none)

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_MS)
            .setTileTimeline(
                TimelineBuilders.Timeline.fromLayoutElement(
                    layout(headline, sub, footer, accent)
                )
            )
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future {
        ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    private fun layout(
        headline: String,
        sub: String,
        footer: String,
        accent: Int
    ): LayoutElementBuilders.LayoutElement {

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
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(openApp)
            .addContent(
                text("DOSEWEAR", 11f, COLOR_STEEL, LayoutElementBuilders.FONT_WEIGHT_BOLD)
            )
            .addContent(text(headline, 20f, accent, LayoutElementBuilders.FONT_WEIGHT_BOLD, 2))
            .addContent(text(sub, 13f, COLOR_TEXT, LayoutElementBuilders.FONT_WEIGHT_NORMAL, 2))
            .addContent(text(footer, 12f, COLOR_STEEL, LayoutElementBuilders.FONT_WEIGHT_NORMAL))
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(column)
            .build()
    }

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

        /** Sistem kartı en fazla bu sıklıkta kendi tazeler (pil dostu, 30 dk). */
        private const val FRESHNESS_MS = 30L * 60L * 1000L

        private const val COLOR_BG = 0xFF05070C.toInt()
        private const val COLOR_TEXT = 0xFFF2F6FF.toInt()
        private const val COLOR_STEEL = 0xFF8B98AC.toInt()
        private const val COLOR_MINT = 0xFF35E0A1.toInt()
        private const val COLOR_AMBER = 0xFFFFB74D.toInt()
    }
}
