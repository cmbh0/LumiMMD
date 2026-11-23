plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lumi.mmd.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Lua 5.2 解释器（纯 Java，MIT 许可）：脚本驱动模型、做特效
    api("org.luaj:luaj-jse:3.0.1")
    testImplementation("junit:junit:4.13.2")
}
