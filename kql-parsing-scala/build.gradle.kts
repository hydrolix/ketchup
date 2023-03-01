plugins {
    scala
}

val junitVersion: String by project
val scalaBinaryVersion = "2.13"

dependencies {
    implementation("org.scala-lang:scala-library:2.13.8")

    testImplementation(project(":kql-testing"))
    implementation(project(":util"))
    implementation(project(":model"))

    implementation("com.lihaoyi:fastparse_$scalaBinaryVersion:2.3.3")
    implementation("io.arrow-kt:arrow-core:1.1.2")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.scala-lang:scala-reflect:2.13.8")
}

tasks.withType<ScalaCompile> {
    scalaCompileOptions.additionalParameters = listOf("-nowarn")
}
