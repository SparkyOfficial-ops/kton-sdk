plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            api("io.ktor:ktor-client-core:3.1.1")
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.1.1")
            implementation("com.google.zxing:core:3.5.3")
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
