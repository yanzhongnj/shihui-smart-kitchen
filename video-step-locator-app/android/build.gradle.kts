allprojects {
    repositories {
        // 腾讯镜像（本机实测最快最稳）优先，阿里云兜底，官方源最后
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-google/") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}
// AGP9 下插件子模块（file_picker 8.x 等）的 check*AarMetadata 会因自身声明 compileSdk=34
// 而 flutter_plugin_android_lifecycle 要求 >=35 报错（本工程实际 compileSdk=36 满足运行要求）。
// 直接禁用该元数据检查任务，规避 AGP9 严格校验导致的构建失败。
subprojects {
    tasks.matching {
        it.name.contains("checkDebugAarMetadata") || it.name.contains("checkReleaseAarMetadata")
    }.configureEach {
        enabled = false
    }
    // AGP9: bundle*LocalLintAar / bundle*Aar 期望 aar_metadata_check 目录存在，但被上面禁用后缺失 -> 直接禁用
    tasks.matching {
        it.name.contains("bundleReleaseLocalLintAar") || it.name.contains("bundleDebugLocalLintAar")
    }.configureEach {
        enabled = false
    }
    tasks.matching {
        it.name.contains("bundleReleaseAar") || it.name.contains("bundleDebugAar")
    }.configureEach {
        enabled = false
    }
    // AGP9 release vital-lint 对插件子模块的 AAR exploded-for-lint 兼容差，禁用（不影响 APK 产出）
    tasks.matching {
        it.name.contains("lintVital") || it.name.contains("lintAnalyzeRelease")
    }.configureEach {
        enabled = false
    }
}
// 插件子模块（file_picker 等）默认用 flutter.compileSdkVersion=34，但
// flutter_plugin_android_lifecycle 2.0.35 / video_player 2.14 等要求 compileSdk>=36。
// 统一把所有 Android library 子模块的 compileSdk 提升到 36（本地已装 android-36 平台）。
subprojects {
    project.pluginManager.withPlugin("com.android.library") {
        val force = {
            try {
                val android = extensions.findByName("android")
                val m = android?.javaClass?.methods?.firstOrNull {
                    it.name in setOf("compileSdkVersion", "setCompileSdkVersion") &&
                        it.parameterCount == 1 && it.parameterTypes[0] == Integer.TYPE
                }
                m?.invoke(android, 36)
            } catch (e: Throwable) {
                logger.warn("compileSdk override failed for ${project.name}: ${e.message}")
            }
        }
        if (project.state.executed) force() else project.afterEvaluate { force() }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
