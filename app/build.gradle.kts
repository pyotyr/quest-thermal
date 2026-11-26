plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.meta.spatial.plugin")
}

android {
    namespace = "io.github.cbird.ht203u"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.pyotyr.questthermal"
        minSdk = 34
        targetSdk = 34
        versionCode = 12
        versionName = "0.8.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    packaging { resources.excludes.add("META-INF/LICENSE") }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir(rootProject.file("defaults"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.meta.spatial:meta-spatial-sdk:0.15.0")
    implementation("com.meta.spatial:meta-spatial-sdk-toolkit:0.15.0")
    implementation("com.meta.spatial:meta-spatial-sdk-vr:0.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

spatial { allowUsageDataCollection.set(false) }
