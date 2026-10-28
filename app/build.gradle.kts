plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mehmetdogru.calltracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mehmetdogru.calltracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    // Release APK'si IMZALANMALIDIR. Onceden imzalama yapilandirmasi HIC yoktu:
    // "assembleRelease" app-release-unsigned.apk uretiyordu ve Android imzasiz
    // paketi kurmaz ("Uygulama yuklenmedi"). Dagitimci uygulamasiyla AYNI
    // anahtar kullanilir; boylece iki uygulama da ayni sekilde guncellenebilir.
    signingConfigs {
        create("dagitim") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dagitim")
        }
    }

    // CT-001: ApiService interceptor'i X-App-Version basligini BuildConfig.VERSION_NAME'den
    // okuyor. AGP 8+ buildConfig uretimini varsayilan olarak kapattigi icin acikca aciliyor.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
    // CT-001: Cihaz anahtarinin Android Keystore destekli sifreli saklanmasi.
    // Not: 1.1.0-alpha06, MasterKey.Builder API'sini sunan ve pratikte standart olan surumdur;
    // 1.0.0'daki MasterKeys API'si kullanimdan kaldirilmistir.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
