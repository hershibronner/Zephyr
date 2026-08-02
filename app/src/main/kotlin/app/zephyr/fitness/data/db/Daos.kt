package app.zephyr.fitness.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface FoodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFood(food: FoodEntity): Long

    @Update
    suspend fun updateFood(food: FoodEntity)

    @Query("SELECT * FROM foods WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): FoodEntity?

    @Query("SELECT * FROM foods WHERE name LIKE '%' || :query || '%' ORDER BY useCount DESC LIMIT 50")
    suspend fun search(query: String): List<FoodEntity>

    /** Recents are how logging gets fast after the first week — most people eat the same 40 things. */
    @Query("SELECT * FROM foods WHERE lastUsedEpochDay IS NOT NULL ORDER BY lastUsedEpochDay DESC, useCount DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods WHERE isFavourite = 1 ORDER BY useCount DESC")
    fun observeFavourites(): Flow<List<FoodEntity>>

    @Query("UPDATE foods SET useCount = useCount + 1, lastUsedEpochDay = :epochDay WHERE id = :id")
    suspend fun markUsed(id: Long, epochDay: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(entry: FoodLogEntity): Long

    @Delete
    suspend fun deleteLog(entry: FoodLogEntity)

    @Query("SELECT * FROM food_log WHERE date = :date ORDER BY loggedAtEpochMillis")
    fun observeLogForDate(date: LocalDate): Flow<List<FoodLogEntity>>

    @Query("SELECT * FROM food_log WHERE date BETWEEN :from AND :to")
    fun observeLogBetween(from: LocalDate, to: LocalDate): Flow<List<FoodLogEntity>>

    @Query("SELECT COALESCE(SUM(kcal), 0) FROM food_log WHERE date = :date")
    fun observeKcalForDate(date: LocalDate): Flow<Int>

    @Query("SELECT MAX(loggedAtEpochMillis) FROM food_log WHERE date = :date")
    suspend fun lastLoggedAt(date: LocalDate): Long?
}

@Dao
interface WeightDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WeightEntity)

    @Delete
    suspend fun delete(entry: WeightEntity)

    @Query("SELECT * FROM weight_log ORDER BY date")
    fun observeAll(): Flow<List<WeightEntity>>

    @Query("SELECT * FROM weight_log ORDER BY date DESC LIMIT 1")
    fun observeLatest(): Flow<WeightEntity?>

    @Query("SELECT * FROM weight_log ORDER BY date DESC LIMIT 1")
    suspend fun latest(): WeightEntity?
}

@Dao
interface StepsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DailyStepsEntity)

    @Query("SELECT * FROM daily_steps WHERE date = :date")
    suspend fun forDate(date: LocalDate): DailyStepsEntity?

    @Query("SELECT * FROM daily_steps WHERE date = :date")
    fun observeForDate(date: LocalDate): Flow<DailyStepsEntity?>

    @Query("SELECT * FROM daily_steps ORDER BY date DESC LIMIT :days")
    fun observeRecent(days: Int = 30): Flow<List<DailyStepsEntity>>

    @Query("SELECT * FROM daily_steps ORDER BY date DESC LIMIT :days")
    suspend fun recent(days: Int = 30): List<DailyStepsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHour(entry: HourlyStepsEntity)

    @Query("SELECT COALESCE(steps, 0) FROM hourly_steps WHERE date = :date AND hour = :hour")
    suspend fun stepsInHour(date: LocalDate, hour: Int): Int
}

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Insert
    suspend fun insertPoints(points: List<RoutePointEntity>)

    @Query("SELECT * FROM sessions ORDER BY startEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE date = :date")
    fun observeForDate(date: LocalDate): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE date BETWEEN :from AND :to ORDER BY startEpochMillis")
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE date BETWEEN :from AND :to ORDER BY startEpochMillis")
    suspend fun between(from: LocalDate, to: LocalDate): List<SessionEntity>

    @Query("SELECT COALESCE(SUM(netKcal), 0) FROM sessions WHERE date = :date")
    fun observeNetKcalForDate(date: LocalDate): Flow<Int>

    @Query("SELECT * FROM route_points WHERE sessionId = :sessionId ORDER BY timestampMillis")
    suspend fun pointsFor(sessionId: Long): List<RoutePointEntity>

    @Query("SELECT MAX(distanceMetres) FROM sessions WHERE type = :type AND date >= :since")
    suspend fun longestSince(type: String, since: LocalDate): Double?

    @Transaction
    suspend fun insertWithRoute(session: SessionEntity, points: List<RoutePointEntity>): Long {
        val id = insert(session)
        if (points.isNotEmpty()) {
            insertPoints(points.map { it.copy(sessionId = id) })
        }
        return id
    }
}

@Dao
interface StrengthDao {

    @Insert
    suspend fun insertWorkout(workout: StrengthWorkoutEntity): Long

    @Update
    suspend fun updateWorkout(workout: StrengthWorkoutEntity)

    @Insert
    suspend fun insertSet(set: StrengthSetEntity): Long

    @Delete
    suspend fun deleteSet(set: StrengthSetEntity)

    @Query("SELECT * FROM strength_workouts ORDER BY startEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<StrengthWorkoutEntity>>

    @Query("SELECT * FROM strength_sets WHERE workoutId = :workoutId ORDER BY setIndex")
    fun observeSets(workoutId: Long): Flow<List<StrengthSetEntity>>

    /** The previous session's numbers, prefilled so the user never has to remember them. */
    @Query(
        """
        SELECT s.* FROM strength_sets s
        INNER JOIN strength_workouts w ON s.workoutId = w.id
        WHERE s.exerciseName = :exercise AND s.workoutId != :excludingWorkoutId AND s.isWarmup = 0
        ORDER BY w.startEpochMillis DESC, s.setIndex
        LIMIT 10
        """,
    )
    suspend fun lastSetsFor(exercise: String, excludingWorkoutId: Long): List<StrengthSetEntity>

    @Query("SELECT MAX(weightKg) FROM strength_sets WHERE exerciseName = :exercise AND isWarmup = 0")
    suspend fun bestWeight(exercise: String): Double?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExercises(exercises: List<ExerciseEntity>)

    @Query("SELECT * FROM exercises ORDER BY name")
    fun observeExercises(): Flow<List<ExerciseEntity>>
}

@Dao
interface PlanDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(slot: PlannedSlotEntity): Long

    @Delete
    suspend fun delete(slot: PlannedSlotEntity)

    @Query("SELECT * FROM planned_slots ORDER BY dayOfWeek, timeOfDay")
    fun observeAll(): Flow<List<PlannedSlotEntity>>

    @Query("SELECT * FROM planned_slots ORDER BY dayOfWeek, timeOfDay")
    suspend fun all(): List<PlannedSlotEntity>

    @Query("SELECT COUNT(*) FROM planned_slots")
    suspend fun count(): Int
}

@Dao
interface NudgeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(entry: NudgeLogEntity)

    @Query("SELECT key FROM nudge_log WHERE date >= :since")
    suspend fun keysSince(since: LocalDate): List<String>

    @Query("SELECT COUNT(*) FROM nudge_log WHERE date = :date AND isCelebration = 0")
    suspend fun demandCountOn(date: LocalDate): Int

    @Query("DELETE FROM nudge_log WHERE date < :before")
    suspend fun prune(before: LocalDate)
}
