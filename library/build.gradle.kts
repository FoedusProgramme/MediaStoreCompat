@file:Suppress("UnstableApiUsage")

import kotlin.math.max


plugins {
    alias(libs.plugins.android.library)
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