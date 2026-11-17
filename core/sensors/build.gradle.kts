plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "dev.sleepalarm.sensors"
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
    implementation(project(":core:alarm"))
    implementation(libs.androidx.core)
    implementation(libs.health.services)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Use Maven Central's canonical endpoint for Robolectric runtime SDK jars.
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
