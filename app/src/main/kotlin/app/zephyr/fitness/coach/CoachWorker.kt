package app.zephyr.fitness.coach

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.zephyr.fitness.ZephyrApplication
import app.zephyr.fitness.data.db.NudgeLogEntity
import dev.zephyr.core.activity.ActivityType
import dev.zephyr.core.nudge.CoachState
import dev.zephyr.core.nudge.NudgeEngine
import dev.zephyr.core.plan.AdherenceCalculator
import dev.zephyr.core.plan.CompletedSession
import dev.zephyr.core.plan.FitnessBaseline
import dev.zephyr.core.plan.PlannedSlot
import dev.zephyr.core.plan.ProgressionEngine
import dev.zephyr.core.plan.WeeklyTemplate
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Wakes every fifteen minutes, asks the coach whether it has anything worth saying, and delivers
 * whatever comes back.
 *
 * The worker owns no judgement of its own — every decision about what to send and whether now is
 * an acceptable moment belongs to [NudgeEngine], which is a pure function and therefore actually
 * testable. This class only gathers state, posts, and records what it posted so the same nudge is
 * never sent twice even across process death.
 */
class CoachWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ZephyrApplication ?: return Result.success()
        val container = app.container

        return runCatching { evaluate(container) }
            .fold(
                onSuccess = { Result.success() },
                // A failed pass is not worth retrying: the next tick is fifteen minutes away and
                // the state it would act on will have moved on anyway.
                onFailure = { Result.success() },
            )
    }

    private suspend fun evaluate(container: app.zephyr.fitness.AppContainer) {
        val today = LocalDate.now()
        val now = LocalDateTime.now()

        container.stepRepository.sync(today, now.toLocalTime())

        val state = container.todayRepository.observe(today).first()
        if (!state.ready) return

        val prescriptions = todaysPrescriptions(container, today)
        val missed = missedYesterday(container, today)

        val lastFoodAt = container.database.foodDao().lastLoggedAt(today)?.let {
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault())
        }
        val lastWeighIn = container.database.weightDao().latest()?.date
        val sentKeys = container.database.nudgeDao().keysSince(today.minusDays(1)).toSet()
        val sentToday = container.database.nudgeDao().demandCountOn(today)

        val coachState = CoachState(
            now = now,
            balance = state.balance,
            stepStatus = state.stepStatus,
            stepsInLastHour = container.stepRepository.stepsInLastHour(now.toLocalTime(), today),
            streakDays = state.streak.current,
            streakSecuredToday = !state.streak.atRisk && state.streak.current > 0,
            todaysPrescriptions = prescriptions,
            completedSessionToday = state.sessionsToday > 0,
            missedYesterday = missed,
            lastFoodLogAt = lastFoodAt,
            daysSinceWeighIn = lastWeighIn?.let { ChronoUnit.DAYS.between(it, today).toInt() },
            alreadySentKeys = sentKeys,
            sentTodayCount = sentToday,
            isTrackingSession = container.isTrackingSession(),
        )

        val nudges = NudgeEngine.evaluate(coachState, state.settings.nudges)
        for (nudge in nudges) {
            container.notifier.post(nudge)
            container.database.nudgeDao().record(
                NudgeLogEntity(
                    key = nudge.key,
                    date = today,
                    category = nudge.category.name,
                    isCelebration = nudge.isCelebration,
                    sentAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }

        container.database.nudgeDao().prune(today.minusDays(30))
    }

    private suspend fun todaysPrescriptions(
        container: app.zephyr.fitness.AppContainer,
        today: LocalDate,
    ) = prescriptionsForWeekOf(container, today).filter { it.date == today }

    private suspend fun missedYesterday(
        container: app.zephyr.fitness.AppContainer,
        today: LocalDate,
    ): List<dev.zephyr.core.plan.Prescription> {
        val yesterday = today.minusDays(1)
        val prescriptions = prescriptionsForWeekOf(container, yesterday)
        val completed = container.database.sessionDao()
            .between(yesterday.minusDays(1), today)
            .mapNotNull { session ->
                runCatching { ActivityType.valueOf(session.type) }.getOrNull()
                    ?.let { CompletedSession(session.date, it) }
            }

        return AdherenceCalculator.evaluate(prescriptions, completed, upTo = yesterday)
            .missed
            .filter { it.date == yesterday }
    }

    private suspend fun prescriptionsForWeekOf(
        container: app.zephyr.fitness.AppContainer,
        date: LocalDate,
    ): List<dev.zephyr.core.plan.Prescription> {
        val slots = container.database.planDao().all().map { entity ->
            PlannedSlot(
                id = entity.id,
                dayOfWeek = DayOfWeek.of(entity.dayOfWeek),
                type = runCatching { ActivityType.valueOf(entity.type) }.getOrDefault(ActivityType.OTHER),
                timeOfDay = entity.timeOfDay,
                enabled = entity.enabled,
            )
        }
        if (slots.isEmpty()) return emptyList()

        val weekStart = date.with(DayOfWeek.MONDAY)
        val planStart = container.planStartDate()
        val weekIndex = ChronoUnit.WEEKS.between(planStart.with(DayOfWeek.MONDAY), weekStart).toInt()

        val baseline = FitnessBaseline(
            longestRunMetres = container.database.sessionDao()
                .longestSince(ActivityType.RUN.name, date.minusDays(30)),
            longestHikeMinutes = null,
        )

        return ProgressionEngine.prescriptionsForWeek(
            template = WeeklyTemplate(slots),
            weekStart = weekStart,
            weekIndex = weekIndex.coerceAtLeast(0),
            baseline = baseline,
        )
    }

    companion object {
        private const val WORK_NAME = "zephyr_coach"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CoachWorker>(Duration.ofMinutes(15)).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
