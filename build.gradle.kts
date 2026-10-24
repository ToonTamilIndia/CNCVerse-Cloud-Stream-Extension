import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    // ── CloudStream gradle plugin ─────────────────────────────────────────────
    // Published from the multi-module repo `recloudstream/gradle` through
    // JitPack. Only *commit-sha* versions of the `gradle` submodule are
    // published under a matching maven coordinate; the branch coordinates
    // (`master-SNAPSHOT`, `master-master-SNAPSHOT`, ...) resolve to a POM that
    // declares a different version and therefore trip Gradle's
    // "inconsistent module metadata / bad version" check, which breaks the
    // whole build during configuration.
    //
    // To update the plugin, bump the commit sha below to a revision of
    // https://github.com/recloudstream/gradle that has been built by JitPack
    // (verify with:
    //   curl -sI https://jitpack.io/com/github/recloudstream/gradle/gradle/<sha>/gradle-<sha>.pom
    // ).
    val csGradlePluginVersion = "81b1d424d"

    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("com.github.recloudstream.gradle:gradle:$csGradlePluginVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo("https://github.com/ToonTamilIndia/CNCVerse-Cloud-Stream-Extension")
        authors = listOf("ToonTamilIndia")
    }

    android {
        namespace = "com.cncverse"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            // The `com.lagradost:cloudstream3:pre-release` artifact is compiled
            // for JVM 11, so our modules must match. Compiling at 1.8 makes the
            // Kotlin compiler reject inlining its bytecode ("Cannot inline
            // bytecode built with JVM target 11 ..."). This also keeps CI and
            // local builds identical regardless of the host JDK.
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }


        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions",
                    "-Xannotation-default-target=param-property"
                )
            }
        }
    }

    // AGP 8 removed the implicit BuildConfig generation. The plugin modules that
    // need decryption keys read them from `BuildConfig`; those keys are declared
    // with `buildConfigField(...)` in the module's own `build.gradle.kts` and are
    // never taken from environment variables.
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        buildFeatures.buildConfig = true
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