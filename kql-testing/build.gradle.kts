plugins {
    kotlin("jvm")
    `java-library`
}

val junitVersion: String by project

dependencies {
    kotlin("stdlib")

    implementation(project(":util"))

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation(kotlin("test"))
}
