package app.moodle

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** What the app still needs before the notification → selfie → GPS flow can work. */
data class Readiness(
    val notifications: NotificationStatus,
    val camera: Boolean,
    val location: Boolean,
) {
    val allGood: Boolean get() = notifications == NotificationStatus.OK && camera && location
}

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private var readiness by mutableStateOf(Readiness(NotificationStatus.OK, true, true))
    private val prefs by lazy { getSharedPreferences("permissions", Context.MODE_PRIVATE) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Photos.clearPending(this)
        refreshState()
        if (savedInstanceState == null) {
            // Show the system dialog only while Android will still show it. Once a permission is
            // permanently denied, the banner's Fix button is the way to Settings, not every launch.
            val missing = missingRuntimePermissions()
            if (missing.isNotEmpty() && !permanentlyDenied(missing)) launchPermissionRequest(missing)
        }
        setContent {
            MoodleTheme {
                MoodleApp(
                    db = MoodDb.get(this),
                    tick = tick,
                    readiness = readiness,
                    onFix = ::requestOrOpenSettings,
                    onChanged = { tick++ },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        tick++ // pick up moments saved from the notification while we were in the background
    }

    private fun refreshState() {
        readiness = Readiness(
            notifications = MoodNotification.status(this),
            camera = granted(Manifest.permission.CAMERA),
            location = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        )
        // Always (re)post: harmless if it is already there, and it heals a dismissed one.
        MoodNotification.post(this)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun missingRuntimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!granted(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION) // must be requested together on Android 12+
        }
    }

    /**
     * Asks for what is missing. Once Android stops showing the dialog (denied twice) the only
     * way forward is the system settings, so go there instead.
     */
    private fun requestOrOpenSettings() {
        val missing = missingRuntimePermissions()
        if (missing.isEmpty()) {
            // Runtime permissions are fine, so notifications are blocked in system settings.
            openNotificationSettings()
            return
        }
        if (permanentlyDenied(missing)) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        } else {
            launchPermissionRequest(missing)
        }
    }

    /** Asked before, and Android no longer wants us to explain: the dialog won't appear again. */
    private fun permanentlyDenied(missing: List<String>) = missing.any {
        prefs.getBoolean(it, false) && !shouldShowRequestPermissionRationale(it)
    }

    private fun launchPermissionRequest(missing: List<String>) {
        val edit = prefs.edit()
        missing.forEach { edit.putBoolean(it, true) }
        edit.apply()
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun openNotificationSettings() {
        val intent = if (readiness.notifications == NotificationStatus.CHANNEL_BLOCKED) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, MoodNotification.CHANNEL_ID)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }
}
