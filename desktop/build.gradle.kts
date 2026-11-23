// 桌面无头渲染工具：把 engine 的源码直接编进一个纯 JVM 模块，
// 用 LWJGL(GLES3) + Mesa llvmpipe 的 EGL surfaceless 上下文跑同一套渲染代码，输出 PNG 做回归验证。
// android.opengl.GLES20/GLES30 由 src/main/java 下的同名垫片类转发到 LWJGL。
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin", "../engine/src/main/kotlin")
        java.srcDirs("src/main/java")
    }
    test {
        kotlin.srcDirs("../engine/src/test/kotlin")
    }
}

dependencies {
    val lwjgl = "3.3.4"
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjgl"))
    implementation("org.luaj:luaj-jse:3.0.1")
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-egl")
    implementation("org.lwjgl:lwjgl-opengles")
    runtimeOnly("org.lwjgl:lwjgl::natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-opengles::natives-linux")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.lumi.mmd.desktop.HeadlessMainKt")
    applicationDefaultJvmArgs = listOf("-Xmx900m", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

tasks.withType<JavaExec>().configureEach {
    environment("LC_ALL", "C.UTF-8")
    environment("EGL_PLATFORM", "surfaceless")
    environment("LIBGL_ALWAYS_SOFTWARE", "1")
    environment("GALLIUM_DRIVER", "llvmpipe")
    workingDir = rootProject.projectDir
}
tasks.withType<Test>().configureEach {
    maxHeapSize = "700m"
    workingDir = rootProject.projectDir
    testLogging { events("failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
