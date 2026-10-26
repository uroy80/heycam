package heycam.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HeycamApplication : Application() {
    @Inject lateinit var startup: AppStartup

    override fun onCreate() {
        super.onCreate()
        startup.run()
    }
}
