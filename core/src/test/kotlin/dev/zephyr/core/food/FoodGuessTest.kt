package dev.zephyr.core.food

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FoodGuessTest {

    private fun guess(vararg labels: Pair<String, Float>) = FoodGuesser.fromLabels(labels.toList())

    @Test
    fun `a recognised food produces one typical serving`() {
        val result = guess("Pizza" to 0.9f)

        assertTrue(result.recognised)
        assertEquals(1, result.items.size)
        assertEquals("Pizza", result.items.first().name)
        assertEquals("1 slice", result.items.first().servingDescription)
    }

    @Test
    fun `calories are scaled to the serving, not reported per 100g`() {
        val pizza = assertNotNull(OnDeviceFoodTable.match("pizza"))
        val item = guess("Pizza" to 0.9f).items.first()

        val expected = (pizza.kcalPer100 * pizza.typicalServingGrams / 100.0).toInt()
        assertTrue(
            kotlin.math.abs(item.kcal - expected) <= 1,
            "expected ~$expected kcal for ${pizza.typicalServingGrams}g, got ${item.kcal}",
        )
    }

    @Test
    fun `low confidence labels are ignored`() {
        val result = guess("Pizza" to 0.2f, "Cake" to 0.1f)

        assertTrue(!result.recognised)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `a generic food label admits it does not know rather than inventing a number`() {
        val result = guess("Food" to 0.95f, "Tableware" to 0.8f)

        assertTrue(!result.recognised)
        assertTrue(result.items.isEmpty())
        assertTrue("recognise" in result.note.lowercase() || "hand" in result.note.lowercase())
    }

    @Test
    fun `an unrecognised subject says so plainly`() {
        val result = guess("Bicycle" to 0.9f, "Wheel" to 0.85f)

        assertTrue(!result.recognised)
        assertTrue(result.items.isEmpty())
        assertTrue(result.note.isNotBlank())
    }

    @Test
    fun `nothing at all still returns a usable message`() {
        val result = FoodGuesser.fromLabels(emptyList())

        assertTrue(!result.recognised)
        assertTrue(result.note.isNotBlank())
    }

    @Test
    fun `several dishes on one plate are all reported`() {
        val result = guess("Rice" to 0.9f, "Chicken" to 0.85f, "Broccoli" to 0.7f)

        assertTrue(result.recognised)
        assertEquals(3, result.items.size)
        assertEquals(result.items.sumOf { it.kcal }, result.totalKcal)
    }

    @Test
    fun `the plate is capped so a texture-guessing labeller cannot invent a banquet`() {
        val result = guess(
            "Rice" to 0.9f, "Chicken" to 0.9f, "Broccoli" to 0.9f,
            "Carrot" to 0.9f, "Tomato" to 0.9f, "Cheese" to 0.9f,
        )

        assertTrue(result.items.size <= 3, "reported ${result.items.size} items")
    }

    @Test
    fun `duplicate labels do not double-count the same food`() {
        val result = guess("Pizza" to 0.9f, "pizza" to 0.88f, "PIZZA" to 0.8f)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `the strongest labels win when the plate is capped`() {
        val result = guess("Steak" to 0.95f, "Rice" to 0.9f, "Salad" to 0.85f, "Cake" to 0.6f)

        assertTrue(result.items.none { it.name == "Cake" }, "the weakest label displaced a stronger one")
    }

    @Test
    fun `a compound label still matches its food`() {
        assertNotNull(OnDeviceFoodTable.match("grilled chicken"))
        assertNotNull(OnDeviceFoodTable.match("Fried Rice"))
    }

    @Test
    fun `every recognised result warns that portion size is invisible`() {
        val result = guess("Pizza" to 0.9f)

        val note = result.note.lowercase()
        assertTrue(
            "how much" in note || "amount" in note || "serving" in note,
            "the portion caveat is missing: ${result.note}",
        )
    }

    @Test
    fun `every table entry is internally consistent`() {
        OnDeviceFoodTable.entries.forEach { food ->
            assertTrue(food.kcalPer100 >= 0, "${food.displayName} has negative calories")
            assertTrue(food.typicalServingGrams > 0, "${food.displayName} has no serving size")
            assertTrue(food.label == food.label.lowercase(), "${food.label} is not lowercase")
            assertTrue(food.displayName.isNotBlank())
            assertTrue(food.servingDescription.isNotBlank())
        }
    }

    @Test
    fun `macros never claim more energy than the food contains`() {
        OnDeviceFoodTable.entries.forEach { food ->
            // 4 kcal per gram of protein and carbohydrate, 9 per gram of fat.
            val fromMacros = food.proteinPer100 * 4 + food.carbsPer100 * 4 + food.fatPer100 * 9
            assertTrue(
                fromMacros <= food.kcalPer100 * 1.35 + 25,
                "${food.displayName}: macros imply ${fromMacros.toInt()} kcal but it lists ${food.kcalPer100.toInt()}",
            )
        }
    }

    @Test
    fun `labels are unique so a match is never ambiguous`() {
        val labels = OnDeviceFoodTable.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size, "duplicate labels in the table")
    }

    @Test
    fun `no result claims a confidence it has not earned`() {
        val result = guess("Pizza" to 0.9f)
        val note = result.note.lowercase()
        listOf("exact", "precise", "accurate", "certain").forEach {
            assertTrue(it !in note, "the note oversells the estimate: ${result.note}")
        }
    }
}
