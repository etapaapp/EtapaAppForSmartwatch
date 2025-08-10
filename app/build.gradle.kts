plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.marinov.colegioetapa"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.marinov.colegioetapa"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "2.0"
        buildConfigField("String", "EAD_URL", "\"${project.properties["EAD_URL"]}\"")
        buildConfigField("String", "GITHUB_PAT", "\"${project.properties["GITHUB_PAT"]}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        android.buildFeatures.buildConfig = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.webkit)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation (libs.jsoup)
    implementation (libs.gson)
    implementation (libs.work.runtime.ktx)
    implementation (libs.listenablefuture)
    implementation (libs.retrofit)
    implementation (libs.converter.gson)
    implementation (libs.logging.interceptor)
    implementation (libs.guava)
    implementation (libs.glide)
    annotationProcessor (libs.compiler)
    implementation (libs.swiperefreshlayout)
}
