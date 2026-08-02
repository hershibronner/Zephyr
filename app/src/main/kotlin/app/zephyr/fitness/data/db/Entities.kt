package app.zephyr.fitness.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.time.LocalDate
import java.time.LocalTime

enum class MealSlot(val label: String) {
    BREAKFAST("Breakfast"),
    LUNCH("Lunch"),
    DINNER("Dinner"),
    SNACK("Snack"),
}

/**
 * A food the user can log. Covers three origins: entries they typed themselves, products cached
 * from Open Food Facts after a search or barcode scan, and quick-added raw calories.
 *
 * Nutrition is stored per 100 g/ml, the form Open Food Facts publishes, with an optional serving
 * size so "1 serving" stays meaningful.
 */
@Entity(tableName = "foods", indices = [Index(value = ["barcode"], unique = true), Index("name")])
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val kcalPer100: Double,
    val proteinPer100: Double = 0.0,
    val carbsPer100: Double = 0.0,
    val fatPer100: Double = 0.0,
    val servingGrams: Double? = null,
    val servingLabel: String? = null,
    val isCustom: Boolean = true,
    val isFavourite: Boolean = false,
    val lastUsedEpochDay: Long? = null,
    val useCount: Int = 0,
)

/**
 * One logged item on one day.
 *
 * The nutrition figures are a *snapshot* rather than a lookup through [foodId]. Correcting a food's
 * calories next month must not silently rewrite last month's history — a diary whose past changes
 * underneath you is worse than no diary, because every trend computed from it becomes untrustworthy.
 */
@Entity(tableName = "food_log", indices = [Index("date")])
data class FoodLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val slot: MealSlot,
    val foodId: Long? = null,
    val name: String,
    val quantityGrams: Double?,
    val servings: Double = 1.0,
    val kcal: Int,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val loggedAtEpochMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "weight_log")
data class WeightEntity(
    @PrimaryKey val date: LocalDate,
    val weightKg: Double,
    val loggedAtEpochMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "daily_steps")
data class DailyStepsEntity(
    @PrimaryKey val date: LocalDate,
    val steps: Int,
    /**
     * The hardware counter is cumulative since boot, so a delta needs a per-boot baseline. Storing
     * it here lets a reboot be detected (counter lower than baseline) and handled without losing
     * the day's total.
     */
    val sensorBaseline: Float? = null,
    val lastSensorValue: Float? = null,
)

/** Rolling per-hour counts, so the coach can tell "sat still for an hour" from "walked at lunch". */
@Entity(tableName = "hourly_steps", primaryKeys = ["date", "hour"])
data class HourlyStepsEntity(
    val date: LocalDate,
    val hour: Int,
    val steps: Int,
)

@Entity(tableName = "sessions", indices = [Index("date")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val type: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val durationSeconds: Long,
    val distanceMetres: Double,
    val elevationGainMetres: Double,
    val elevationLossMetres: Double,
    val kcal: Int,
    val netKcal: Int,
    val isManual: Boolean = false,
    val note: String? = null,
)

@Entity(tableName = "route_points", indices = [Index("sessionId")])
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMetres: Double?,
    val timestampMillis: Long,
    val accuracyMetres: Float,
)

@Entity(tableName = "strength_workouts", indices = [Index("date")])
data class StrengthWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val startEpochMillis: Long,
    val endEpochMillis: Long? = null,
    val kcal: Int = 0,
    val note: String? = null,
)

@Entity(tableName = "strength_sets", indices = [Index("workoutId"), Index("exerciseName")])
data class StrengthSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseName: String,
    val setIndex: Int,
    val reps: Int,
    val weightKg: Double,
    val isWarmup: Boolean = false,
)

@Entity(tableName = "exercises", indices = [Index(value = ["name"], unique = true)])
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val muscleGroup: String,
    val isLowerBody: Boolean,
    val isCustom: Boolean = false,
)

/** The user's weekly skeleton — which days they've committed to, and to what. */
@Entity(tableName = "planned_slots")
data class PlannedSlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayOfWeek: Int,
    val type: String,
    val timeOfDay: LocalTime,
    val enabled: Boolean = true,
)

/**
 * A record of every notification sent. Powers deduplication and the daily cap — without a durable
 * log, a process restart would reset the budget and the user would be notified twice about the
 * same thing, which is exactly how notification permission gets revoked.
 */
@Entity(tableName = "nudge_log", indices = [Index("date")])
data class NudgeLogEntity(
    @PrimaryKey val key: String,
    val date: LocalDate,
    val category: String,
    val isCelebration: Boolean,
    val sentAtEpochMillis: Long,
)

class Converters {
    @TypeConverter fun dateToEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter fun epochDayToDate(day: Long?): LocalDate? = day?.let(LocalDate::ofEpochDay)

    @TypeConverter fun timeToSecondOfDay(time: LocalTime?): Int? = time?.toSecondOfDay()

    @TypeConverter fun secondOfDayToTime(second: Int?): LocalTime? = second?.let(LocalTime::ofSecondOfDay)

    @TypeConverter fun slotToName(slot: MealSlot?): String? = slot?.name

    @TypeConverter fun nameToSlot(name: String?): MealSlot? = name?.let(MealSlot::valueOf)
}
