package com.pinpin.app

import android.app.Application
import com.pinpin.app.data.PinRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import com.pinpin.app.update.AppUpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application entry point.
 *
 * Just sets up the Hilt graph. Service starts and repository wiring
 * are intentionally deferred to Activity onCreate / Onboarding
 * completion: starting a foreground service from `Application.onCreate`
 * is not allowed on Android 12+ when the process is in a cold-start
 * state, and throws ForegroundServiceStartNotAllowedException. The
 * service starts are triggered from
 * [com.pinpin.app.onboarding.OnboardingActivity] and
 * [com.pinpin.app.ui.MainActivity] instead.
 */
@HiltAndroidApp
class PinApplication : Application() {

    val appUpdateController by lazy {
        AppUpdateController(this, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    @Inject lateinit var pinRepository: PinRepository

    override fun onCreate() {
        super.onCreate()
        // Nothing to do here — service start and repository→service
        // sync are wired in OnboardingActivity and MainActivity.
    }
}
