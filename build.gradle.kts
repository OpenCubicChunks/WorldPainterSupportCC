plugins {
    java
    maven
}

group = "io.github.opencubicchunks"
version = "1.2.0-WP2.7.0"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenLocal()
    maven { setUrl("https://oss.sonatype.org/content/repositories/public/") }
    maven { setUrl("http://repo.maven.apache.org/maven2") }
}

dependencies {
    implementation(group = "io.github.opencubicchunks", name = "regionlib", version = "0.57.0-SNAPSHOT")
    implementation(group = "com.carrotsearch", name = "hppc", version = "0.8.1")
    runtimeOnly(group = "org.pepsoft.worldpainter", name = "WPGUI", version = "2.7.0") {
        exclude(module = "WPDynmapPreviewer")
    }
    compileOnly(group = "org.pepsoft.worldpainter", name = "WPCore", version = "2.7.0")
}
