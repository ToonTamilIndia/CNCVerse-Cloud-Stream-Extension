import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("com.github.recloudstream.gradle:gradle:81b1d424d")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo("https://github.com/toonTamilIndia/CNCVerse-Cloud-Stream-Extension")
        authors = listOf("toonTamilIndia")
    }

    android {
        namespace = "com.cncverse"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35

            // CineTvProvider keys (from original NivinCNC source)
            buildConfigField("String", "CINETV_SECRET_KEY_ENCRYPTED", "\"MxASAkl/yHTGg+/Tw1R7u96nGqkWsOZ2\"")
            buildConfigField("String", "CINETV_DES_KEY", "\"dsawdf634eebGFHITR5UT9kS0\"")
            buildConfigField("String", "CINETV_DES_IV", "\"32456738\"")
            buildConfigField("String", "CINETV_AES_KEY", "\"0123456789123456\"")
            buildConfigField("String", "CINETV_AES_IV", "\"2015030120123456\"")
            buildConfigField("String", "CINETV_WS_SECRET", "\"00b5f05c40b4f1d91dbc9b3fd8a059ef\"")

            // CastleTv provider key suffix
            buildConfigField("String", "CASTLE_SUFFIX", "\"\"")

            // Pikashow provider keys (original values)
            buildConfigField("String", "PIKASHOW_API_KEY", "\"\"")
            buildConfigField("String", "PIKASHOW_HMAC_SECRET", "\"\"")

            // XonProvider Firebase config (empty = uses hardcoded fallback)
            buildConfigField("String", "XON_FIREBASE_API_KEY", "\"\"")
            buildConfigField("String", "XON_FIREBASE_APP_ID", "\"\"")
            buildConfigField("String", "XON_FIREBASE_PROJECT_NUMBER", "\"\"")

            // Library identifiers
            buildConfigField("String", "LIBRARY_PACKAGE_NAME", "\"com.cncverse\"")
            buildConfigField("String", "SMARTLINK_URL", "\"\"")
            buildConfigField("String", "SPEEDLINK_URL", "\"\"")
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }


        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xannotation-default-target=param-property"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // Other dependencies
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.16")
        implementation("org.jsoup:jsoup:1.22.1")
        implementation("androidx.annotation:annotation:1.9.1")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        implementation("org.mozilla:rhino:1.9.0")
        implementation("me.xdrop:fuzzywuzzy:1.4.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        implementation("com.github.vidstige:jadb:v1.2.1")
        implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}