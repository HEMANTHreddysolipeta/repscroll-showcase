plugins {
    kotlin("jvm") version "2.0.21"
}

repositories { mavenCentral() }

dependencies {
    testImplementation("junit:junit:4.13.2")
}


tasks.test {
    testLogging { events("passed", "failed"); showStandardStreams = false }
}
