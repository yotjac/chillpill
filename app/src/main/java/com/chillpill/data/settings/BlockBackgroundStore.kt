package com.chillpill.data.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Stores the user's own block screen background in `filesDir/backgrounds/`.
 *
 * The URI handed back by the photo picker is only a temporary grant, so the picked image is
 * copied immediately. Every import writes a *new* file name, which means the block screen can
 * never read a half-written file and any cached bitmap keyed on the name invalidates naturally.
 * Orphans (drafts that were never saved, replaced photos) are removed by [cleanup].
 */
class BlockBackgroundStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME)

    fun fileFor(fileName: String): File = fileFor(context, fileName)

    /**
     * Copies [uri] into app storage, downscaled to at most [MAX_DIMENSION] px on the long side
     * and rotated per its EXIF orientation.
     *
     * @return the new file name, or null when the image could not be read or decoded.
     */
    suspend fun importImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            // Bounds pass: decodeStream always returns null with inJustDecodeBounds, so the
            // stream itself is what gets null-checked here.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return@withContext null

            val bitmap = transform(decoded, readOrientationMatrix(uri))

            val target = dir.apply { mkdirs() }.let { File(it, "custom_${System.currentTimeMillis()}.jpg") }
            val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
            FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            bitmap.recycle()
            if (!temp.renameTo(target)) {
                temp.delete()
                return@withContext null
            }
            target.name
        } catch (e: Exception) {
            Log.e(TAG, "import failed for uri=$uri", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "import ran out of memory for uri=$uri", e)
            null
        }
    }

    /** Deletes every stored image except [keep] (and any leftover temp files). */
    suspend fun cleanup(keep: Set<String>) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file ->
                // Never touch a temp file a concurrent import is still writing.
                val importInFlight = file.name.endsWith(TEMP_SUFFIX) &&
                    now - file.lastModified() < TEMP_GRACE_MS
                if (file.name !in keep && !importInFlight) file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanup failed", e)
        }
        Unit
    }

    /**
     * The most recently stored photo, or null when there is none. Lets the picker keep offering
     * the user's photo even while a bundled image is selected.
     */
    suspend fun latestFileName(): String? = withContext(Dispatchers.IO) {
        try {
            dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }
                ?.maxByOrNull { it.lastModified() }
                ?.name
        } catch (e: Exception) {
            Log.w(TAG, "latestFileName failed", e)
            null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sampleSize = 1
        var longSide = maxOf(width, height)
        while (longSide / 2 >= MAX_DIMENSION) {
            longSide /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * Builds the matrix that puts the image the right way up, covering rotations *and* the
     * mirrored orientations that some cameras write.
     */
    private fun readOrientationMatrix(uri: Uri): Matrix {
        val matrix = Matrix()
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            Log.w(TAG, "could not read EXIF orientation for uri=$uri", e)
            ExifInterface.ORIENTATION_NORMAL
        }
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
        return matrix
    }

    /** Applies [orientation] and scales down to [MAX_DIMENSION] when sampling left it larger. */
    private fun transform(source: Bitmap, orientation: Matrix): Bitmap {
        val longSide = maxOf(source.width, source.height)
        val scale = if (longSide > MAX_DIMENSION) MAX_DIMENSION.toFloat() / longSide else 1f
        if (scale == 1f && orientation.isIdentity) return source
        val matrix = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            postConcat(orientation)
        }
        val result = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (result !== source) source.recycle()
        return result
    }

    companion object {
        private const val TAG = "BlockBackgroundStore"
        private const val DIR_NAME = "backgrounds"
        private const val TEMP_SUFFIX = ".tmp"
        private const val TEMP_GRACE_MS = 60_000L
        private const val MAX_DIMENSION = 2048
        private const val JPEG_QUALITY = 85

        /**
         * Single source of the storage path, so UI code can locate a stored photo without
         * building a store instance of its own.
         */
        fun fileFor(context: Context, fileName: String): File =
            File(File(context.filesDir, DIR_NAME), fileName)
    }
}
