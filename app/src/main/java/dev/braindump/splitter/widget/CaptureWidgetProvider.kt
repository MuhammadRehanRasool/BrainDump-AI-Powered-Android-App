package dev.braindump.splitter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.braindump.splitter.R
import dev.braindump.splitter.ui.capture.CaptureActivity

/**
 * Home-screen capture bar: tap the bar to type, tap the mic to dictate. The layout is
 * static, so there is no update schedule and the widget costs nothing in the background.
 */
class CaptureWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = buildViews(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    private fun buildViews(context: Context): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_capture).apply {
            setOnClickPendingIntent(R.id.widget_type, captureIntent(context, voice = false))
            setOnClickPendingIntent(R.id.widget_voice, captureIntent(context, voice = true))
        }

    private fun captureIntent(context: Context, voice: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            // The two intents differ only by an extra, so distinct request codes keep them apart.
            if (voice) REQUEST_VOICE else REQUEST_TYPE,
            CaptureActivity.intent(context, startWithVoice = voice).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        const val REQUEST_TYPE = 100
        const val REQUEST_VOICE = 101
    }
}
