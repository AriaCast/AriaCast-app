package com.aria.ariacast.automation

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.aria.ariacast.AudioCastService
import com.aria.ariacast.R
import com.aria.ariacast.ui.theme.StatusColors

/** Process-wide cast status for the widget (updated by AudioCastService). */
object WidgetStatus {
    @Volatile var casting = false
    @Volatile var target: String? = null

    suspend fun update(context: Context, casting: Boolean, target: String?) {
        if (this.casting == casting && this.target == target) return
        this.casting = casting
        this.target = target
        runCatching { AriaWidget().updateAll(context) }
    }
}

/**
 * Home-screen widget (Discussion #11): status dot (green = casting, red = last speaker
 * ready), the speaker's name, and one tap to start or stop.
 */
class AriaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = context.getSharedPreferences(AudioCastService.PREFS_NAME, Context.MODE_PRIVATE)
        val casting = WidgetStatus.casting
        val name = WidgetStatus.target ?: prefs.getString(AudioCastService.KEY_LAST_SERVER_NAME, null)
        provideContent {
            GlanceTheme {
                val action = if (casting) actionStartService(Intent(context, AudioCastService::class.java).setAction(AudioCastService.ACTION_STOP))
                    else actionStartActivity(Intent(context, CastShortcutActivity::class.java))
                Box(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).clickable(action).padding(16.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (name != null) {
                                Box(GlanceModifier.size(12.dp).cornerRadius(6.dp)
                                    .background(ColorProvider(if (casting) StatusColors.Connected else StatusColors.Available))) {}
                                Spacer(GlanceModifier.width(8.dp))
                            }
                            Text(context.getString(R.string.app_name), style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                        }
                        Text(
                            name ?: context.getString(R.string.widget_pick_speaker),
                            style = TextStyle(fontSize = 16.sp, color = GlanceTheme.colors.onSurface), maxLines = 1,
                        )
                        Text(
                            context.getString(if (casting) R.string.widget_tap_stop else R.string.widget_tap_cast),
                            style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant),
                        )
                    }
                }
            }
        }
    }
}

class AriaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AriaWidget()
}
