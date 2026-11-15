import java.io.File
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// Configuration-cache safe branch detection via tracked .git/HEAD input
val gitBranch = providers.fileContents(rootProject.layout.projectDirectory.file(".git/HEAD"))
    .asText
    .map { text ->
        val line = text.lines().firstOrNull()?.trim().orEmpty()
        if (line.startsWith("ref: refs/heads/")) line.removePrefix("ref: refs/heads/") else line
    }
    .getOrElse("")

val betaProp = providers.gradleProperty("betaBuild")
val isBeta = if (betaProp.isPresent) {
    betaProp.get().toBoolean()
} else {
    gitBranch.equals("beta", ignoreCase = true)
}

android {
    namespace = "app.overbay.twitter"
    compileSdk = 36

    defaultConfig {
        applicationId = if (isBeta) "app.overbay.twitter.beta" else "app.overbay.twitter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = if (isBeta) "0.1.0-beta" else "0.1.0"
    }

    sourceSets {
        getByName("debug") {
            if (isBeta) {
                res.srcDirs("src/beta/res")
            }
        }
        getByName("release") {
            if (isBeta) {
                res.srcDirs("src/beta/res")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.swiperefreshlayout)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

abstract class CopyDebugApkTask : DefaultTask() {
    @get:InputFile
    abstract val sourceApk: RegularFileProperty

    @get:Input
    abstract val betaBuild: Property<Boolean>

    @get:Internal
    abstract val projectRootDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val src = sourceApk.get().asFile
        if (src.exists()) {
            val root = projectRootDir.get().asFile
            val betaApk = File(root, "TwitterBeta-debug.apk")
            val standardApk = File(root, "Twitter-debug.apk")
            if (betaBuild.get()) {
                src.copyTo(betaApk, overwrite = true)
                if (standardApk.exists()) standardApk.delete()
                println("Copied ${src.name} -> TwitterBeta-debug.apk")
            } else {
                src.copyTo(standardApk, overwrite = true)
                if (betaApk.exists()) betaApk.delete()
                println("Copied ${src.name} -> Twitter-debug.apk")
            }
        }
    }
}

tasks.register<CopyDebugApkTask>("copyDebugApkToRoot") {
    sourceApk.set(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    betaBuild.set(isBeta)
    projectRootDir.set(layout.projectDirectory.dir(".."))
}

afterEvaluate {
    tasks.findByName("assembleDebug")?.finalizedBy("copyDebugApkToRoot")
}
