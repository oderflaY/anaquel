import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            // NativeSQLiteDriver usa el SQLite que ya trae iOS.
            linkerOpts("-lsqlite3")
        }
    }
    
    android {
       namespace = "com.eter.mitienda.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            // Escáner: CameraX (vista previa + análisis) y ML Kit con el modelo incluido en el APK (100 % offline).
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
            implementation(libs.mlkit.barcode.scanning)
            // Lanzador del diálogo de permisos del sistema.
            implementation(libs.androidx.activity.compose)
            // Orientación EXIF de las fotos (al recomprimir se pierde y saldrían giradas).
            implementation(libs.androidx.exifinterface)
            // Sincronización P2P: Nearby Connections (Bluetooth + BLE + Wi-Fi directo, sin internet).
            implementation(libs.play.services.nearby)
            // Recordatorios de pedidos: trabajos programados que sobreviven a reinicios.
            implementation(libs.androidx.work.runtime)
            // Anaquel (nube): cliente HTTP.
            implementation(libs.ktor.client.okhttp)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.compose)
            // Fechas locales (días, zona horaria) para gráficas y fechas de entrega.
            implementation(libs.kotlinx.datetime)
            // SQL crudo multiplataforma (sin ORM). Driver: AndroidSQLiteDriver / NativeSQLiteDriver.
            api(libs.androidx.sqlite.framework)
            // Anaquel (nube): API del servidor.
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // Pruebas instrumentadas: el DAO se prueba en el dispositivo con el driver real (AndroidSQLiteDriver).
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.testExt.junit)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}