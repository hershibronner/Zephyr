package app.zephyr.fitness.data.food

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dev.zephyr.core.food.FoodGuesser
import dev.zephyr.core.food.OnDeviceGuess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Identifies food in a photo entirely on the device.
 *
 * No key, no network, no cost — and considerably less capable than the Claude path. An image
 * labeller answers "what is this" from a few hundred learned categories; it cannot read portion
 * size, and anything homemade or mixed will not be in its vocabulary at all. Those limits are
 * reported rather than smoothed over, because a confident wrong number is worse than an admission.
 */
class OnDeviceFoodRecogniser {

    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                // Below the guesser's own threshold, so it sees the weak tail and decides.
                .setConfidenceThreshold(0.4f)
                .build(),
        )
    }

    /** Takes the same base64 JPEG the online path sends, so both photo routes share one capture. */
    suspend fun recognise(jpegBase64: String): OnDeviceGuess = withContext(Dispatchers.Default) {
        val bitmap = decode(jpegBase64)
            ?: return@withContext OnDeviceGuess(note = "That photo couldn't be read. Try again.")

        val labels = runCatching { label(bitmap) }.getOrDefault(emptyList())
        bitmap.recycle()

        FoodGuesser.fromLabels(labels)
    }

    private fun decode(base64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    private suspend fun label(bitmap: Bitmap): List<Pair<String, Float>> =
        suspendCancellableCoroutine { continuation ->
            // Rotation is already applied by MealPhoto, so the image is upright here.
            labeler.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { labels ->
                    continuation.resume(labels.map { it.text to it.confidence })
                }
                .addOnFailureListener { continuation.resume(emptyList()) }
        }
}
