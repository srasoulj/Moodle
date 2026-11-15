package app.moodle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/**
 * Selfie storage. Photos live in the app's private storage, so they never appear in the
 * gallery and no other app can read them.
 */
object Photos {
    fun dir(context: Context): File = File(context.filesDir, "selfies").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** Where a just-taken photo waits until the whole moment is saved (or discarded). */
    fun newPendingFile(context: Context): File =
        File(context.cacheDir, "pending_${UUID.randomUUID()}.jpg")

    fun clearPending(context: Context) {
        context.cacheDir.listFiles { f -> f.name.startsWith("pending_") }?.forEach { it.delete() }
    }

    /** Moves the pending photo into permanent storage and returns its file name. */
    fun commit(context: Context, pending: File, epochMillis: Long): String {
        val name = "moment_${epochMillis}_${UUID.randomUUID().toString().take(8)}.jpg"
        val dest = file(context, name)
        if (!pending.renameTo(dest)) {
            pending.copyTo(dest, overwrite = true)
            pending.delete()
        }
        return name
    }

    fun delete(context: Context, name: String) {
        file(context, name).delete()
    }

    /**
     * Decodes [file] so that neither side is much bigger than [maxSide], honouring the EXIF
     * orientation CameraX writes (otherwise selfies show up sideways).
     */
    fun decode(file: File, maxSide: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val orientation = try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
            else -> return raw
        }
        return try {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        } catch (_: OutOfMemoryError) {
            raw
        }
    }
}
