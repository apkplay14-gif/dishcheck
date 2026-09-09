package ua.starlink.reader.ads

import android.app.Activity
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import ua.starlink.reader.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Збір згоди на обробку даних через Google UMP.
 *
 * Порядок навмисно саме такий: спершу питаємо згоду, і лише коли UMP каже
 * «можна» — ініціалізуємо SDK реклами й вантажимо банер. Якщо зробити навпаки,
 * реклама встигне звернутись до Google до того, як користувач щось вирішив.
 *
 * За межами ЄС форма не показується взагалі: UMP сам визначає регіон і одразу
 * повертає дозвіл. Тобто для України користувач нічого не побачить.
 */
object AdsConsent {

    private val canRequestAdsState = mutableStateOf(false)

    /** Чи можна вантажити рекламу. Compose-стан: банер зʼявиться сам, коли стане true. */
    val canRequestAds: Boolean get() = canRequestAdsState.value

    private val privacyOptionsState = mutableStateOf(false)

    /** Чи треба показувати пункт «Налаштування конфіденційності» — вимога GDPR. */
    val privacyOptionsRequired: Boolean get() = privacyOptionsState.value

    private val adsInitialized = AtomicBoolean(false)

    fun gather(activity: Activity) {
        if (!BuildConfig.SHOW_ADS) return

        val consent = UserMessagingPlatform.getConsentInformation(activity)
        val builder = ConsentRequestParameters.Builder()
            .setTagForUnderAgeOfConsent(false)

        val testDevice = BuildConfig.CONSENT_TEST_DEVICE_ID
        if (BuildConfig.DEBUG && testDevice.isNotBlank()) {
            builder.setConsentDebugSettings(
                ConsentDebugSettings.Builder(activity)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                    .addTestDeviceHashedId(testDevice)
                    .build()
            )
        }

        consent.requestConsentInfoUpdate(
            activity,
            builder.build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) Log.w(TAG, "consent form: ${error.message}")
                    onConsentResolved(activity, consent)
                }
            },
            { error ->
                // Без відповіді від UMP реклама просто не показується — на роботу
                // застосунку це не впливає ніяк.
                Log.w(TAG, "consent request: ${error.message}")
                onConsentResolved(activity, consent)
            },
        )
    }

    /** Повторний показ форми: користувач має право змінити рішення будь-коли. */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
            if (error != null) Log.w(TAG, "privacy options: ${error.message}")
            onConsentResolved(activity, UserMessagingPlatform.getConsentInformation(activity))
        }
    }

    private fun onConsentResolved(activity: Activity, consent: ConsentInformation) {
        privacyOptionsState.value = consent.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

        if (!consent.canRequestAds()) {
            canRequestAdsState.value = false
            return
        }

        if (adsInitialized.compareAndSet(false, true)) {
            val appContext = activity.applicationContext
            // Ініціалізація SDK помітно довга — виносимо зі стартового потоку.
            Thread { MobileAds.initialize(appContext) }.start()
        }
        canRequestAdsState.value = true
    }

    private const val TAG = "AdsConsent"
}
