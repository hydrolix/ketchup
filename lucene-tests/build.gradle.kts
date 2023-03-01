plugins {
    kotlin("jvm")
}

val junitVersion: String by project

dependencies {
    testImplementation(project(mapOf("path" to ":server-common")))
    kotlin("stdlib")

    implementation(project(":server-common"))

    testImplementation("org.apache.lucene:lucene-queryparser:9.4.2")

    testImplementation(testFixtures(project(":server-common")))
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation(kotlin("test"))
}

repositories {
    mavenCentral()
}
