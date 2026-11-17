plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "dev.sleepalarm.sensors"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:analysis"))
    implementation(project(":core:settings"))
    implementation(project(":core:alarm"))
    implementation(libs.androidx.core)
    implementation(libs.health.services)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    testImplementation(libs.junit)
}
