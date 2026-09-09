package ua.starlink.reader.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import ua.starlink.reader.BuildConfig
import ua.starlink.reader.ads.AdsConsent

/**
 * Банер унизу головного екрана.
 *
 * Свідомо тільки тут: на екрані сканера й у картці комплекту реклами немає —
 * там людина працює з даними, і банер поверх серійника це помилка, а не дохід.
 *
 * Якщо реклама не завантажилась (немає інтернету — звична річ на монтажі, або
 * AdMob не дав заповнення), банер не займає жодного пікселя.
 */
@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    if (!BuildConfig.SHOW_ADS) return
    // Поки згода не отримана, рекламу вантажити не можна.
    if (!AdsConsent.canRequestAds) return

    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    var loaded by remember { mutableStateOf(false) }

    val adView = remember {
        AdView(context).apply {
            adUnitId = BuildConfig.AD_UNIT_ID
            // Inline-банер з обмеженою висотою: на всю ширину, але не вище 60dp.
            // Стара «anchored» родина застаріла в SDK 25, а її заміна
            // getLargeAnchoredAdaptiveBannerAdSize() займає близько 100dp —
            // це забагато для екрана, з якого працюють.
            setAdSize(AdSize.getInlineAdaptiveBannerAdSize(widthDp, MAX_BANNER_HEIGHT_DP))
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    loaded = true
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loaded = false
                }
            }
            loadAd(AdRequest.Builder().build())
        }
    }

    DisposableEffect(Unit) {
        onDispose { adView.destroy() }
    }

    if (loaded) {
        AndroidView(
            factory = { adView },
            modifier = modifier
                .fillMaxWidth()
                .background(Brand.Ink),
        )
    }
}

/** Стеля висоти банера в dp. Більше — більший дохід, менше екрана для роботи. */
private const val MAX_BANNER_HEIGHT_DP = 60

/**
 * Пункт «Налаштування конфіденційності». GDPR вимагає дати можливість змінити
 * рішення будь-коли, тому кнопка зʼявляється сама там, де згода була потрібна.
 * Поза ЄС її не видно.
 */
@Composable
fun PrivacyOptionsEntry() {
    if (!BuildConfig.SHOW_ADS) return
    if (!AdsConsent.privacyOptionsRequired) return
    val activity = LocalContext.current as? Activity ?: return

    TextButton(onClick = { AdsConsent.showPrivacyOptions(activity) }) {
        Text(
            stringResource(ua.starlink.reader.R.string.privacy_options),
            style = MaterialTheme.typography.bodySmall,
            color = Brand.Accent,
        )
    }
}
