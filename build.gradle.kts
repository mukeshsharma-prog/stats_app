// Stats App — root build.
//
// Holds only configuration that is genuinely common to every module. Anything
// module-specific belongs in that module's own build file.
//
// The Spring Boot plugin is declared here but applied nowhere at the root: only
// the three application modules apply it, because only they produce an executable
// image. stats-common is a plain library.

plugins {
    alias(libs.plugins.spring.boot) apply false
}

// The generated `libs` accessor resolves against the project whose scope it is
// evaluated in, so inside `subprojects { }` it would look for a `libs` extension
// on each SUBPROJECT and fail. Capture the root catalog here and use this.
val catalog = libs

allprojects {
    group = "com.zenner.datahub.stats"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    // Java 21 — CONFIRMED, not 17. Pinned via a toolchain so the build does not
    // depend on whatever JDK happens to be on the developer's PATH.
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(catalog.versions.java.get()))
        }
    }

    dependencies {
        // Every module imports the Boot BOM, which is what makes the version-less
        // entries in libs.versions.toml resolve. Module build files declare WHAT
        // they need; this line decides WHICH VERSION.
        "implementation"(platform(catalog.spring.boot.bom))
        "testImplementation"(platform(catalog.spring.boot.bom))
        // annotationProcessor needs the platform too, and it is easy to miss: it is a
        // SEPARATE configuration, so a version-less catalog entry used there resolves
        // against nothing. The failure is quiet in the worst way — the processor simply
        // does not run, no metadata is generated, and the only symptom is an IDE that
        // still calls every custom property unknown.
        "annotationProcessor"(platform(catalog.spring.boot.bom))

        "testImplementation"(catalog.spring.boot.starter.test)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
