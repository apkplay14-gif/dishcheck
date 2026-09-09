import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Ключ підпису лежить ПОЗА репозиторієм, шлях і паролі — у keystore.properties,
// який у git не потрапляє (.gitignore). Якщо файлу немає — release збереться
// без підпису замість того, щоб упасти: так проєкт лишається складальним
// на машині, де ключа немає.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "ua.starlink.reader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dishcheck.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // ------------------------------------------------------------------ реклама
        // БОЙОВІ ідентифікатори AdMob. Тиснути цей банер зі свого телефона НЕ МОЖНА:
        // один клік по власній рекламі — привід для довічного блокування акаунта.
        // Для будь-якої перевірки поверніть сюди тестові ідентифікатори Google:
        //   App ID   ca-app-pub-3940256099942544~3347511713
        //   Ad unit  ca-app-pub-3940256099942544/9214589741
        // Свої з AdMob підставляються тільки тут, більше ніде.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-8847726899043038~5601637383"
        buildConfigField("String", "AD_UNIT_ID", "\"ca-app-pub-8847726899043038/6543183969\"")

        // Один вимикач на всю рекламу: false — банера немає взагалі.
        buildConfigField("boolean", "SHOW_ADS", "true")

        // Форма згоди GDPR показується лише в ЄС. Щоб побачити її з України,
        // впишіть сюди хеш свого пристрою — UMP друкує його в logcat
        // рядком «Use new ConsentDebugSettings.Builder()...» під час запуску.
        buildConfigField("String", "CONSENT_TEST_DEVICE_ID", "\"\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }

            // R8 вимкнено навмисно: protobuf-java будує повідомлення через рефлексію
            // дескрипторів, і скорочення коду ламає динамічний розбір відповіді Starlink.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Пакуємо лише наші мови. Без цього AndroidX, ML Kit і Play Services
        // тягнуть у APK власні переклади на 80+ локалей, серед них російську.
        localeFilters += listOf("en", "uk", "pl", "es", "de")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCY"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.protobuf.java)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
}
