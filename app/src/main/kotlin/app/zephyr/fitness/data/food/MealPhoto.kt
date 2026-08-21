package app.zephyr.fitness.data.food

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Turns a camera frame into something worth sending.
 *
 * Two things matter and both were wrong before. A modern phone shoots 8–12 MP, so the raw JPEG is
 * several megabytes and base64 adds another third — over mobile data that is a long, silent wait
 * that reads as the feature being broken. And the sensor almost never stores the image upright: the
 * rotation lives in metadata that a raw byte copy throws away, so a plate photographed in portrait
 * arrived at the model on its side, which is a genuinely harder image to read.
 */
object MealPhoto {

    /** Plenty for identifying food; a quarter of the bytes of anything larger. */
    private const val MAX_EDGE_PX = 1024
    private const val JPEG_QUALITY = 85

    /**
     * @param rotationDegrees from `ImageProxy.imageInfo.rotationDegrees`
     * @return base64 JPEG, or null when the bytes were not a decodable image
     */
    fun prepare(jpegBytes: ByteArray, rotationDegrees: Int): String? {
        val decoded = decodeDownsampled(jpegBytes) ?: return null

        val upright = if (rotationDegrees % 360 != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it != decoded) decoded.recycle() }
        } else {
            decoded
        }

        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        upright.recycle()

        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Decodes at a reduced size rather than decoding full-size and scaling down. A 12 MP frame is
     * ~48 MB as a bitmap, which is enough to hit an OutOfMemoryError on a mid-range phone before any
     * resizing happens.
     */
    private fun decodeDownsampled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / (sample * 2) >= MAX_EDGE_PX) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        // inSampleSize only halves, so one exact scale finishes the job.
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= MAX_EDGE_PX) return bitmap

        val ratio = MAX_EDGE_PX.toFloat() / edge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }
}
