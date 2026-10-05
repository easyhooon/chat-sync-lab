plugins { id("com.android.application"); kotlin("plugin.serialization"); id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp"); id("androidx.room") }
android {
    namespace = "dev.chatlab"
    compileSdk = 36
    defaultConfig { applicationId = "dev.chatlab"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
room { schemaDirectory("$projectDir/schemas") }
val ktor = "3.4.3"
dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.8.3")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("io.ktor:ktor-client-okhttp:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-client-websockets:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-paging:2.8.5")
    implementation("androidx.paging:paging-runtime:3.5.1")
    implementation("androidx.paging:paging-compose:3.5.1")
    ksp("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.paging:paging-testing:3.5.1")
}
