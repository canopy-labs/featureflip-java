plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "featureflip-java"

// Integration modules. They ship from the same tag as the core, at the same version,
// and depend on it through a project dependency (the published POMs name the core by
// its Maven coordinates).
include("featureflip-spring-boot-starter")
include("featureflip-micronaut")
