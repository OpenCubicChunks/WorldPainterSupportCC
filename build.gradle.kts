plugins {
    java
    application // <-- add this
}

// add start here
val run: JavaExec by tasks
run.apply {
    mainClass = "org.pepsoft.worldpainter.Main"
    classpath = java.sourceSets["main"].runtimeClasspath
}
//add end here

group = "io.github.opencubicchunks"
version = "1.5.0"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    mavenLocal()
    maven { setUrl("https://maven.daporkchop.net/") }
    maven { setUrl("https://repo.maven.apache.org/maven2") }
}

// TODO: update jide
configurations.all { resolutionStrategy {
    force("com.jidesoft:jide-dock:local")
    force("com.jidesoft:jide-common:local")
    force("com.jidesoft:jide-plaf-jdk7:local")
    force("net.sourceforge:jpen:local")
    force("org.netbeans.swing:laf-dark:local")
    force("us.dynmap:DynmapCore:local")
} }

dependencies {
    implementation(group = "io.github.opencubicchunks", name = "regionlib", version = "0.90.0")
    implementation(group = "com.carrotsearch", name = "hppc", version = "0.8.1")
    runtimeOnly(group = "org.pepsoft.worldpainter", name = "WPGUI", version = "2.26.1")
    runtimeOnly("us.dynmap:DynmapCoreAPI:local")
    compileOnly(group = "org.pepsoft.worldpainter", name = "WPCore", version = "2.26.1")
}