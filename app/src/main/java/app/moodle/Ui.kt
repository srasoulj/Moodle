package app.moodle

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Root of the history UI: a month calendar, and a per-day list one tap away. */
@Composable
fun MoodleApp(
    db: MoodDb,
    tick: Int,
    readiness: Readiness,
    onFix: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var month by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var selectedDay by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val zone = ZoneId.systemDefault()

    val moments = remember(month, tick) {
        val (from, to) = CalendarMath.queryRange(month, zone)
        db.between(from, to)
    }
    val byDay = remember(moments) { CalendarMath.byDay(moments) }
    val total = remember(tick) { db.count() }

    val day = selectedDay
    val dayMoments = if (day != null) byDay[day].orEmpty() else emptyList()

    // The last moment of a day was deleted: fall back to the calendar.
    LaunchedEffect(day, dayMoments.isEmpty()) {
        if (day != null && dayMoments.isEmpty()) selectedDay = null
    }
    BackHandler(enabled = selectedDay != null) { selectedDay = null }

    if (day != null && dayMoments.isNotEmpty()) {
        DayScreen(
            date = day,
            moments = dayMoments,
            onBack = { selectedDay = null },
            onDelete = { m ->
                db.delete(m.id)?.let { Photos.delete(context, it) }
                onChanged()
            },
        )
    } else {
        CalendarScreen(
            month = month,
            byDay = byDay,
            isEmpty = total == 0,
            readiness = readiness,
            onFix = onFix,
            onPrev = { month = month.minusMonths(1) },
            onNext = { month = month.plusMonths(1) },
            onToday = { month = YearMonth.now() },
            onDayClick = { selectedDay = it },
        )
    }
}

// ---------------------------------------------------------------------------------------
// Calendar
// ---------------------------------------------------------------------------------------

@Composable
private fun CalendarScreen(
    month: YearMonth,
    byDay: Map<LocalDate, List<Moment>>,
    isEmpty: Boolean,
    readiness: Readiness,
    onFix: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
    // Read from the observable configuration so the calendar re-renders if the system
    // language or region changes while the app is open.
    val locale = LocalConfiguration.current.locales[0]
    val firstDay = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val cells = remember(month, firstDay) { CalendarMath.gridDays(month, firstDay) }
    val today = LocalDate.now()

    Scaffold { inner ->
        Column(
            Modifier
                .padding(inner)
                .padding(horizontal = 8.dp)
                .fillMaxSize()
        ) {
            if (!readiness.allGood) SetupBanner(readiness, onFix)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrev) { Text("‹", fontSize = 28.sp) }
                Text(
                    text = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale) +
                        " " + month.year,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                if (month != YearMonth.now()) TextButton(onClick = onToday) { Text("Today") }
                TextButton(onClick = onNext) { Text("›", fontSize = 28.sp) }
            }

            Row(Modifier.fillMaxWidth()) {
                CalendarMath.weekdayOrder(firstDay).forEach { dow ->
                    Text(
                        text = dow.getDisplayName(TextStyle.SHORT, locale),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (isEmpty) {
                Text(
                    text = "Pull down your notification shade and tap an emoji in the Moodle " +
                        "notification to log how you feel. Each moment is saved with the time, " +
                        "your GPS position and a selfie.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                cells.chunked(7).forEach { week ->
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        week.forEach { date ->
                            DayCell(
                                date = date,
                                moments = if (date != null) byDay[date].orEmpty() else emptyList(),
                                isToday = date == today,
                                onClick = { if (date != null) onDayClick(date) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayCell(
    date: LocalDate?,
    moments: List<Moment>,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (date == null) {
        Box(modifier)
        return
    }
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .padding(1.dp)
            .clip(shape)
            .background(if (moments.isNotEmpty()) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest)
            .then(if (isToday) Modifier.border(2.dp, scheme.primary, shape) else Modifier)
            .clickable(enabled = moments.isNotEmpty(), onClick = onClick)
            .padding(3.dp)
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (moments.isNotEmpty()) {
            // Every emoji of the day, shrinking as the day gets busier, capped with "+N".
            val n = moments.size
            val size = when {
                n <= 2 -> 22.sp
                n <= 4 -> 18.sp
                n <= 8 -> 14.sp
                else -> 12.sp
            }
            val shown = if (n > MAX_EMOJIS_IN_CELL) moments.take(MAX_EMOJIS_IN_CELL) else moments
            FlowRow(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.Center,
            ) {
                shown.forEach { Text(it.mood.emoji, fontSize = size) }
                if (n > MAX_EMOJIS_IN_CELL) {
                    Text(
                        "+${n - MAX_EMOJIS_IN_CELL}",
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

private const val MAX_EMOJIS_IN_CELL = 8

@Composable
private fun SetupBanner(readiness: Readiness, onFix: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Setup needed", style = MaterialTheme.typography.titleSmall)
            if (readiness.notifications != NotificationStatus.OK) {
                Text("• Notifications are off, so the emoji bar can't be shown.")
            }
            if (!readiness.camera) Text("• Camera permission is needed for the selfie.")
            if (!readiness.location) Text("• Location permission is needed for the GPS position.")
            Button(onClick = onFix, modifier = Modifier.padding(top = 8.dp)) { Text("Fix") }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Day detail
// ---------------------------------------------------------------------------------------

@Composable
private fun DayScreen(
    date: LocalDate,
    moments: List<Moment>,
    onBack: () -> Unit,
    onDelete: (Moment) -> Unit,
) {
    val context = LocalContext.current
    var toDelete by remember { mutableStateOf<Moment?>(null) }
    var viewing by remember { mutableStateOf<Moment?>(null) }

    Scaffold { inner ->
        Column(Modifier.padding(inner)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text(
                    text = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(moments, key = { it.id }) { m ->
                    MomentCard(
                        m = m,
                        onPhoto = { viewing = m },
                        onLocation = { openInMaps(context, m) },
                        onDelete = { toDelete = m },
                    )
                }
            }
        }
    }

    viewing?.let { m ->
        Dialog(
            onDismissRequest = { viewing = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { viewing = null }
            ) {
                Selfie(Photos.file(context, m.photoName), 1600, Modifier.fillMaxSize(), ContentScale.Fit)
            }
        }
    }

    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this moment?") },
            text = {
                Text(
                    "The ${m.mood.emoji} moment from ${timeText(m, withZone = false)}, with its " +
                        "selfie and location, will be removed from this phone. This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    onDelete(m)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MomentCard(
    m: Moment,
    onPhoto: () -> Unit,
    onLocation: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Selfie(
                    file = Photos.file(context, m.photoName),
                    maxSide = 320,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onPhoto),
                    contentScale = ContentScale.Crop,
                )
                Column(Modifier.weight(1f)) {
                    Text("${m.mood.emoji}  ${m.mood.label}", style = MaterialTheme.typography.titleLarge)
                    Text(timeText(m, withZone = true), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "📍 " + locationText(m),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable(onClick = onLocation),
                    )
                    if (m.approximate) {
                        Text(
                            "approximate location",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

private fun timeText(m: Moment, withZone: Boolean): String {
    val base = m.localDateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM))
    return if (withZone && m.zone != ZoneId.systemDefault()) "$base (${m.zone.id})" else base
}

private fun locationText(m: Moment): String {
    val coords = String.format(Locale.US, "%.5f, %.5f", m.latitude, m.longitude)
    return if (m.accuracyMeters >= 0) coords + " (±${m.accuracyMeters.toInt()} m)" else coords
}

/** Hands the coordinates to whatever maps app is installed. This app itself stays offline. */
private fun openInMaps(context: Context, m: Moment) {
    val coords = String.format(Locale.US, "%.6f,%.6f", m.latitude, m.longitude)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$coords(Moodle%20moment)"))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No maps app installed", Toast.LENGTH_SHORT).show()
    }
}

/** Selfie loader: decodes off the main thread and respects EXIF orientation. */
@Composable
fun Selfie(file: File, maxSide: Int, modifier: Modifier, contentScale: ContentScale) {
    val bitmap by produceState<Bitmap?>(initialValue = null, file.absolutePath, maxSide) {
        value = withContext(Dispatchers.IO) { Photos.decode(file, maxSide) }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Selfie",
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
