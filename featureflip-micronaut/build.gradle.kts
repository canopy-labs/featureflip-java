plugins {
    `java-library`
    `maven-publish`
    signing
    id("net.thebugmc.gradle.sonatype-central-portal-publisher")
}

group = "io.featureflip"

val micronautTestVersion: String? = providers.gradleProperty("micronautTestVersion").orNull
val testJavaVersion: Int? = providers.gradleProperty("testJavaVersion").orNull?.toInt()
// One version line for all three artifacts. publish-java-sdk.yml rewrites the root's
// `version = "..."` from the release tag; this follows it.
version = rootProject.version

java {
    toolchain {
        // Micronaut 4 needs Java 17. The core stays on 11; a 17 consumer of an
        // 11 jar is fine.
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // Published in the POM as io.featureflip:featureflip-java:<same version>, compile scope:
    // the factory's public bean type is FeatureflipClient.
    api(project(":"))

    // Micronaut is compile-only: the application already brings its own, and this
    // module is only ever added to one.
    compileOnly(platform("io.micronaut.platform:micronaut-platform:4.10.24"))
    compileOnly("io.micronaut:micronaut-inject")
    annotationProcessor(platform("io.micronaut.platform:micronaut-platform:4.10.24"))
    annotationProcessor("io.micronaut:micronaut-inject-java")

    // The tests run against the floor above unless java-sdk-ci's framework-compat job
    // passes -PmicronautTestVersion=<newest 5.x> (with -PtestJavaVersion=25, which
    // Micronaut 5 needs). main stays compiled against the floor either way, so that job
    // proves the jar we publish loads on the newer major.
    testImplementation(platform(micronautTestVersion?.let { "io.micronaut.platform:micronaut-platform:$it" }
        ?: "io.micronaut.platform:micronaut-platform:4.10.24"))
    testImplementation("io.micronaut:micronaut-inject")
    testImplementation("io.micronaut:micronaut-runtime")
    testAnnotationProcessor(platform(micronautTestVersion?.let { "io.micronaut.platform:micronaut-platform:$it" }
        ?: "io.micronaut.platform:micronaut-platform:4.10.24"))
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("com.squareup.okhttp3:mockwebserver3-junit5:5.5.1")
    testImplementation("org.slf4j:slf4j-simple:2.0.20")
}

tasks.withType<JavaCompile>().configureEach {
    // `-processing` only: an annotation processor that claims some of the annotations it
    // sees (the metadata processor, here) makes javac warn about the rest, which is
    // expected and not a defect. Every other lint category stays on.
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testJavaVersion?.let { v -> javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(v)) }) }
}

testJavaVersion?.let { v ->
    tasks.named<JavaCompile>("compileTestJava") {
        javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(v)) })
    }
}

centralPortal {
    // Defaults to the root project's name, which would publish this over the core.
    name = project.name
    username = System.getenv("MAVEN_USERNAME") ?: ""
    password = System.getenv("MAVEN_PASSWORD") ?: ""

    pom {
        name.set("Featureflip Micronaut Integration")
        description.set("Micronaut factory and configuration for the Featureflip Java SDK")
        url.set("https://featureflip.io")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                name.set("Featureflip Team")
                organization.set("Canopy Labs LLC")
                organizationUrl.set("https://featureflip.io")
            }
        }

        scm {
            url.set("https://github.com/canopy-labs/featureflip-java")
        }
    }
}

signing {
    val signingKey = findProperty("signing.key") as String? ?: System.getenv("GPG_PRIVATE_KEY")
    val signingPassword = findProperty("signing.password") as String? ?: System.getenv("GPG_PASSPHRASE")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}

// Upload the core first when one invocation publishes everything, so the artifact this
// module's POM names is the first to reach Central.
tasks.matching { it.name == "publishToCentralPortal" }.configureEach {
    mustRunAfter(":publishToCentralPortal")
}
