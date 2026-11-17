plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "dev.sleepalarm.diagnostics"
    compileSdk = 36
    defaultConfig { minSdk = 30 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:analysis"))
    implementation(project(":core:settings"))
    implementation(libs.wearable)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
}

tasks.withType<Test>().configureEach { systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
