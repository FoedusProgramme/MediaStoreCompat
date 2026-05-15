@file:Suppress("UnstableApiUsage")

import kotlin.math.max


plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.publish)
    id("signing")
}

android {
    namespace = "org.nift4.mediastorecompat"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments += "clearPackageData" to "true"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    lint {
        checkTestSources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        managedDevices {
            localDevices {
                for (i in max(27, defaultConfig.minSdk!!)..compileSdk!!) {
                    create("galaxyNexusApi$i") {
                        device = "Galaxy Nexus"
                        apiLevel = i
                        systemImageSource = "aosp"
                        testedAbi = "x86_64" // This presently defaults to "x86". However, in 9.0 this will change to "arm64-v8a"
                    }
                }
            }
            groups {
                create("galaxyNexusAllApis") {
                    for (i in max(27, defaultConfig.minSdk!!)..compileSdk!!) {
                        targetDevices.add(localDevices["galaxyNexusApi$i"])
                    }
                }
            }
        }
    }
}
signing {
    useGpgCmd()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("io.github.nift4.mediastorecompat", "minsdk21", "1.0.0-alpha03")

    pom {
        name.set("MediaStoreCompat")
        description.set("Effortlessly access media files on Android 5 or later, on internal storage and on SD cards")
        inceptionYear.set("2026")
        url.set("https://github.com/FoedusProgramme/MediaStoreCompat")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("nift4")
                name.set("Nick")
                url.set("https://github.com/nift4/")
            }
            developer {
                id.set("android")
                name.set("Portions (C) 2007 The Android Open Source Project")
                url.set("https://source.android.com/")
            }
        }
        scm {
            url.set("https://github.com/FoedusProgramme/MediaStoreCompat")
            connection.set("scm:git:git://github.com/FoedusProgramme/MediaStoreCompat.git")
            developerConnection.set("scm:git:ssh://git@github.com/FoedusProgramme/MediaStoreCompat.git")
        }
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.test.uiautomator.shell)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.testparameterinjector)
    androidTestUtil(libs.androidx.test.orchestrator)
}