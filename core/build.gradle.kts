// Pure Kotlin/JVM module: no android.* and no ai.pleos.* imports allowed.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Routine and its @Serializable types are part of the public API used by :app.
    api(libs.kotlinx.serialization.json)
    // Executor ports are suspend functions; confirmation uses withTimeoutOrNull.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// Coverage gate (80% lines); report in core/build/reports/jacoco.
tasks.jacocoTestReport {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
