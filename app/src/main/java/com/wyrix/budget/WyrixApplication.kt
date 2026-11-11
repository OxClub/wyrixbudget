package com.wyrix.budget

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.wyrix.budget.data.local.DefaultDataSeeder
import com.wyrix.budget.data.local.DemoDataSeeder
import com.wyrix.budget.data.notify.NotificationChannels
import com.wyrix.budget.data.notify.ReminderScheduler
import com.wyrix.budget.data.prefs.DemoMode
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class WyrixApplication : Application(), Configuration.Provider {

    @Inject lateinit var seeder: DefaultDataSeeder
    @Inject lateinit var demoSeeder: DemoDataSeeder
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        ReminderScheduler.schedule(this)
        appScope.launch {
            seeder.seedIfEmpty()
            if (DemoMode.isOn(this@WyrixApplication)) demoSeeder.seedIfEmpty()
        }
    }
}
