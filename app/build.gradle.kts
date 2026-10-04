import java.util.zip.ZipFile
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
}

val importPortraitsFromArchive by tasks.registering {
    val archive = rootProject.file("G-Codus.zip")
    val output = layout.buildDirectory.dir("generated/portraitAssets")
    inputs.file(archive)
    outputs.dir(output)
    doLast {
        if (!archive.isFile) return@doLast
        val root = output.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        val gameFolders = mapOf(
            "genshin" to "genshin",
            "wuthering_waves" to "wuthering_waves",
            "zenless_zone_zero" to "zenless_zone_zero",
            "honkai_star_rail" to "honkai_star_rail",
            "arknights_endfield" to "arknights_endfield"
        )
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(".webp", true)) continue
                val normalized = entry.name.replace('\\', '/')
                val game = gameFolders.entries.firstOrNull { (folder, _) ->
                    normalized.contains("/$folder/") || normalized.startsWith("$folder/")
                }?.value ?: continue
                val name = normalized.substringAfterLast('/')
                val target = root.resolve(game).resolve(name)
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { outputStream -> input.copyTo(outputStream) }
                }
            }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(importPortraitsFromArchive) }

android {
    namespace = "com.example.gcodus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.gcodus"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "../assets", "../images_big", layout.buildDirectory.dir("generated/portraitAssets"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
}
