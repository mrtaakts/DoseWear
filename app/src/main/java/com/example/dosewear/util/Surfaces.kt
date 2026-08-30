package com.example.dosewear.util

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.example.dosewear.complication.MainComplicationService
import com.example.dosewear.tile.MainTileService

/**
 * Tile ve Complication'i "veri degisti" diye durtmek icin tek yer.
 * Periyodik yenileme YOK: sadece gercekten bir sey degistiginde cagrilir -> pil dostu.
 */
object Surfaces {

    fun refreshAll(context: Context) {
        refreshTile(context)
        refreshComplication(context)
    }

    fun refreshTile(context: Context) {
        runCatching {
            TileService.getUpdater(context.applicationContext)
                .requestUpdate(MainTileService::class.java)
        }
    }

    fun refreshComplication(context: Context) {
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(
                    context.applicationContext,
                    ComponentName(context.applicationContext, MainComplicationService::class.java)
                )
                .requestUpdateAll()
        }
    }
}
