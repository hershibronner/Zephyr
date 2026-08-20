package app.zephyr.fitness.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.zephyr.fitness.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * What a published build says about itself. CI writes this file next to the APK on every release.
 */
@Serializable
data class UpdateManifest(
    @SerialName("versionCode") val versionCode: Int,
    @SerialName("versionName") val versionName: String,
    @SerialName("apkUrl") val apkUrl: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("sizeBytes") val sizeBytes: Long = 0,
    @SerialName("notes") val notes: String = "",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val fraction: Float) : UpdateState
    data class ReadyToInstall(val manifest: UpdateManifest, val file: File) : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Keeps the app current without a trip to GitHub.
 *
 * Android cannot hot-swap compiled code, so a new build still means installing an APK — but that's
 * the only part left for the user: the app notices, fetches, and verifies on its own, and all that
 * remains is one tap on the system installer.
 *
 * The downloaded file is hash-checked before it is ever handed to the installer. This is executable
 * code arriving over the network, and a redirect to the wrong place or a truncated download should
 * fail loudly rather than get installed.
 */
class UpdateManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val downloadDir: File
        get() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Null when this build is already current. */
    suspend fun check(): UpdateManifest? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(BuildConfig.UPDATE_MANIFEST_URL)
            .header("User-Agent", "Zephyr/${BuildConfig.VERSION_NAME}")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Update check failed (HTTP ${response.code})")
            val body = response.body?.string().orEmpty()
            val manifest = json.decodeFromString<UpdateManifest>(body)
            manifest.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        }
    }

    /**
     * Fetches the APK and verifies it. [onProgress] reports 0f..1f, or -1f when the server declines
     * to say how large the file is.
     */
    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = File(downloadDir, "zephyr-${manifest.versionCode}.apk")

        // A previous run may already have fetched and verified this exact build.
        if (target.exists() && sha256Of(target).equals(manifest.sha256, ignoreCase = true)) {
            onProgress(1f)
            return@withContext target
        }

        val request = Request.Builder()
            .url(manifest.apkUrl)
            .header("User-Agent", "Zephyr/${BuildConfig.VERSION_NAME}")
            .build()

        val partial = File(downloadDir, "zephyr-${manifest.versionCode}.apk.part")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Download failed (HTTP ${response.code})")
            val body = response.body ?: error("Download returned no body")
            val total = body.contentLength().takeIf { it > 0 } ?: manifest.sizeBytes

            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else -1f)
                    }
                }
            }
        }

        val actual = sha256Of(partial)
        if (!actual.equals(manifest.sha256, ignoreCase = true)) {
            partial.delete()
            error("Downloaded file did not match the expected checksum")
        }

        partial.renameTo(target)
        // Old versions are dead weight once a newer one has landed.
        downloadDir.listFiles()?.forEach { if (it != target) it.delete() }
        target
    }

    /**
     * True once the user has allowed this app to install packages. Sideloaded updates are impossible
     * without it, and on Android 8+ it is granted per-app rather than globally.
     */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Opens the system screen where the user grants this app permission to install updates. */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Hands the verified APK to the system installer. The user still confirms. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
