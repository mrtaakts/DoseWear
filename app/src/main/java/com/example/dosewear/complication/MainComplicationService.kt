package com.example.dosewear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.example.dosewear.R
import com.example.dosewear.data.DoseRepository
import com.example.dosewear.data.DoseStatus
import com.example.dosewear.data.Fmt
import com.example.dosewear.presentation.MainActivity

/**
 * Saat yuzunde tek satirlik ozet: onay bekleyen doz varsa "!", yoksa siradaki dozun saati.
 */
class MainComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return shortText("08:00", getString(R.string.comp_title))
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null

        val repo = DoseRepository.get(applicationContext)
        val pending = repo.logs.openDoses().filter { it.status == DoseStatus.PENDING }
        if (pending.isNotEmpty()) {
            return shortText("!${pending.size}", getString(R.string.comp_pending))
        }

        val next = repo.nextUpcoming()
            ?: return shortText("--:--", getString(R.string.comp_none))

        return shortText(Fmt.hhmm(next.triggerAt), getString(R.string.comp_title))
    }

    private fun shortText(value: String, title: String): ComplicationData =
        ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(value).build(),
            PlainComplicationText.Builder(getString(R.string.comp_desc)).build()
        )
            .setTitle(PlainComplicationText.Builder(title).build())
            .setTapAction(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        0,
        Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
