package app.zephyr.fitness.ui

import app.zephyr.fitness.data.db.FoodEntity
import app.zephyr.fitness.data.food.MealEstimate

/**
 * Where the user is in the act of adding food.
 *
 * Modelled as one state rather than a pile of booleans because the steps are genuinely exclusive —
 * you are scanning, or reviewing what was found, or nothing — and separate flags let impossible
 * combinations exist.
 */
sealed interface FoodFlow {
    data object Idle : FoodFlow

    /** Camera open. [busy] covers the network round trip so the scanner stops re-firing. */
    data class Capturing(val busy: Boolean = false, val status: String? = null) : FoodFlow

    /** A barcode or search hit, waiting for the user to say how much of it they ate. */
    data class Portioning(val food: FoodEntity, val grams: String) : FoodFlow

    /** A photo estimate, itemised and editable before anything is written to the ledger. */
    data class Reviewing(val estimate: MealEstimate) : FoodFlow

    data class Failed(val reason: String) : FoodFlow
}
