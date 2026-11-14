plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "io.github.alerrandrov.easytelebot"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.alerrandrov.easytelebot"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // ABIs suportadas pelo Python 3.12 do Chaquopy (só 64 bits).
            // Celulares reais são arm64; x86_64 fica para o emulador.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Assinatura de release: o workflow recria o keystore a partir dos Secrets
    // e passa os dados por variáveis de ambiente. Sem elas, o release sai sem assinatura.
    val keystorePath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }

    // Código-fonte do motor + run.py vão como assets para o "Exportar código".
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/exportAssets"))
}

val copyExportSources by tasks.registering(Copy::class) {
    into(layout.buildDirectory.dir("generated/exportAssets/export"))
    from("src/main/python") {
        include("telebot_engine/**/*.py")
    }
    from("src/main/export")
}
tasks.named("preBuild") { dependsOn(copyExportSources) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        // Nenhum pacote pip: o motor usa só a biblioteca padrão.
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
