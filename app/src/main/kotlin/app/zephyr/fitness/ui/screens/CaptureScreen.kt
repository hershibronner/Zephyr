package app.zephyr.fitness.ui.screens

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.zephyr.fitness.data.food.MealPhoto
import app.zephyr.fitness.ui.theme.Z
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class CaptureMode { BARCODE, PHOTO }

/**
 * The camera, doing double duty: reading a barcode off a packet, or photographing a plate.
 *
 * Both live on one screen because they answer the same question — "what am I about to eat?" — and
 * which one applies depends only on whether the food came in packaging. Switching between them is a
 * tap rather than a trip back to a menu.
 */
@Composable
fun CaptureScreen(
    mode: CaptureMode,
    busy: Boolean,
    status: String?,
    onModeChange: (CaptureMode) -> Unit,
    onBarcode: (String) -> Unit,
    onPhoto: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // A single-thread executor for analysis: ML Kit calls back off the main thread, and the frames
    // must be closed in order or the camera stalls after a few dozen images.
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    // The callbacks are captured by the analyser, which outlives a recomposition; without this the
    // camera would keep calling the first composition's lambdas.
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val currentBusy by rememberUpdatedState(busy)

    DisposableEffect(mode) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            provider = providerFuture.get()
            provider?.unbindAll()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            imageCapture = capture

            val useCases = mutableListOf(preview, capture)

            if (mode == CaptureMode.BARCODE) {
                val scanner = BarcodeScanning.getClient()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { proxy ->
                    scanBarcode(proxy, scanner, currentBusy) { value ->
                        ContextCompat.getMainExecutor(context).execute { currentOnBarcode(value) }
                    }
                }
                useCases += analysis
            }

            runCatching {
                provider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases.toTypedArray(),
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose { provider?.unbindAll() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        if (mode == CaptureMode.BARCODE) {
            // A frame to aim with. Barcodes read far more reliably when people fill the box.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.78f)
                    .height(168.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            )
        }

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Close",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onClose),
                )
                ModeToggle(mode, onModeChange)
            }

            Text(
                status ?: when (mode) {
                    CaptureMode.BARCODE -> "Point at the barcode on the packet"
                    CaptureMode.PHOTO -> "Frame the whole plate, from above if you can"
                },
                color = Color.White.copy(alpha = 0.85f), fontSize = 13.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(bottom = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> CircularProgressIndicator(color = Color.White)

                mode == CaptureMode.PHOTO -> Box(
                    Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable {
                            imageCapture?.let { capture -> takePhoto(capture, executor, onPhoto) }
                        },
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun ModeToggle(mode: CaptureMode, onModeChange: (CaptureMode) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CaptureMode.entries.forEach { entry ->
            val on = entry == mode
            Text(
                if (entry == CaptureMode.BARCODE) "Barcode" else "Photo",
                color = if (on) Z.Ink else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable { onModeChange(entry) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * Reads one frame. The proxy must be closed on every path — a leaked frame permanently stalls the
 * analyser, and the symptom is a camera that simply stops finding barcodes after a while.
 *
 * ImageProxy.image is opt-in in CameraX; ML Kit needs the underlying Image to avoid a copy, and
 * this is the documented way to feed it.
 */
@OptIn(ExperimentalGetImage::class)
private fun scanBarcode(
    proxy: ImageProxy,
    scanner: BarcodeScanner,
    busy: Boolean,
    onFound: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null || busy) {
        proxy.close()
        return
    }

    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull { it.rawValue != null && it.format in PRODUCT_FORMATS }
                ?.rawValue
                ?.let(onFound)
        }
        .addOnCompleteListener { proxy.close() }
}

/** Retail product symbologies only, so a QR code on the packaging is not mistaken for the product. */
private val PRODUCT_FORMATS = setOf(
    Barcode.FORMAT_EAN_13,
    Barcode.FORMAT_EAN_8,
    Barcode.FORMAT_UPC_A,
    Barcode.FORMAT_UPC_E,
)

private fun takePhoto(
    capture: ImageCapture,
    executor: Executor,
    onPhoto: (String) -> Unit,
) {
    capture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = image.planes.firstOrNull()?.buffer?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
                // Read the rotation before closing: the sensor rarely stores the frame upright, and
                // a raw byte copy discards the metadata that says so.
                val rotation = image.imageInfo.rotationDegrees
                image.close()

                if (bytes != null) {
                    MealPhoto.prepare(bytes, rotation)?.let(onPhoto)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                // Swallowed deliberately: the screen stays open so the user can simply try again,
                // which is more useful than an error dialog over a live camera.
            }
        },
    )
}
