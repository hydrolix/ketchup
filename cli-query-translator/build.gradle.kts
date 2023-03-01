import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "7.1.2"
    application
}

val ktorVersion: String by project
val jacksonVersion: String by project
val junitVersion: String by project

dependencies {
    kotlin("stdlib")
    kotlin("reflect")

    // Internal dependencies
    implementation(project(":util"))
    implementation(project(":kql-parsing-scala"))
    implementation(project(":server-common")) {
        // TODO split up server-common into core and server instead of having to exclude these
        exclude("io.ktor", "ktor-server-netty-jvm") // No web server in CLI
        exclude("com.clickhouse")                   // No connecting to Clickhouse in CLI
        exclude("org.apache.httpcomponents")        // No HTTP client in CLI
    }
    implementation(project(":model")) {
        exclude("org.apache.httpcomponents") // transitive via elastic Java client, not needed here
    }

    // External prod dependencies
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")

    // Test dependencies
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
}

application {
    mainClass.set("io.hydrolix.ketchup.cli.CliQueryTranslator")
}

tasks.withType<ShadowJar> {
    minimize {
        exclude(dependency("org.jetbrains.kotlin:kotlin-reflect"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib"))
        exclude(dependency("org.slf4j:slf4j-api"))
        exclude(dependency("ch.qos.logback:logback-classic"))
        exclude(dependency("org.eclipse.parsson:parsson")) // for elastic Java client
    }
}

repositories {
    mavenCentral()
}
