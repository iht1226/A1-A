plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
    namespace = "dev.sleepalarm.phone"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.sleepalarm.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:settings"))
    implementation(project(":core:health"))
    implementation(project(":core:sync"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.health.connect)
    implementation(libs.wearable)
    // Play Services transitively requests an old Fragment; keep ActivityResult dispatch compatible.
    implementation(libs.androidx.fragment)
}
