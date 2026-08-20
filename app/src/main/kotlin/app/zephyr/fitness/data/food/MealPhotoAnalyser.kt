package app.zephyr.fitness.data.food

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One dish Claude identified in the photo. */
@Serializable
data class EstimatedItem(
    val name: String,
    @SerialName("portion") val portion: String = "",
    @SerialName("grams") val grams: Double = 0.0,
    val kcal: Int,
    @SerialName("protein_g") val proteinG: Double = 0.0,
    @SerialName("carbs_g") val carbsG: Double = 0.0,
    @SerialName("fat_g") val fatG: Double = 0.0,
)

@Serializable
data class MealEstimate(
    val items: List<EstimatedItem> = emptyList(),
    /** low / medium / high — surfaced verbatim so the user can judge whether to correct it. */
    val confidence: String = "medium",
    val note: String = "",
) {
    val totalKcal: Int get() = items.sumOf { it.kcal }
    val totalProteinG: Double get() = items.sumOf { it.proteinG }
}

/**
 * Estimates a meal's calories from a photo, using Claude's vision capability.
 *
 * Photo estimation is genuinely approximate — depth and density are not visible, so a "medium" bowl
 * could be 300 or 600 kcal. That uncertainty is returned rather than hidden: the confidence and the
 * per-item breakdown are shown, and every number stays editable before it is logged. A single
 * confident-looking total would be the dishonest design, and would quietly corrupt the ledger the
 * whole app is built on.
 *
 * Called with the user's own API key. Raw HTTP against /v1/messages rather than the Anthropic Java
 * SDK: that SDK pulls Jackson, kotlin-reflect and three jsonschema modules, which is a large amount
 * of server-shaped dependency to put in an APK for one request.
 */
class MealPhotoAnalyser(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(2, TimeUnit.MINUTES)
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyse(jpegBase64: String, apiKey: String): MealEstimate = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", 8000)
            putJsonObject("output_config") {
                // The task is bounded and the latency is a user staring at a spinner, so effort is
                // kept low rather than paying for deliberation this does not need.
                put("effort", "low")
            }
            put("system", SYSTEM_PROMPT)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(
                                buildJsonObject {
                                    put("type", "image")
                                    putJsonObject("source") {
                                        put("type", "base64")
                                        put("media_type", "image/jpeg")
                                        put("data", jpegBase64)
                                    }
                                },
                            )
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", USER_PROMPT)
                                },
                            )
                        }
                    },
                )
            }
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(readableError(response.code, body))
            }
            parse(body)
        }
    }

    private fun parse(body: String): MealEstimate {
        val root = json.parseToJsonElement(body).jsonObject

        // A safety decline arrives as HTTP 200, so stop_reason has to be checked before content.
        val stopReason = root["stop_reason"]?.jsonPrimitive?.contentOrNull
        if (stopReason == "refusal") {
            error("Claude declined to analyse this photo. Log it manually instead.")
        }

        val text = root["content"]?.jsonArray
            ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: error("Claude returned no readable answer")

        // The model is asked for bare JSON, but a stray fence or sentence should not lose the meal.
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) error("Could not read the estimate. Try another photo.")

        return runCatching { json.decodeFromString<MealEstimate>(text.substring(start, end + 1)) }
            .getOrElse { error("Could not read the estimate. Try another photo.") }
    }

    private fun readableError(code: Int, body: String): String {
        val apiMessage = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        return when (code) {
            401 -> "That API key was rejected. Check it in Settings."
            429 -> "Rate limited by the API. Wait a moment and try again."
            in 500..599 -> "The API is having trouble. Try again shortly."
            else -> apiMessage ?: "Photo analysis failed (HTTP $code)"
        }
    }

    private companion object {
        const val MODEL = "claude-opus-5"
        const val ANTHROPIC_VERSION = "2023-06-01"

        val SYSTEM_PROMPT = """
            You estimate the nutrition of a meal from a photograph, for a fitness app.

            Reply with a single JSON object and nothing else — no prose, no markdown fence:
            {"items":[{"name":"...","portion":"...","grams":0,"kcal":0,"protein_g":0,"carbs_g":0,"fat_g":0}],
             "confidence":"low|medium|high","note":"..."}

            Break the plate into the separate foods you can actually identify, one entry each.
            Use everyday portion words people recognise ("half a chicken breast", "one cup of rice"),
            and give your best gram estimate alongside.

            Be honest about uncertainty rather than splitting the difference. Depth and density are
            not visible in a photo, and oil and sauces hide enormous amounts of energy, so say "low"
            whenever the portion size or preparation is genuinely ambiguous. A wrong number the user
            trusts is worse than a wide one they check. Use "note" for the single assumption that
            most affects the total — for example whether something looks fried or grilled.

            If the photo contains no food at all, return an empty items list and say so in "note".
        """.trimIndent()

        val USER_PROMPT = "What am I eating, and roughly what does it cost me? Reply with the JSON only."
    }
}
