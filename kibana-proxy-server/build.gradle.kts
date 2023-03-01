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

    implementation(project(":server-common"))
    testImplementation(project(":server-common"))

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.hydrolix.ketchup.server.ketchup.KibanaProxyMain")
}

repositories {
    mavenCentral()
}
