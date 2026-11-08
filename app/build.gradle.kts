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
    // Reconoce qué hay en la imagen de un sticker (modelo incluido en la app, sin internet)
    implementation("com.google.mlkit:image-labeling:17.0.9")
}
