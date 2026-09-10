import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

subprojects {
    pluginManager.withPlugin("java") {
        apply(plugin = "jacoco")

        tasks.withType<Test>().configureEach {
            finalizedBy(tasks.named("jacocoTestReport"))
        }
        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.withType<Test>())
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
    }
}

fun Project.configureAndroidUnitTestCoverage() {
    apply(plugin = "jacoco")
    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
        group = "verification"
        description = "Generates JaCoCo coverage for the debug JVM unit tests."
        dependsOn("testDebugUnitTest")

        reports {
            xml.required.set(true)
            html.required.set(true)
        }

        val generatedClasses = listOf(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "android/**/*.*",
        )
        classDirectories.setFrom(
            files(
                fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
                    exclude(generatedClasses)
                },
                fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
                    exclude(generatedClasses)
                },
            ),
        )
        sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java"))
        executionData.setFrom(
            fileTree(layout.buildDirectory) {
                include(
                    "jacoco/testDebugUnitTest.exec",
                    "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                )
            },
        )
    }
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        configureAndroidUnitTestCoverage()
    }
    pluginManager.withPlugin("com.android.library") {
        configureAndroidUnitTestCoverage()
    }
}

tasks.register("androidUnitTestCoverage") {
    group = "verification"
    description = "Runs Android JVM unit tests and generates coverage reports for every Android module."
    dependsOn(subprojects.mapNotNull { it.tasks.findByName("jacocoDebugUnitTestReport") })
}
