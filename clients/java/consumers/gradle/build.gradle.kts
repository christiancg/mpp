plugins {
    java
}

val mppVersion: String = providers.gradleProperty("mppVersion").getOrElse("0.1.0-SNAPSHOT")

repositories {
    mavenLocal()
    mavenCentral()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

dependencies {
    implementation(platform("io.github.christiancg:mpp-client-bom:$mppVersion"))
    implementation("io.github.christiancg:mpp-client")
}
