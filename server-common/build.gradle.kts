plugins {
    kotlin("jvm")
    `java-library`
    `java-test-fixtures`
}

val ktorVersion: String by project
val jacksonVersion: String by project
val junitVersion: String by project

dependencies {
    kotlin("stdlib")
    kotlin("reflect")

    api(project(":util"))
    api(project(":model"))
    api(project(":kql-parsing-scala"))

    api("io.ktor:ktor-server-core-jvm:$ktorVersion")
    api("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    api("io.ktor:ktor-server-sessions-jvm:$ktorVersion")
    api("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    api("io.ktor:ktor-server-default-headers-jvm:$ktorVersion")
    api("io.ktor:ktor-serialization-jackson:$ktorVersion")
    api("io.ktor:ktor-server-double-receive:$ktorVersion")

    api("io.arrow-kt:arrow-core:1.1.2")
    api("com.clickhouse:clickhouse-jdbc:0.3.2-patch11")

    testApi("org.junit.jupiter:junit-jupiter:$junitVersion")
    testApi(kotlin("test"))
}

repositories {
    mavenCentral()
}
