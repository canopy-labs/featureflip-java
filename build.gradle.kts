plugins {
    `java-library`
    `maven-publish`
    signing
    id("net.thebugmc.gradle.sonatype-central-portal-publisher") version "1.2.4"
}

group = "io.featureflip"
version = "2.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.slf4j:slf4j-api:2.0.17")

    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-sse:5.3.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.squareup.okhttp3:mockwebserver3-junit5:5.3.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}

centralPortal {
    username = System.getenv("MAVEN_USERNAME") ?: ""
    password = System.getenv("MAVEN_PASSWORD") ?: ""

    pom {
        name.set("Featureflip Java SDK")
        description.set("Java SDK for Featureflip - a feature flag SaaS platform")
        url.set("https://github.com/canopy-labs/featureflip-java")

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
