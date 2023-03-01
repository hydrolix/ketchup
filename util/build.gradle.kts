plugins {
    kotlin("jvm")
}

val jacksonVersion: String by project

dependencies {
    kotlin("stdlib")

    api("ch.qos.logback:logback-classic:1.2.11")

    api("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:$jacksonVersion")
}