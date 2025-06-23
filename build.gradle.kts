plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
//    maven {
//        url = uri("https://maven.aliyun.com/repository/public/")
//    }
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    // 添加依赖（Gradle）
    implementation("org.jetbrains.kotlinx:multik-core:0.2.3") {
        exclude(group = "org.bytedeco") // 防止 multik 引入冲突版本
    }
    implementation("org.jetbrains.kotlinx:multik-default:0.2.3")
    // 排除冲突的传递依赖

//    implementation("org.nd4j:nd4j-native:1.0.0-beta7")
//    implementation("org.nd4j:nd4j-native:1.0.0-beta7:windows-x86_64")  // Windows
    implementation("org.nd4j:nd4j-api:1.0.0-beta7")
    implementation("org.nd4j:nd4j-native-platform:1.0.0-beta7") // 自动适配操作系统
//    // 显式添加 JavaCPP（可选，但推荐）
//    implementation("org.bytedeco:javacpp:1.5.7")
//    implementation("org.bytedeco:openblas:0.3.19-1.5.7")

    // Exposed 核心
    implementation("org.jetbrains.exposed:exposed-core:0.44.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.44.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.44.0")

    // SQLite 驱动
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}