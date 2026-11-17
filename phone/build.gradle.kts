plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose) }
android {
    namespace = "dev.sleepalarm.phone"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.sleepalarm.app"
        minSdk = 30
        targetSdk = 35
        versionCode = System.getenv("APP_VERSION_CODE")?.toInt() ?: 5
        versionName = "0.4.0"
    }
    val signingPath = System.getenv("SIGNING_KEYSTORE_PATH")
    if (!signingPath.isNullOrBlank()) {
        signingConfigs.create("stable") {
            storeFile = file(signingPath)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
        buildTypes.getByName("release") { signingConfig = signingConfigs.getByName("stable") }
        buildTypes.getByName("debug") { signingConfig = signingConfigs.getByName("stable") }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core:diagnostics"))
    implementation(project(":core:update"))
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
