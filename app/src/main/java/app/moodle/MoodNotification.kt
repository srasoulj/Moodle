package app.moodle

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class NotificationStatus { OK, PERMISSION_DENIED, APP_BLOCKED, CHANNEL_BLOCKED }

/**
 * The app's only entry point: a persistent, silent notification with one tappable emoji per
 * mood. Standard notification actions are capped at three, so the five emojis live in a
 * custom RemoteViews layout.
 */
object MoodNotification {
    const val CHANNEL_ID = "mood_entry"
    const val NOTIFICATION_ID = 1001
    const val ACTION_REPOST = "app.moodle.action.REPOST"

    /** View ids in display order: slot N shows the Nth mood in [Mood.entries]. */
    private val slotIds = intArrayOf(
        R.id.slot1, R.id.slot2, R.id.slot3, R.id.slot4, R.id.slot5, R.id.slot6,
        R.id.slot7, R.id.slot8, R.id.slot9, R.id.slot10, R.id.slot11, R.id.slot12,
        R.id.slot13,
    )

    fun status(context: Context): NotificationStatus {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return NotificationStatus.PERMISSION_DENIED
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return NotificationStatus.APP_BLOCKED
        }
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
            return NotificationStatus.CHANNEL_BLOCKED
        }
        return NotificationStatus.OK
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "Mood entry", NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "The always-visible row of mood emojis"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /** Posts (or refreshes) the notification. Returns false when the system won't show it. */
    @SuppressLint("MissingPermission") // guarded by status() below
    fun post(context: Context): Boolean {
        val app = context.applicationContext
        ensureChannel(app)
        if (status(app) != NotificationStatus.OK) return false

        // Android 12+ gives a custom notification only ~48dp when collapsed, so that view carries
        // the quick row; the second row of feelings appears when the notification is expanded.
        val collapsed = buildViews(app, expanded = false)
        val expanded = buildViews(app, expanded = true)

        val openApp = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Fired when the user swipes the notification away (Android 14+ allows that even for
        // ongoing notifications); the receiver simply posts it again.
        val deleted = PendingIntent.getBroadcast(
            app, 0,
            Intent(app, RepostReceiver::class.java).setAction(ACTION_REPOST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mood)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setContentIntent(openApp)
            .setDeleteIntent(deleted)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
        return true
    }

    internal fun buildViews(context: Context, expanded: Boolean): RemoteViews {
        check(Mood.entries.size <= slotIds.size) { "Every mood needs a slot in notification_mood_expanded.xml" }
        val layout = if (expanded) R.layout.notification_mood_expanded else R.layout.notification_mood_collapsed
        val views = RemoteViews(context.packageName, layout)
        Mood.entries.forEachIndexed { i, mood ->
            if (!expanded && mood.row != 1) return@forEachIndexed // not in the collapsed layout
            val id = slotIds[i]
            views.setTextViewText(id, mood.emoji)
            views.setContentDescription(id, mood.label)
            views.setOnClickPendingIntent(id, capturePendingIntent(context, mood))
        }
        return views
    }

    /**
     * Straight to the capture activity (a receiver that then launches an activity would be a
     * blocked "notification trampoline" on Android 12+). Every emoji gets its own request
     * code *and* data URI: PendingIntent identity ignores extras, so without this all five
     * buttons would collapse into one and log the same mood.
     */
    internal fun capturePendingIntent(context: Context, mood: Mood): PendingIntent {
        val intent = Intent(context, CaptureActivity::class.java)
            .setAction(CaptureActivity.ACTION_CAPTURE)
            .setData(Uri.parse("moodle://capture/${mood.id}"))
            .putExtra(CaptureActivity.EXTRA_MOOD, mood.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, mood.id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
