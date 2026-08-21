package dev.zephyr.core.food

import kotlin.math.roundToInt

/**
 * One food the on-device labeller can recognise, with a typical serving.
 *
 * Per-100g figures are conventional reference values; the serving size is the honest weak point,
 * since a photo label says "pizza" and nothing about how much of it is on the plate.
 */
data class FoodReference(
    /** Lowercase ML Kit label this matches. */
    val label: String,
    val displayName: String,
    val typicalServingGrams: Double,
    val servingDescription: String,
    val kcalPer100: Double,
    val proteinPer100: Double = 0.0,
    val carbsPer100: Double = 0.0,
    val fatPer100: Double = 0.0,
)

data class GuessedItem(
    val name: String,
    val servingDescription: String,
    val grams: Double,
    val kcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
)

/**
 * The result of an on-device guess.
 *
 * [recognised] is false when nothing in the table matched, which is a common and expected outcome —
 * anything mixed, homemade, or outside a few hundred common foods will not be identified. That is
 * reported as "I don't know" rather than as a number, because a fabricated calorie count silently
 * corrupts the ledger the whole app depends on.
 */
data class OnDeviceGuess(
    val items: List<GuessedItem> = emptyList(),
    val recognised: Boolean = false,
    val note: String = "",
) {
    val totalKcal: Int get() = items.sumOf { it.kcal }
}

/**
 * Turns image labels into a calorie guess, with no network and no API key.
 *
 * This is deliberately the weaker of the two photo paths and is labelled as such everywhere it
 * surfaces. An image labeller reports *what* is in the picture, never *how much* — three slices of
 * pizza and one slice produce the identical label at the identical confidence. So every result here
 * is one typical serving, and the user is told plainly to correct the amount.
 */
object FoodGuesser {

    /** Labels below this are noise; the labeller emits a long tail of weak guesses. */
    const val MIN_CONFIDENCE = 0.55f

    /** More than this on one plate and the labeller is guessing at textures, not dishes. */
    private const val MAX_ITEMS = 3

    /**
     * Labels too generic to cost calories. "Food" on its own means the labeller saw a meal and
     * could not say what it was — reporting a number for that would be invention.
     */
    private val TOO_GENERIC = setOf(
        "food", "dish", "cuisine", "recipe", "meal", "ingredient", "produce",
        "tableware", "plate", "bowl", "drink", "drinkware", "table", "kitchen",
    )

    fun fromLabels(labels: List<Pair<String, Float>>): OnDeviceGuess {
        val confident = labels
            .filter { it.second >= MIN_CONFIDENCE }
            .sortedByDescending { it.second }

        if (confident.isEmpty()) {
            return OnDeviceGuess(
                note = "Couldn't tell what this is. Add it by hand — it only takes a moment.",
            )
        }

        val matched = confident
            .filterNot { it.first.lowercase().trim() in TOO_GENERIC }
            .mapNotNull { (label, _) -> OnDeviceFoodTable.match(label) }
            .distinctBy { it.displayName }
            .take(MAX_ITEMS)

        if (matched.isEmpty()) {
            val sawFood = confident.any { it.first.lowercase() in TOO_GENERIC }
            return OnDeviceGuess(
                note = if (sawFood) {
                    "That's food, but not something I recognise offline. Enter it by hand, or add " +
                        "an API key for proper photo estimates."
                } else {
                    "Couldn't tell what this is. Add it by hand — it only takes a moment."
                },
            )
        }

        val items = matched.map { reference ->
            val factor = reference.typicalServingGrams / 100.0
            GuessedItem(
                name = reference.displayName,
                servingDescription = reference.servingDescription,
                grams = reference.typicalServingGrams,
                kcal = (reference.kcalPer100 * factor).roundToInt(),
                proteinG = reference.proteinPer100 * factor,
                carbsG = reference.carbsPer100 * factor,
                fatG = reference.fatPer100 * factor,
            )
        }

        return OnDeviceGuess(
            items = items,
            recognised = true,
            // Stated every single time, because this is the limitation that matters and the user
            // has no way to know it from looking at the number.
            note = "Offline guess — one typical serving each. It can't see how much is on the " +
                "plate, so check the amount before logging.",
        )
    }
}

/**
 * A small reference table of foods the device labeller can name.
 *
 * Deliberately short. A larger table would not help: the constraint is the labeller's vocabulary,
 * not the nutrition data, and padding it with foods that will never be recognised would only make
 * the feature look more capable than it is.
 */
object OnDeviceFoodTable {

    val entries: List<FoodReference> = listOf(
        FoodReference("pizza", "Pizza", 125.0, "1 slice", 266.0, 11.0, 33.0, 10.0),
        FoodReference("hamburger", "Burger", 220.0, "1 burger", 254.0, 13.0, 21.0, 13.0),
        FoodReference("cheeseburger", "Cheeseburger", 230.0, "1 burger", 280.0, 15.0, 21.0, 15.0),
        FoodReference("hot dog", "Hot dog", 100.0, "1 hot dog", 290.0, 10.0, 22.0, 18.0),
        FoodReference("french fries", "Fries", 115.0, "1 medium portion", 312.0, 3.4, 41.0, 15.0),
        FoodReference("fried chicken", "Fried chicken", 140.0, "1 piece", 246.0, 24.0, 9.0, 12.0),
        FoodReference("sandwich", "Sandwich", 180.0, "1 sandwich", 250.0, 12.0, 28.0, 9.0),
        FoodReference("taco", "Taco", 100.0, "1 taco", 226.0, 9.0, 20.0, 12.0),
        FoodReference("burrito", "Burrito", 300.0, "1 burrito", 206.0, 9.0, 25.0, 8.0),
        FoodReference("nachos", "Nachos", 150.0, "1 portion", 306.0, 8.0, 32.0, 16.0),
        FoodReference("sushi", "Sushi", 200.0, "6-8 pieces", 145.0, 6.0, 27.0, 1.5),
        FoodReference("noodle", "Noodles", 250.0, "1 bowl", 138.0, 5.0, 25.0, 2.0),
        FoodReference("pasta", "Pasta", 250.0, "1 plate", 158.0, 6.0, 31.0, 1.0),
        FoodReference("spaghetti", "Spaghetti", 250.0, "1 plate", 158.0, 6.0, 31.0, 1.0),
        FoodReference("lasagna", "Lasagne", 280.0, "1 portion", 135.0, 8.0, 12.0, 6.0),
        FoodReference("rice", "Rice", 180.0, "1 cup cooked", 130.0, 2.7, 28.0, 0.3),
        FoodReference("fried rice", "Fried rice", 200.0, "1 portion", 163.0, 4.0, 24.0, 5.5),
        FoodReference("curry", "Curry", 300.0, "1 portion", 110.0, 7.0, 8.0, 6.0),
        FoodReference("soup", "Soup", 300.0, "1 bowl", 55.0, 3.0, 6.0, 2.0),
        FoodReference("salad", "Salad", 200.0, "1 bowl", 55.0, 2.5, 6.0, 2.5),
        FoodReference("steak", "Steak", 220.0, "1 steak", 250.0, 26.0, 0.0, 16.0),
        FoodReference("meat", "Meat", 180.0, "1 portion", 230.0, 25.0, 0.0, 14.0),
        FoodReference("chicken", "Chicken", 170.0, "1 breast", 165.0, 31.0, 0.0, 3.6),
        FoodReference("seafood", "Seafood", 170.0, "1 portion", 130.0, 22.0, 1.0, 4.0),
        FoodReference("fish", "Fish", 170.0, "1 fillet", 140.0, 22.0, 0.0, 5.0),
        FoodReference("shrimp", "Shrimp", 120.0, "1 portion", 99.0, 24.0, 0.2, 0.3),
        FoodReference("sausage", "Sausage", 100.0, "2 sausages", 300.0, 12.0, 2.0, 27.0),
        FoodReference("bacon", "Bacon", 60.0, "3 rashers", 541.0, 37.0, 1.4, 42.0),
        FoodReference("egg", "Eggs", 100.0, "2 eggs", 155.0, 13.0, 1.1, 11.0),
        FoodReference("omelette", "Omelette", 170.0, "1 omelette", 154.0, 11.0, 1.0, 12.0),
        FoodReference("bread", "Bread", 60.0, "2 slices", 265.0, 9.0, 49.0, 3.2),
        FoodReference("toast", "Toast", 60.0, "2 slices", 290.0, 9.5, 52.0, 3.5),
        FoodReference("bagel", "Bagel", 100.0, "1 bagel", 250.0, 10.0, 49.0, 1.5),
        FoodReference("croissant", "Croissant", 60.0, "1 croissant", 406.0, 8.0, 46.0, 21.0),
        FoodReference("pancake", "Pancakes", 150.0, "3 pancakes", 227.0, 6.0, 28.0, 10.0),
        FoodReference("waffle", "Waffle", 100.0, "1 waffle", 291.0, 8.0, 33.0, 14.0),
        FoodReference("cereal", "Cereal", 40.0, "1 bowl", 379.0, 8.0, 84.0, 2.0),
        FoodReference("oatmeal", "Porridge", 250.0, "1 bowl", 71.0, 2.5, 12.0, 1.5),
        FoodReference("cheese", "Cheese", 40.0, "1 portion", 402.0, 25.0, 1.3, 33.0),
        FoodReference("yogurt", "Yoghurt", 170.0, "1 pot", 59.0, 10.0, 3.6, 0.4),
        FoodReference("milk", "Milk", 250.0, "1 glass", 61.0, 3.2, 4.8, 3.3),
        FoodReference("fruit", "Fruit", 150.0, "1 portion", 60.0, 0.8, 14.0, 0.2),
        FoodReference("apple", "Apple", 180.0, "1 apple", 52.0, 0.3, 14.0, 0.2),
        FoodReference("banana", "Banana", 120.0, "1 banana", 89.0, 1.1, 23.0, 0.3),
        FoodReference("orange", "Orange", 130.0, "1 orange", 47.0, 0.9, 12.0, 0.1),
        FoodReference("strawberry", "Strawberries", 150.0, "1 bowl", 32.0, 0.7, 7.7, 0.3),
        FoodReference("grape", "Grapes", 150.0, "1 bunch", 69.0, 0.7, 18.0, 0.2),
        FoodReference("watermelon", "Watermelon", 280.0, "1 wedge", 30.0, 0.6, 7.6, 0.2),
        FoodReference("vegetable", "Vegetables", 200.0, "1 portion", 45.0, 2.5, 8.0, 0.3),
        FoodReference("broccoli", "Broccoli", 150.0, "1 portion", 34.0, 2.8, 7.0, 0.4),
        FoodReference("carrot", "Carrots", 120.0, "1 portion", 41.0, 0.9, 10.0, 0.2),
        FoodReference("tomato", "Tomatoes", 150.0, "1 portion", 18.0, 0.9, 3.9, 0.2),
        FoodReference("potato", "Potato", 200.0, "1 potato", 93.0, 2.5, 21.0, 0.1),
        FoodReference("corn", "Corn", 150.0, "1 cob", 96.0, 3.4, 21.0, 1.5),
        FoodReference("avocado", "Avocado", 100.0, "half an avocado", 160.0, 2.0, 9.0, 15.0),
        FoodReference("tofu", "Tofu", 150.0, "1 portion", 76.0, 8.0, 1.9, 4.8),
        FoodReference("nut", "Nuts", 30.0, "a small handful", 607.0, 20.0, 21.0, 54.0),
        FoodReference("popcorn", "Popcorn", 30.0, "1 bowl", 387.0, 12.0, 78.0, 4.5),
        FoodReference("pretzel", "Pretzels", 30.0, "1 handful", 380.0, 10.0, 80.0, 3.0),
        FoodReference("cake", "Cake", 100.0, "1 slice", 350.0, 5.0, 50.0, 15.0),
        FoodReference("cookie", "Cookies", 40.0, "2 cookies", 480.0, 5.5, 64.0, 22.0),
        FoodReference("doughnut", "Doughnut", 70.0, "1 doughnut", 425.0, 5.0, 51.0, 22.0),
        FoodReference("muffin", "Muffin", 110.0, "1 muffin", 377.0, 6.0, 55.0, 15.0),
        FoodReference("pie", "Pie", 125.0, "1 slice", 265.0, 3.0, 34.0, 13.0),
        FoodReference("ice cream", "Ice cream", 100.0, "2 scoops", 207.0, 3.5, 24.0, 11.0),
        FoodReference("chocolate", "Chocolate", 40.0, "a few squares", 546.0, 5.0, 61.0, 31.0),
        FoodReference("candy", "Sweets", 40.0, "1 handful", 394.0, 0.0, 98.0, 0.2),
        FoodReference("dessert", "Dessert", 120.0, "1 portion", 300.0, 4.0, 40.0, 14.0),
        FoodReference("coffee", "Coffee", 240.0, "1 cup", 2.0, 0.1, 0.0, 0.0),
        FoodReference("juice", "Juice", 250.0, "1 glass", 45.0, 0.5, 10.0, 0.1),
        FoodReference("beer", "Beer", 350.0, "1 bottle", 43.0, 0.5, 3.6, 0.0),
        FoodReference("wine", "Wine", 150.0, "1 glass", 83.0, 0.1, 2.6, 0.0),
        FoodReference("cocktail", "Cocktail", 200.0, "1 drink", 110.0, 0.0, 12.0, 0.0),
        FoodReference("fast food", "Fast food", 250.0, "1 portion", 280.0, 12.0, 30.0, 13.0),
    )

    private val byLabel: Map<String, FoodReference> = entries.associateBy { it.label }

    /**
     * Exact label first, then a whole-word match so compounds like "grilled chicken" resolve.
     *
     * Matching is one-directional on purpose. Allowing a table entry to match because it *contains*
     * the detected label let the generic label "food" resolve to "fast food" and report a confident
     * 700 kcal for a photo the labeller could not identify at all.
     */
    fun match(label: String): FoodReference? {
        val clean = label.lowercase().trim()
        byLabel[clean]?.let { return it }
        return entries.firstOrNull { entry ->
            Regex("\\b${Regex.escape(entry.label)}\\b").containsMatchIn(clean)
        }
    }
}
