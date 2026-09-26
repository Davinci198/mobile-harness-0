package com.jarves.mh.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jarves.mh.MainActivity
import com.jarves.mh.R

/**
 * Home screen "Ask anything" widget. Tapping the card opens the chat via
 * [MainActivity.ACTION_ASK], reusing the same entry point as the assist
 * gesture, the launcher shortcut and the keep-alive notification action.
 */
class AskWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val openChat = PendingIntent.getActivity(
            context,
            6,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_ASK
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val views = RemoteViews(context.packageName, R.layout.widget_ask).apply {
            setOnClickPendingIntent(R.id.widget_ask_root, openChat)
        }
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }
}
