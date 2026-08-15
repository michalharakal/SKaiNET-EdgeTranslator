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
// KNOWN LIMITATION (confirmed via `:shared:dependencies`): -PuseLocalSkainet=true alone reliably
// substitutes sk.ainet.core:* with real `project :SKaiNET:...` references. Passing BOTH flags
// together does not reliably substitute sk.ainet.transformers:* the same way (modules still
// resolve as plain external versions) — likely because SKaiNET-transformers' own
// settings.gradle.kts *also* does `includeBuild("../SKaiNET")` when -PuseLocalSkainet=true is
// set, creating a nested/duplicate include of the same physical directory. Not yet root-caused.
// Until fixed, prefer developing SKaiNET-transformers changes directly against a local SKaiNET
// composite build *inside the SKaiNET-transformers checkout itself* (its own -PuseLocalSkainet
// convention, proven reliable — see SKaiNET-transformers' GEMMA4_E2B_SKAINET_FINDINGS.md), then
// bring a finished fix into EdgeTranslator via a real published version, rather than relying on
// this repo's own -PuseLocalSkainetTransformers for live iteration.
if (providers.gradleProperty("useLocalSkainet").orNull == "true") {
    includeBuild("../SKaiNET")
}
if (providers.gradleProperty("useLocalSkainetTransformers").orNull == "true") {
    includeBuild("../SKaiNET-transformers")
}

