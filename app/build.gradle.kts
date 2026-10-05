plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.gcodus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.gcodus"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.6.1"
    }

    // Character portraits, banner JSON and other character assets are NOT packaged in the APK.
    // All character data and portraits are fetched from the GitHub database at runtime.
    sourceSets {
        getByName("main") {
            assets.setSrcDirs(emptyList<Any>())
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

// The icon is kept as images.jpeg in the project root. Copy it into Android's
// drawable resources automatically before every build, so no manual copying is needed.
val prepareAppIcon by tasks.registering(Copy::class) {
    from(rootProject.file("images.jpeg"))
    into(layout.buildDirectory.dir("generated/res/appIcon/drawable"))
    rename { "app_icon.jpeg" }
}

tasks.named("preBuild").configure {
    dependsOn(prepareAppIcon)
}

// Use a concrete directory here. Newer Android Gradle Plugin versions reject
// Provider instances passed directly to Android SourceSet APIs.
android.sourceSets.getByName("main").res.srcDir(
    layout.buildDirectory.dir("generated/res/appIcon").get().asFile
)

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
