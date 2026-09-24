package com.example

import android.app.Application
import com.example.data.database.RecordingDatabase
import com.example.data.repository.RecordingRepository
import com.example.utils.AudioPlayerManager
import com.example.utils.AudioRecorderManager

/**
 * Manual DI container (stepping stone to Hilt).
 *
 * Phase 5: singletons live here instead of `new` in ViewModel/Service.
 * Phase 5+: replace with Hilt @HiltAndroidApp + @Module without changing
 * call sites (they already read from [container]).
 */
class SajilApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    private val appContext = app.applicationContext

    val database: RecordingDatabase by lazy {
        RecordingDatabase.getDatabase(appContext)
    }

    val repository: RecordingRepository by lazy {
        RecordingRepository(appContext, database.recordingDao())
    }

    val recorderManager: AudioRecorderManager by lazy {
        AudioRecorderManager(appContext)
    }

    val playerManager: AudioPlayerManager by lazy {
        AudioPlayerManager(appContext)
    }
}
