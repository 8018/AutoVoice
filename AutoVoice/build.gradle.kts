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

fun Project.configureAndroidUnitTestCoverage(variant: String = "debug") {
    val variantTask = variant.replaceFirstChar { it.uppercase() }
    apply(plugin = "jacoco")
    extensions.configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    tasks.register<JacocoReport>("jacoco${variantTask}UnitTestReport") {
        group = "verification"
        description = "Generates JaCoCo coverage for the debug JVM unit tests."
        dependsOn("test${variantTask}UnitTest")

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
                fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/$variant")) {
                    exclude(generatedClasses)
                },
                fileTree(layout.buildDirectory.dir("intermediates/javac/$variant/compile${variantTask}JavaWithJavac/classes")) {
                    exclude(generatedClasses)
                },
            ),
        )
        sourceDirectories.setFrom(files("src/main/kotlin", "src/main/java", "src/${variant.removeSuffix("Debug")}/kotlin"))
        executionData.setFrom(
            fileTree(layout.buildDirectory) {
                include(
                    "jacoco/test${variantTask}UnitTest.exec",
                    "outputs/unit_test_code_coverage/${variant}UnitTest/test${variantTask}UnitTest.exec",
                )
            },
        )
        if (project.name == "audio-frontend" && variant == "debug") {
            // Legacy VadSegmenter tests exercise the same public frontend classes.
            val legacy = project(":audio-frontend-legacy")
            dependsOn(":audio-frontend-legacy:testDebugUnitTest")
            executionData.from(legacy.layout.buildDirectory.file(
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            ))
        }
    }
}

subprojects {
    pluginManager.withPlugin("com.android.application") {
        configureAndroidUnitTestCoverage("iflytekDebug")
        configureAndroidUnitTestCoverage("openDebug")
        tasks.register("jacocoDebugUnitTestReport") {
            group = "verification"
            dependsOn("jacocoIflytekDebugUnitTestReport", "jacocoOpenDebugUnitTestReport")
        }
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
