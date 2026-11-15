package app.moodle

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Opened by tapping an emoji in the notification. A moment is only ever saved when the mood,
 * a selfie and a position can all be stored together; leaving the screen saves nothing.
 *
 * Android does not allow silent background camera capture, so this screen is always shown
 * (it needs the person to frame the selfie anyway).
 */
class CaptureActivity : ComponentActivity() {

    companion object {
        const val ACTION_CAPTURE = "app.moodle.action.CAPTURE"
        const val EXTRA_MOOD = "app.moodle.extra.MOOD"
    }

    private var mood by mutableStateOf<Mood?>(null)
    private var tapMillis = 0L
    private var tapZone = ""
    internal var pendingFile by mutableStateOf<File?>(null)
    private var locState by mutableStateOf<LocState>(LocState.Idle)
    private var permsOk by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var shutterBusy by mutableStateOf(false)
    private var cameraMessage by mutableStateOf<String?>(null)
    private var saved = false

    private lateinit var tracker: LocationTracker
    private val io = Executors.newSingleThreadExecutor()
    // Compose state on purpose: CameraX binds asynchronously after the first composition, and
    // the shutter's enabled flag has to react when binding finishes.
    internal var imageCapture by mutableStateOf<ImageCapture?>(null)
    private var cameraProvider: ProcessCameraProvider? = null
    private var frontCamera = true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
        if (permsOk) tracker.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tracker = LocationTracker(this) { locState = it }

        if (!beginMoment(intent)) {
            finish()
            return
        }
        refreshPermissions()
        if (!permsOk) requestPermissions()

        setContent { MoodleTheme { CaptureScreen() } }
    }

    /** A second emoji tap while this screen is open starts a completely fresh moment. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tracker.stop()
        if (beginMoment(intent)) {
            refreshPermissions()
            if (permsOk) tracker.start()
        }
    }

    override fun onStart() {
        super.onStart()
        refreshPermissions()
        // Start locating right away, in parallel with the camera, so the GPS wait doesn't
        // stack on top of the time spent framing the selfie.
        if (permsOk && locState !is LocState.Fix) tracker.start()
    }

    override fun onStop() {
        tracker.stop()
        super.onStop()
    }

    override fun onDestroy() {
        tracker.stop()
        cameraProvider?.unbindAll()
        if (!saved) pendingFile?.delete()
        io.shutdown()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------------------

    private fun beginMoment(intent: Intent): Boolean {
        val chosen = Mood.fromId(intent.getIntExtra(EXTRA_MOOD, -1)) ?: return false
        pendingFile?.delete()
        pendingFile = null
        saving = false
        shutterBusy = false
        cameraMessage = null
        locState = LocState.Idle
        mood = chosen
        // The moment is "when you tapped", not when the shutter fired.
        tapMillis = System.currentTimeMillis()
        tapZone = ZoneId.systemDefault().id
        return true
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshPermissions() {
        // Rough location is enough, so either the precise or the approximate permission works.
        permsOk = granted(Manifest.permission.CAMERA) &&
            (granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    // -----------------------------------------------------------------------------------
    // Camera
    // -----------------------------------------------------------------------------------

    private fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val front = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val selector = when {
                    front -> CameraSelector.DEFAULT_FRONT_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    else -> {
                        cameraMessage = "No camera found on this device."
                        return@addListener
                    }
                }
                frontCamera = front

                val preview = Preview.Builder().build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                // Cap the resolution: full-sensor selfies are 5-12 MB each and add up fast.
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1440, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setResolutionSelector(resolution)
                    .setJpegQuality(85)
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
                imageCapture = capture
                cameraMessage = null
            } catch (e: Exception) {
                cameraMessage = "Couldn't start the camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (shutterBusy) return
        shutterBusy = true
        val file = Photos.newPendingFile(this)
        val options = ImageCapture.OutputFileOptions.Builder(file)
            // Mirror front-camera shots so the saved selfie matches what the preview showed.
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = frontCamera })
            .build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    shutterBusy = false
                    pendingFile = file
                }

                override fun onError(exception: ImageCaptureException) {
                    shutterBusy = false
                    file.delete()
                    cameraMessage = "Couldn't take the photo (${exception.message}). Try again."
                }
            },
        )
    }

    private fun retake() {
        pendingFile?.delete()
        pendingFile = null
    }

    // -----------------------------------------------------------------------------------
    // Saving
    // -----------------------------------------------------------------------------------

    private fun save() {
        val chosen = mood ?: return
        val file = pendingFile ?: return
        val fix = locState as? LocState.Fix ?: return
        if (saving) return
        saving = true
        val whenMillis = tapMillis
        val zone = tapZone
        io.execute {
            try {
                val name = Photos.commit(this, file, whenMillis)
                try {
                    MoodDb.get(this).insert(
                        NewMoment(
                            epochMillis = whenMillis,
                            zoneId = zone,
                            mood = chosen,
                            photoName = name,
                            latitude = fix.latitude,
                            longitude = fix.longitude,
                            accuracyMeters = fix.accuracy,
                            approximate = fix.approximate,
                        )
                    )
                } catch (e: Exception) {
                    Photos.delete(this, name) // never leave a photo without its row
                    throw e
                }
                runOnUiThread {
                    saved = true
                    Toast.makeText(this, "${chosen.emoji} Moment saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                file.delete()
                runOnUiThread {
                    saving = false
                    pendingFile = null
                    Toast.makeText(this, "Couldn't save: ${e.message}. Please retake.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------------------------

    @Composable
    private fun CaptureScreen() {
        val current = mood ?: return
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val file = pendingFile
            when {
                !permsOk -> PermissionPanel()
                file == null -> CameraPane()
                else -> ReviewPane(file)
            }
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { finish() }, enabled = !saving) {
                    Text("✕  Cancel", color = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${current.emoji}  ${current.label}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }

    @Composable
    private fun CameraPane() {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        bindCamera(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            DisposableEffect(Unit) {
                onDispose {
                    imageCapture = null
                    cameraProvider?.unbindAll()
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LocationStatus()
                cameraMessage?.let {
                    Text(it, color = Color(0xFFFFB4AB), textAlign = TextAlign.Center)
                }
                Box(
                    Modifier
                        .padding(top = 12.dp)
                        .size(76.dp)
                        .semantics { contentDescription = "Take selfie" }
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(if (shutterBusy) Color.Gray else Color.White)
                        .clickable(enabled = !shutterBusy && imageCapture != null, role = Role.Button) { takePhoto() }
                )
            }
        }
    }

    @Composable
    private fun ReviewPane(file: File) {
        Box(Modifier.fillMaxSize()) {
            Selfie(file, 1600, Modifier.fillMaxSize(), ContentScale.Fit)
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LocationStatus()
                Row(
                    Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { retake() },
                        enabled = !saving,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White),
                    ) { Text("Retake") }
                    Button(
                        onClick = { save() },
                        enabled = locState is LocState.Fix && !saving,
                    ) { Text(if (saving) "Saving…" else "Save moment") }
                }
            }
        }
    }

    @Composable
    private fun LocationStatus() {
        val state = locState
        val message: String
        var actionLabel: String? = null
        var action: () -> Unit = {}
        when (state) {
            LocState.Idle, LocState.Searching -> message = "📍 Finding your location…"
            is LocState.Fix -> {
                message = if (state.approximate) {
                    "📍 Rough location found"
                } else {
                    "📍 Location locked" + if (state.accuracy >= 0) " (±${state.accuracy.toInt()} m)" else ""
                }
            }
            LocState.LocationOff -> {
                message = "📍 Location is turned off"
                actionLabel = "Turn on"
                action = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }
            LocState.NoPermission -> {
                message = "📍 Location permission needed"
                actionLabel = "Allow"
                action = { requestPermissions() }
            }
            LocState.NoFix -> {
                message = "📍 Couldn't get a location. Try near a window or outdoors."
                actionLabel = "Retry"
                action = { tracker.start() }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null) {
                TextButton(onClick = action) { Text(actionLabel) }
            }
        }
    }

    @Composable
    private fun PermissionPanel() {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Recording a moment needs the camera (selfie) and location (approximate is fine).",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = { requestPermissions() }, modifier = Modifier.padding(top = 16.dp)) {
                Text("Allow access")
            }
            TextButton(onClick = { openAppSettings() }) { Text("Open app settings") }
        }
    }
}
