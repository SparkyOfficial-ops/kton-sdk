plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("maven-publish")
}

android {
    namespace = "io.kton.connect"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }
    androidTarget {
        publishLibraryVariants("release")
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            api("io.ktor:ktor-client-core:3.1.1")
        }
        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.1.1")
                implementation("com.google.zxing:core:3.5.3")
            }
        }
        jvmMain {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                implementation("net.java.dev.jna:jna:5.15.0")
            }
        }
        androidMain {
            dependsOn(jvmAndAndroidMain)
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
                implementation("androidx.security:security-crypto:1.1.0-alpha06")
            }
        }
        jvmTest {
            dependsOn(commonTest.get())
        }
        androidUnitTest {
            dependsOn(commonTest.get())
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("io.ktor:ktor-server-core:3.1.1")
            implementation("io.ktor:ktor-server-cio:3.1.1")
            implementation("io.ktor:ktor-server-sse:3.1.1")
        }
    }
}
