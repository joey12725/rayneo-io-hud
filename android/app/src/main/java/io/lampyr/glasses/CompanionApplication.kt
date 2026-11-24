package io.lampyr.glasses

import android.app.Application
import io.lampyr.glasses.settings.CompanionConfigStore

/**
 * Neutral application class for the companion. Cold entry points (boot receiver, notification listener, in-call
 * service) all start here, so the typed companion config (assistant and owner names, integrations, notification
 * policy) is loaded once before any of them reads it.
 */
class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CompanionConfigStore(this).publish()
    }
}
