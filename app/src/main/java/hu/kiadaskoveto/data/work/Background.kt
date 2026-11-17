package hu.kiadaskoveto.data.work

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
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import hu.kiadaskoveto.KiadasApp
import hu.kiadaskoveto.MainActivity
import hu.kiadaskoveto.R
import hu.kiadaskoveto.data.repo.BudgetAlert
import java.util.concurrent.TimeUnit

/** Local notifications only — budget figures are never sent anywhere. */
class Notifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_BUDGET, context.getString(R.string.channel_budget_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_budget_desc)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_BACKUP, context.getString(R.string.channel_backup_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_backup_desc)
        })
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun budgetAlerts(alerts: List<BudgetAlert>) {
        if (!canNotify()) return
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "budgets"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alerts.forEach { a ->
            val p = a.progress
            val title = if (a.threshold >= 100) "Túllépted a keretet: ${p.title}" else "${p.title}: a keret ${a.threshold}%-a elfogyott"
            val text = "Elköltve ${p.spent.format()} / ${p.limit.format()} (${p.periodLabel}). " +
                if (p.remaining.isNegative) "Túllépés: ${(-p.remaining).format()}." else "Hátralévő: ${p.remaining.format()}."
            val n = NotificationCompat.Builder(context, CHANNEL_BUDGET)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(NOTIF_BUDGET_BASE + p.budget.id.toInt(), n) }
        }
    }

    fun backupResult(message: String, success: Boolean) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(context, CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (success) "Automatikus mentés kész" else "Az automatikus mentés nem sikerült")
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_BACKUP, n) }
    }

    companion object {
        const val CHANNEL_BUDGET = "budget"
        const val CHANNEL_BACKUP = "backup"
        private const val NOTIF_BUDGET_BASE = 1000
        private const val NOTIF_BACKUP = 2
    }
}

/** Checks budgets after a receipt is saved and once a day. */
class BudgetCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as KiadasApp).container
        val alerts = c.budgets.newAlerts()
        if (alerts.isNotEmpty()) c.notifier.budgetAlerts(alerts)
        return Result.success()
    }
}

/** Periodic local backup (only when the user enabled it). */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as KiadasApp).container
        val s = c.settings.current()
        if (!s.autoBackup) return Result.success()
        return try {
            val msg = c.backup.autoBackup(s.autoBackupFolder)
            c.settings.setLastBackup(System.currentTimeMillis(), msg)
            Result.success()
        } catch (e: Exception) {
            val msg = "Hiba: ${e.message ?: "ismeretlen"}"
            c.settings.setLastBackup(System.currentTimeMillis(), msg)
            c.notifier.backupResult(msg, false)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}

object WorkScheduler {
    private const val BUDGET_DAILY = "budget-daily"
    private const val AUTO_BACKUP = "auto-backup"

    fun scheduleDaily(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BUDGET_DAILY, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<BudgetCheckWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    fun checkBudgetsNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<BudgetCheckWorker>().build())
    }

    fun scheduleAutoBackup(context: Context, enabled: Boolean, intervalDays: Int) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(AUTO_BACKUP); return }
        wm.enqueueUniquePeriodicWork(
            AUTO_BACKUP, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalDays.coerceAtLeast(1).toLong(), TimeUnit.DAYS).build(),
        )
    }
}
