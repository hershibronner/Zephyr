package app.zephyr.fitness

import android.app.Application
import android.content.Context
import app.zephyr.fitness.coach.CoachWorker
import app.zephyr.fitness.coach.Notifier
import app.zephyr.fitness.data.db.ZephyrDatabase
import app.zephyr.fitness.data.prefs.SettingsStore
import app.zephyr.fitness.data.steps.StepRepository
import app.zephyr.fitness.domain.TodayRepository
import app.zephyr.fitness.tracking.TrackingRepository
import app.zephyr.fitness.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Hand-rolled dependency container.
 *
 * There is no Hilt here on purpose. The graph is a dozen singletons that never change at runtime,
 * which a constructor call expresses perfectly well, and every annotation processor added to an
 * Android build is another thing that can break a release for reasons unrelated to the app.
 */
class AppContainer(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: ZephyrDatabase by lazy { ZephyrDatabase.build(context) }
    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
    val notifier: Notifier by lazy { Notifier(context) }
    val stepRepository: StepRepository by lazy { StepRepository(context, database.stepsDao()) }
    val todayRepository: TodayRepository by lazy { TodayRepository(database, settingsStore) }
    val updateManager: UpdateManager by lazy { UpdateManager(context) }

    /**
     * Holds the session in progress. A process singleton, because the recording has to outlive the
     * Activity that started it — a rotation or a backgrounded app must not lose a run.
     */
    val trackingRepository: TrackingRepository by lazy { TrackingRepository() }

    fun isTrackingSession(): Boolean = trackingRepository.state.value.isActive

    /**
     * When the training plan began, used to work out which week of progression the user is in.
     * Falls back to today so a fresh install starts at week zero rather than a negative index.
     */
    private var cachedPlanStart: LocalDate? = null

    suspend fun planStartDate(): LocalDate {
        cachedPlanStart?.let { return it }
        val earliest = database.sessionDao().between(LocalDate.now().minusDays(365), LocalDate.now())
            .minByOrNull { it.date }
            ?.date
        return (earliest ?: LocalDate.now()).also { cachedPlanStart = it }
    }

    fun onCreate() {
        notifier.ensureChannels()
        scope.launch {
            database.strengthDao().insertExercises(ZephyrDatabase.STARTER_EXERCISES)
        }
    }
}

class ZephyrApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.onCreate()
        CoachWorker.schedule(this)
    }
}
