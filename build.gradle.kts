plugins {
    java
    application // <-- add this
    maven
}

// add start here
val run: JavaExec by tasks
run.apply {
    main = "org.pepsoft.worldpainter.Main"
    classpath = java.sourceSets["main"].runtimeClasspath
}
//add end here

group = "io.github.opencubicchunks"
version = "1.4.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenLocal()
    maven { setUrl("https://oss.sonatype.org/content/repositories/public/") }
    maven { setUrl("http://repo.maven.apache.org/maven2") }
}

// TODO: update jide
configurations.all { resolutionStrategy {
    force("com.jidesoft:jide-dock:3.7.9")
    force("com.jidesoft:jide-common:3.7.9")
    force("com.jidesoft:jide-plaf-jdk7:3.7.9")
} }

dependencies {
    implementation(group = "io.github.opencubicchunks", name = "regionlib", version = "0.61.0-SNAPSHOT")
    implementation(group = "com.carrotsearch", name = "hppc", version = "0.8.1")
    runtimeOnly(group = "org.pepsoft.worldpainter", name = "WPGUI", version = "2.14.0") {
        exclude(module = "WPDynmapPreviewer")
    }
    compileOnly(group = "org.pepsoft.worldpainter", name = "WPCore", version = "2.14.0")
}