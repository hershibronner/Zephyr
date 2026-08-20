package app.zephyr.fitness.data.food

import app.zephyr.fitness.BuildConfig
import app.zephyr.fitness.data.db.FoodEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Barcode lookups against Open Food Facts.
 *
 * The response is parsed field by field rather than deserialised into a data class: the database is
 * crowd-sourced and enormously inconsistent — fields are missing, sometimes strings where numbers
 * are expected, and energy arrives as either kJ or kcal depending on who entered it. A strict schema
 * would reject perfectly usable products over one bad field.
 */
class OpenFoodFactsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Null when the barcode is not in the database — common for local and store-brand goods. */
    suspend fun lookup(barcode: String): FoodEntity? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=$FIELDS")
            // Open Food Facts asks apps to identify themselves and rate-limits anonymous traffic.
            .header("User-Agent", "Zephyr/${BuildConfig.VERSION_NAME} (Android; personal fitness app)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val root = json.parseToJsonElement(body).jsonObject

            if (root["status"]?.jsonPrimitive?.intOrNull != 1) return@withContext null
            val product = root["product"]?.jsonObject ?: return@withContext null
            parse(product, barcode)
        }
    }

    private fun parse(product: JsonObject, barcode: String): FoodEntity? {
        val nutriments = product["nutriments"]?.jsonObject ?: return null

        // Prefer the kcal field; fall back to kJ, which is what European entries usually carry.
        val kcalPer100 = nutriments.number("energy-kcal_100g")
            ?: nutriments.number("energy_100g")?.let { it / KJ_PER_KCAL }
            ?: return null

        val name = product.text("product_name")
            ?: product.text("generic_name")
            ?: return null

        return FoodEntity(
            name = name.trim().take(80),
            brand = product.text("brands")?.split(",")?.firstOrNull()?.trim(),
            barcode = barcode,
            kcalPer100 = kcalPer100,
            proteinPer100 = nutriments.number("proteins_100g") ?: 0.0,
            carbsPer100 = nutriments.number("carbohydrates_100g") ?: 0.0,
            fatPer100 = nutriments.number("fat_100g") ?: 0.0,
            servingGrams = product.text("serving_quantity")?.toDoubleOrNull(),
            servingLabel = product.text("serving_size"),
            // Not custom: it came from the shared database, so the user did not author it.
            isCustom = false,
        )
    }

    /** Tolerates numbers that arrive as JSON strings, which Open Food Facts does constantly. */
    private fun JsonObject.number(key: String): Double? {
        val primitive = this[key]?.jsonPrimitive ?: return null
        return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
    }

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        const val KJ_PER_KCAL = 4.184

        val FIELDS = listOf(
            "product_name", "generic_name", "brands", "nutriments",
            "serving_size", "serving_quantity",
        ).joinToString(",")
    }
}
