import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "7.1.2"
    application
}

dependencies {
    kotlin("stdlib")

    implementation(project(":util"))
    implementation(project(":model"))
}

application {
    mainClass.set("io.hydrolix.ketchup.model.hdx.HydrolixMetadataTranslator")
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
