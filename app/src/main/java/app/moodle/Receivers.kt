package app.moodle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notifications vanish on reboot and app update; bring ours back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> MoodNotification.post(context)
        }
    }
}

/** Triggered by the notification's delete intent: the user swiped it away, so re-post it. */
class RepostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MoodNotification.post(context)
    }
}
