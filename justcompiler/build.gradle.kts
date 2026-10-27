plugins {
    kotlin("jvm") version "2.4.20"
    `java-gradle-plugin`
    `maven-publish`
}

group = "me.collumy"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.20")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.20")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}

gradlePlugin {
    plugins {
        create("justCompiler") {
            id = "me.collumy.justcompiler"
            implementationClass = "me.collumy.justcompiler.plugin.JustGradlePlugin"
        }
    }
}