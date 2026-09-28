package app.hfd.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.hfd.Graph
import app.hfd.MainActivity
import app.hfd.R
import app.hfd.core.progress.Stats
import app.hfd.core.reminders.ReminderKind
import app.hfd.core.reminders.ReminderPlanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The optional review reminder, scheduled with WorkManager: one work per upcoming occurrence (the
 * next 36 hours), re-planned every 12 hours, at app start and whenever the settings change.
 * WorkManager may delay a reminder a little while the phone sleeps (Doze); that's accepted for
 * a reminder.
 */
object Reminders {
    const val CHANNEL = "reminders"
    private const val TAG = "hfd-reminder"
    private const val PLANNER = "hfd-reminder-planner"
    private const val KEY = "hfd-reminder-key:"
    private const val AT = "hfd-reminder-at:"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = context.getString(R.string.channel_reminders_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Brings the scheduled reminders in line with the settings: plans the next occurrences and
     * cancels planned ones that are no longer wanted. A reminder whose time has come is left
     * alone, since it may be the one WorkManager just started the app for (the worker checks
     * the setting itself).
     */
    suspend fun reschedule(context: Context): Unit = withContext(Dispatchers.IO) {
        val wm = WorkManager.getInstance(context)
        val s = Graph.settings.current
        val config = s.reminders
        val any = config.reviews
        val now = ZonedDateTime.now()
        // (Reminders of the kinds removed in 1.6.0 are absent from the plan: cancelled here.)
        val plan = if (any) ReminderPlanner.upcoming(config, now) else emptyList()
        val keys = plan.mapTo(HashSet()) { it.key }
        val nowMs = now.toInstant().toEpochMilli()
        for (info in wm.getWorkInfosByTag(TAG).get()) {
            if (info.state != WorkInfo.State.ENQUEUED) continue
            val key = info.tags.firstOrNull { it.startsWith(KEY) }?.removePrefix(KEY)
            val at = info.tags.firstOrNull { it.startsWith(AT) }?.removePrefix(AT)?.toLongOrNull()
            if (key !in keys && (at == null || at > nowMs)) wm.cancelWorkById(info.id)
        }
        for (r in plan) {
            val atMs = r.at.toInstant().toEpochMilli()
            val work = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(atMs - nowMs, TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .addTag(KEY + r.key)
                .addTag(AT + atMs)
                .setInputData(Data.Builder().putString(ReminderWorker.KIND, r.kind.name).build())
                .build()
            wm.enqueueUniqueWork(r.key, ExistingWorkPolicy.KEEP, work)
        }
        if (any) {
            wm.enqueueUniquePeriodicWork(
                PLANNER,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PlannerWorker>(12, TimeUnit.HOURS).build(),
            )
        } else {
            wm.cancelUniqueWork(PLANNER)
        }
    }

    fun notify(context: Context, dueCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val title = context.resources.getQuantityString(R.plurals.home_due_count, dueCount, dueCount)
        val text = context.getString(R.string.reminder_reviews_text)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction(MainActivity.ACTION_REVIEW)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hfd)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, ReminderKind.DUE_REVIEWS.ordinal, open, flags))
            .build()
        NotificationManagerCompat.from(context).notify(1000 + ReminderKind.DUE_REVIEWS.ordinal, notification)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Unknown kinds (removed reminders still planned by an older version) do nothing.
        runCatching { ReminderKind.valueOf(inputData.getString(KIND)!!) }.getOrNull() ?: return Result.success()
        // The reminder may have been switched off since it was planned.
        if (!Graph.settings.current.reminders.reviews) return Result.success()
        withTimeoutOrNull(10_000) { Graph.progress.loaded.first { it } }
        val due = Stats.due(Graph.progress.state.value, System.currentTimeMillis(), Graph.progress.zone).size
        if (due > 0) Reminders.notify(applicationContext, due)
        return Result.success()
    }

    companion object {
        const val KIND = "kind"
    }
}

/** Keeps the next 36 hours of reminders planned. */
class PlannerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Reminders.reschedule(applicationContext)
        return Result.success()
    }
}
