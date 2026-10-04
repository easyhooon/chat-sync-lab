plugins { kotlin("jvm"); kotlin("plugin.serialization"); application }
kotlin { jvmToolchain(21) }
application { mainClass.set("chatlab.ServerKt") }
val ktor = "3.4.3"
dependencies {
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test-junit5"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-websockets:$ktor")
    testImplementation("io.ktor:ktor-client-cio:$ktor")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktor")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { useJUnitPlatform() }
tasks.register<JavaExec>("demoClient") {
    group = "application"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("chatlab.DemoClientKt")
    dependsOn(tasks.testClasses)
    standardInput = System.`in`
}
tasks.register<JavaExec>("ackLossProxy") {
    group = "verification"
    description = "Explicit local test-only ACK-loss proxy; requires -Pscenario=both or http-only"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("chatlab.AckLossProxyKt")
    dependsOn(tasks.testClasses)
    args(providers.gradleProperty("scenario").getOrElse(""))
}
