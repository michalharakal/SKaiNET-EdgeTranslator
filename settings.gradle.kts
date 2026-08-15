rootProject.name = "EdgeTranslator"

pluginManagement {
    repositories {
        mavenLocal()
        google {
            content { 
              	includeGroupByRegex("com\\.android.*")
              	includeGroupByRegex("com\\.google.*")
              	includeGroupByRegex("androidx.*")
              	includeGroupByRegex("android.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google {
            content { 
              	includeGroupByRegex("com\\.android.*")
              	includeGroupByRegex("com\\.google.*")
              	includeGroupByRegex("androidx.*")
              	includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
    }
}
include(":shared")
include(":androidApp")
include(":desktopApp")

// Composite-build substitution for iterating on local SKaiNET / SKaiNET-transformers source
// without a publishToMavenLocal round-trip (source changes are picked up on the next build).
// Mirrors SKaiNET-transformers' own settings.gradle.kts convention exactly. Off by default;
// opt in per-clone with -PuseLocalSkainet=true / -PuseLocalSkainetTransformers=true, or set them
// in gradle.properties. Sibling checkouts, not the project's own subdirectory.
//
// FIXED (was a KNOWN LIMITATION): -PuseLocalSkainetTransformers=true now always also includes
// ../SKaiNET, even if -PuseLocalSkainet wasn't passed separately — SKaiNET-transformers depends
// on sk.ainet.core internally, so substituting it without also substituting its own core
// dependency is never actually useful. It also no longer relies purely on nested
// `includeBuild("../SKaiNET")` duplication (SKaiNET-transformers' own settings.gradle.kts now
// guards that behind `gradle.parent == null`, so it doesn't double-declare when nested here).
//
// Two root causes, both fixed:
// 1. The nested double-inclusion above (harmless once guarded, but was worth cleaning up).
// 2. The REAL reason sk.ainet.transformers:* never substituted: Gradle's automatic
//    project-substitution for included builds matches on each subproject's `group:name`
//    (the Gradle project path's own name), not its published Maven artifactId. SKaiNET's own
//    project paths happen to equal their artifact names (e.g. `:skainet-lang:skainet-lang-core`
//    IS `skainet-lang-core`), so `-PuseLocalSkainet=true` alone worked by accident. SKaiNET-
//    transformers' project paths do NOT (e.g. `:llm-core` publishes as
//    `skainet-transformers-core` via its own `POM_ARTIFACT_ID`, not `llm-core`) — automatic
//    matching can never bridge that, so it silently fell through to (or, after fixing #1 above,
//    explicitly failed to find) plain external artifacts. Fixed with explicit
//    `dependencySubstitution` rules below, keyed off each module's actual `POM_ARTIFACT_ID`.
if (providers.gradleProperty("useLocalSkainet").orNull == "true" ||
    providers.gradleProperty("useLocalSkainetTransformers").orNull == "true"
) {
    includeBuild("../SKaiNET")
}
if (providers.gradleProperty("useLocalSkainetTransformers").orNull == "true") {
    includeBuild("../SKaiNET-transformers") {
        dependencySubstitution {
            substitute(module("sk.ainet.transformers:skainet-transformers-bom")).using(project(":llm-bom"))
            substitute(module("sk.ainet.transformers:skainet-transformers-core")).using(project(":llm-core"))
            substitute(module("sk.ainet.transformers:skainet-transformers-agent")).using(project(":llm-agent"))
            substitute(module("sk.ainet.transformers:skainet-transformers-runtime-kllama")).using(project(":llm-runtime:kllama"))
            substitute(module("sk.ainet.transformers:skainet-transformers-inference-llama")).using(project(":llm-inference:llama"))
            // Not yet consumed by EdgeTranslator (SKaiNET-Gemma work in progress), added
            // ahead of need so the composite build substitutes them the moment they are.
            substitute(module("sk.ainet.transformers:skainet-transformers-runtime-kgemma")).using(project(":llm-runtime:kgemma"))
            substitute(module("sk.ainet.transformers:skainet-transformers-inference-gemma")).using(project(":llm-inference:gemma"))
        }
    }
}

