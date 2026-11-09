plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.stickerboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.stickerboard"
        minSdk = 24
        targetSdk = 35
        // En CI, VERSION_CODE sube solo en cada compilación (número de ejecución de GitHub).
        // Android solo deja actualizar encima si el versionCode es mayor al instalado.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1"

        // Solo teléfonos reales (ARM de 64 y 32 bits). Quita las copias para x86 de las librerías
        // nativas de ML Kit, que son emuladores y pesan decenas de MB.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // La misma clave en cada compilación = la app se actualiza sin desinstalar.
    // Las credenciales vienen de variables de entorno (secretos de GitHub), nunca del repositorio.
    val releaseKeystore = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sin clave de release (compilación local), firma con la de depuración para poder probar.
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // Traductor en el dispositivo (los modelos se descargan la primera vez)
    implementation("com.google.mlkit:translate:17.0.3")
}
