package hu.kiadaskoveto

import android.app.Application
import hu.kiadaskoveto.data.work.WorkScheduler
import kotlinx.coroutines.launch

class KiadasApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.start()
        WorkScheduler.scheduleDaily(this)
        container.appScope.launch {
            val s = container.settings.current()
            WorkScheduler.scheduleAutoBackup(this@KiadasApp, s.autoBackup, s.autoBackupIntervalDays)
        }
    }
}
