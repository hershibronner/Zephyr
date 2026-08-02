package app.zephyr.fitness.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        FoodEntity::class,
        FoodLogEntity::class,
        WeightEntity::class,
        DailyStepsEntity::class,
        HourlyStepsEntity::class,
        SessionEntity::class,
        RoutePointEntity::class,
        StrengthWorkoutEntity::class,
        StrengthSetEntity::class,
        ExerciseEntity::class,
        PlannedSlotEntity::class,
        NudgeLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ZephyrDatabase : RoomDatabase() {

    abstract fun foodDao(): FoodDao
    abstract fun weightDao(): WeightDao
    abstract fun stepsDao(): StepsDao
    abstract fun sessionDao(): SessionDao
    abstract fun strengthDao(): StrengthDao
    abstract fun planDao(): PlanDao
    abstract fun nudgeDao(): NudgeDao

    companion object {
        fun build(context: Context): ZephyrDatabase =
            Room.databaseBuilder(context, ZephyrDatabase::class.java, "zephyr.db").build()

        /**
         * A starter exercise list. Compound movements first, because those are what actually change
         * how someone looks in a deficit — the app should not open on a list of cable flyes.
         */
        val STARTER_EXERCISES = listOf(
            ExerciseEntity(name = "Back Squat", muscleGroup = "Legs", isLowerBody = true),
            ExerciseEntity(name = "Deadlift", muscleGroup = "Posterior chain", isLowerBody = true),
            ExerciseEntity(name = "Bench Press", muscleGroup = "Chest", isLowerBody = false),
            ExerciseEntity(name = "Overhead Press", muscleGroup = "Shoulders", isLowerBody = false),
            ExerciseEntity(name = "Barbell Row", muscleGroup = "Back", isLowerBody = false),
            ExerciseEntity(name = "Pull-up", muscleGroup = "Back", isLowerBody = false),
            ExerciseEntity(name = "Chin-up", muscleGroup = "Back", isLowerBody = false),
            ExerciseEntity(name = "Dip", muscleGroup = "Chest", isLowerBody = false),
            ExerciseEntity(name = "Romanian Deadlift", muscleGroup = "Hamstrings", isLowerBody = true),
            ExerciseEntity(name = "Front Squat", muscleGroup = "Legs", isLowerBody = true),
            ExerciseEntity(name = "Lunge", muscleGroup = "Legs", isLowerBody = true),
            ExerciseEntity(name = "Leg Press", muscleGroup = "Legs", isLowerBody = true),
            ExerciseEntity(name = "Lat Pulldown", muscleGroup = "Back", isLowerBody = false),
            ExerciseEntity(name = "Seated Cable Row", muscleGroup = "Back", isLowerBody = false),
            ExerciseEntity(name = "Incline Bench Press", muscleGroup = "Chest", isLowerBody = false),
            ExerciseEntity(name = "Dumbbell Shoulder Press", muscleGroup = "Shoulders", isLowerBody = false),
            ExerciseEntity(name = "Lateral Raise", muscleGroup = "Shoulders", isLowerBody = false),
            ExerciseEntity(name = "Barbell Curl", muscleGroup = "Arms", isLowerBody = false),
            ExerciseEntity(name = "Triceps Pushdown", muscleGroup = "Arms", isLowerBody = false),
            ExerciseEntity(name = "Hip Thrust", muscleGroup = "Glutes", isLowerBody = true),
            ExerciseEntity(name = "Calf Raise", muscleGroup = "Calves", isLowerBody = true),
            ExerciseEntity(name = "Plank", muscleGroup = "Core", isLowerBody = false),
            ExerciseEntity(name = "Hanging Leg Raise", muscleGroup = "Core", isLowerBody = false),
        )
    }
}
