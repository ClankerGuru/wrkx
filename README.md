# wrkx

[![Build](https://github.com/ClankerGuru/wrkx/actions/workflows/build.yml/badge.svg)](https://github.com/ClankerGuru/wrkx/actions/workflows/build.yml)
[![Gradle Plugin](https://img.shields.io/badge/Gradle%20Plugin-0.37.0-blue)](https://github.com/ClankerGuru/wrkx)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.20-purple)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.4.1-green)](https://gradle.org)
[![Coverage](https://img.shields.io/badge/Coverage-%E2%89%A590%25-brightgreen)](https://github.com/ClankerGuru/wrkx)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow)](https://opensource.org/licenses/MIT)

**Multi-repository workspace management for Gradle.**

Work across multiple repos as if they were one project. Define your repositories in JSON, select which ones to work with today in the DSL, and let the plugin wire composite builds with dependency substitution. Changes to one repo are immediately visible in all others -- no publishing, no version bumps, no waiting.

## Why wrkx

When your codebase spans multiple repositories, development friction multiplies. You change a library, publish it, bump the version in the consuming app, wait for resolution, discover the change broke something, go back, fix it, publish again. wrkx eliminates this loop.

**Think of each workspace as an epic.** You're working on a feature that touches three repos -- the UI, a shared model library, and the host app. Create a workspace, list the three repos, enable them, and you have a single Gradle build where changes flow instantly between all three. Done with the epic? Disable the repos or switch to a different set. The JSON stays as your catalog; the DSL is your daily driver.

```text
workspace = epic
repos     = the modules that matter for this epic
branches  = all repos on the same working branch
```

## Quick start

A wrkx workspace needs three files:

### settings.gradle.kts

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("zone.clanker.gradle.wrkx") version "latest"
}

rootProject.name = "my-workspace"

wrkx {
    workingBranch = "feature/checkout-flow"
    enableAll()
}
```

### wrkx.json

```json
[
  {
    "name": "checkoutUi",
    "path": "git@github.com:MyOrg/checkout-ui.git",
    "baseBranch": "main",
    "category": "ui",
    "substitute": true,
    "substitutions": ["com.myorg:checkout-ui,:"]
  },
  {
    "name": "sharedModels",
    "path": "git@github.com:MyOrg/shared-models.git",
    "baseBranch": "main",
    "category": "core",
    "substitute": true,
    "substitutions": ["com.myorg:shared-models,:"]
  },
  {
    "name": "hostApp",
    "path": "git@github.com:MyOrg/host-app.git",
    "baseBranch": "develop",
    "category": "apps"
  }
]
```

### build.gradle.kts

```kotlin
plugins {
    base
}
```

### Then

```bash
./gradlew wrkx-clone       # clone all repos
./gradlew wrkx-checkout    # checkout feature/checkout-flow in all repos
./gradlew build             # host-app uses local checkoutUi and sharedModels
```

Changes to `sharedModels` are instantly visible in `checkoutUi` and `hostApp`. No publishing. No version bumps. Just code.

## wrkx.json format

| Field | Required | Default | Description |
|-------|----------|---------|-------------|
| `name` | yes | -- | Unique identifier. Must be a valid Kotlin identifier (camelCase). Used in DSL, tasks, and logs. |
| `path` | yes | -- | Git URL or local path. Any format `git clone` accepts (SSH, HTTPS, local). |
| `baseBranch` | no | `"main"` | The repo's default branch. Where `wrkx-pull` syncs from. |
| `category` | no | `""` | Grouping label for `wrkx-status` output. |
| `substitute` | no | `false` | Enable dependency substitution from local source. |
| `substitutions` | no | `[]` | Maven artifacts this repo provides: `"group:artifact,project"`. |

### Substitution format

```text
"com.myorg:shared-models,:"
 ─────────────────────────  ─
 Maven coordinate            Gradle project path (: = root project)
```

When a repo has `substitute: true`, Gradle resolves its artifacts from local source instead of Maven. Substitution is global -- all builds in the composite see it. Flip `substitute` to `false` to pull from Maven without editing the list.

For multi-module repos, use the subproject path:

```text
"com.myorg:checkout-ui,:checkout"    <- resolves from :checkout subproject
```

## DSL reference

The `wrkx { }` block controls which repos are active in your build:

```kotlin
wrkx {
    // Set a working branch for all enabled repos
    workingBranch = "feature/checkout-flow"

    // Enable everything from JSON
    enableAll()

    // Or: start clean, enable only what you need
    disableAll()
    enable(checkoutUi, sharedModels)

    // Per-repo access via bracket syntax
    this["hostApp"].enable(true)
}
```

| Method | Description |
|--------|-------------|
| `enableAll()` | Enable all repos from wrkx.json |
| `disableAll()` | Disable all repos |
| `enable(vararg repos)` | Enable specific repos |
| `this["name"]` | Access a repo by name for per-repo configuration |
| `workingBranch = "branch"` | Set working branch for `wrkx-checkout` |

### How enablement works

The plugin reads `wrkx.json` and registers all repos, but **does not include any as composite builds** until you enable them in the DSL. Only enabled repos get `settings.includeBuild()`. Tasks (clone, pull, checkout) work for all repos regardless of enablement.

If no `wrkx { }` block is present, no repos are included as composite builds. You must be explicit.

### Workflow examples

**Epic: new checkout flow** -- three repos, one branch:

```kotlin
wrkx {
    workingBranch = "feature/checkout-flow"
    disableAll()
    enable(checkoutUi, sharedModels, hostApp)
}
```

**Quick fix: patch a library** -- one repo, everything else from Maven:

```kotlin
wrkx {
    disableAll()
    enable(sharedModels)
}
```

**Explore: pull everything, build nothing locally**:

```kotlin
// No wrkx {} block -- repos are cloned but not included
// ./gradlew wrkx-clone still works
```

## Tasks

| Task | Description |
|------|-------------|
| `wrkx` | List all available workspace tasks |
| `wrkx-clone` | Clone all repos defined in wrkx.json |
| `wrkx-clone-<name>` | Clone a single repo from its remote |
| `wrkx-pull` | Pull baseBranch for all repos from their remotes |
| `wrkx-pull-<name>` | Pull baseBranch for a single repo |
| `wrkx-checkout` | Checkout workingBranch (or baseBranch) across all repos |
| `wrkx-checkout-<name>` | Checkout workingBranch (or baseBranch) for a single repo |
| `wrkx-status` | Generate workspace status report at `.wrkx/repos.md` |
| `wrkx-prune` | Remove repo directories not defined in wrkx.json |

```bash
./gradlew wrkx-clone           # clone all repos
./gradlew wrkx-clone-gort      # clone just gort
./gradlew wrkx-pull            # pull baseBranch for all repos
./gradlew wrkx-checkout        # checkout workingBranch or baseBranch
./gradlew wrkx-status          # generate workspace status report
./gradlew wrkx-prune           # remove orphaned repo directories
```

### Checkout behavior

- **With `workingBranch` set**: creates/checks out that branch from `baseBranch` in all enabled repos. Fails if working directory is dirty -- commit or stash first.
- **Without `workingBranch`**: checks out each repo's `baseBranch`.

### Pull behavior

- Fetches and fast-forward merges `origin/<baseBranch>` for each repo.
- Repos with no remote are skipped with a log message.

## How it works

1. Plugin reads `wrkx.json` at settings evaluation time
2. All repos are registered in a container (tasks work for all)
3. DSL runs: you enable/disable repos, set workingBranch
4. After settings evaluation, enabled repos get `settings.includeBuild()` with substitution wiring
5. Missing repo directories are warned, not failed -- so `wrkx-clone` works on fresh checkouts

Repos are cloned to a sibling directory:

```text
~/dev/
├── my-workspace/            <- has wrkx.json + settings.gradle.kts
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   └── wrkx.json
└── my-workspace-repos/      <- repos cloned here by wrkx-clone
    ├── checkout-ui/
    ├── shared-models/
    └── host-app/
```

## Known issues

### Kotlin Multiplatform composite builds

Gradle composite builds with dependency substitution **do not work** when the consumer project uses the Kotlin Multiplatform plugin. The Kotlin Gradle plugin's `KmpPartiallyResolvedDependenciesChecker` calls `Project#beforeEvaluate()` on included build projects during dependency resolution, which Gradle blocks at that lifecycle stage.

This affects **all current Kotlin versions** up to and including 2.4.0-Beta1. It is not a wrkx issue -- it reproduces with raw `settings.includeBuild()` and `--include-build` as well.

**What works:**
- JVM project → JVM included build (e.g. a Kotlin/JVM app consuming a Kotlin/JVM library via wrkx substitution)
- Clone, pull, checkout, status, prune tasks work for all project types

**What doesn't work:**
- KMP project → any included build with dependency substitution

Track the upstream fix at [KT-52172](https://youtrack.jetbrains.com/issue/KT-52172/Multiplatform-Support-composite-builds).

## Properties

| Property | Default | Description |
|----------|---------|-------------|
| `zone.clanker.wrkx.enabled` | `true` | Disable the plugin entirely |

## Install globally

```bash
bash install.sh
# or
curl -fsSL https://raw.githubusercontent.com/ClankerGuru/wrkx/main/install.sh | bash
# uninstall
bash install.sh --uninstall
```

Writes an init script to `~/.gradle/init.d/` so every Gradle project gets wrkx tasks.

---

## Contributing

### Requirements

- JDK 17+ (JetBrains Runtime recommended)
- Gradle 9.4.1 (included via wrapper)
- Docker (for integration tests with Testcontainers)

### Clone and set up

```bash
git clone git@github.com:ClankerGuru/wrkx.git
cd wrkx
git config core.hooksPath config/hooks
```

Git hooks enforce:
- **pre-commit**: runs `./gradlew build` (compile + test + detekt + ktlint + coverage)
- **pre-push**: blocks direct pushes to `main` (forces PRs)

### Build

```bash
./gradlew build
```

This single command runs everything:

| Step | Task | What it checks |
|------|------|---------------|
| Compile | `compileKotlin` | Kotlin source compiles |
| Detekt | `detekt` | Static analysis against `config/detekt.yml` |
| ktlint | `ktlintCheck` | Code formatting against `.editorconfig` |
| Unit tests | `test` | Model, task, and plugin behavior |
| Integration tests | `test` | Full lifecycle against Gitea in Testcontainers |
| Architecture tests | `slopTest` | Konsist: naming, packages, annotations, forbidden patterns |
| Coverage | `koverVerify` | Line coverage >= 90% enforced |
| Plugin validation | `validatePlugins` | Gradle plugin descriptor is valid |

### Common commands

```bash
./gradlew build                    # full build (everything)
./gradlew assemble                 # just compile
./gradlew test                     # unit + integration tests
./gradlew detekt                   # static analysis only
./gradlew ktlintCheck              # formatting check only
./gradlew ktlintFormat             # auto-fix formatting
./gradlew slopTest                 # architecture tests (Konsist)
./gradlew check                    # all verification tasks
./gradlew publishToMavenLocal      # publish to ~/.m2 for local testing
```

### Code coverage

Coverage is enforced at **90% minimum** line coverage via [Kover](https://github.com/Kotlin/kotlinx-kover).

```bash
# Check coverage threshold (fails if below 90%)
./gradlew koverVerify

# Print coverage summary to terminal
./gradlew koverLog

# Generate HTML report
./gradlew koverHtmlReport
open build/reports/kover/html/index.html

# Generate XML report (for CI integration)
./gradlew koverXmlReport
# output: build/reports/kover/report.xml
```

The plugin entry point (`Wrkx.SettingsPlugin`, `Wrkx.SettingsExtension`) is excluded from coverage because it runs in a separate JVM via Gradle TestKit and cannot be instrumented by Kover.

### Static analysis

**Detekt** runs with the configuration at `config/detekt.yml`:
- Max issues: 0 (zero tolerance)
- Warnings treated as errors
- Max line length: 120
- Cyclomatic complexity threshold: 15
- Nested block depth: 4
- Magic numbers enforced (except -1, 0, 1, 2)

```bash
./gradlew detekt
# report: build/reports/detekt/detekt.html
```

**ktlint** enforces formatting rules from `.editorconfig`:
- ktlint official style
- Trailing commas required
- 120 char line length
- No wildcard imports

```bash
./gradlew ktlintCheck              # check
./gradlew ktlintFormat             # auto-fix
```

### Architecture tests

Architecture is enforced via [Konsist](https://docs.konsist.lemonappdev.com/) in `src/slopTest/`:

| Test | Enforces |
|------|----------|
| `PackageBoundaryTest` | Models never import from tasks or reports. Reports never import from tasks. |
| `NamingConventionTest` | Task classes end with `Task`. Report classes end with `Renderer`. No generic suffixes (Helper, Manager, Util, etc.). |
| `TaskAnnotationTest` | Every task class has `@UntrackedTask` annotation. |
| `ForbiddenPackageTest` | No junk-drawer packages (utils, helpers, common, misc, shared, etc.). |
| `ForbiddenPatternTest` | No try-catch (use runCatching). No standalone constant files. No wildcard imports. |

```bash
./gradlew slopTest
# report: build/reports/tests/slopTest/index.html
```

### Test suites

**Unit tests** (`src/test/`) -- model serialization, value class validation, task behavior with local git repos. No Docker needed.

**Integration tests** (`src/test/`) -- full lifecycle against a Gitea server in Testcontainers: clone, pull, checkout, prune. Requires Docker. Skipped automatically when Docker is unavailable.

**Plugin tests** (`src/test/WrkxPluginTest.kt`) -- Gradle TestKit tests verifying:
- Plugin applies and creates extension
- `enableAll()`, `disableAll()`, `enable(repo)` DSL behavior
- `workingBranch` property
- Repos not enabled are not included as composite builds
- Missing repos warn instead of fail
- Empty wrkx.json creates default file

### Convention plugins (build-logic)

All build configuration is managed through precompiled script plugins:

| Plugin | Provides |
|--------|----------|
| `clkx-conventions` | Applies all conventions below |
| `clkx-module` | `java-library` + Kotlin JVM + JUnit Platform |
| `clkx-toolchain` | JDK toolchain configuration |
| `clkx-plugin` | `java-gradle-plugin` setup |
| `clkx-publish` | Maven Central publishing via Vanniktech |
| `clkx-serialization` | `kotlinx.serialization` plugin |
| `clkx-testing` | Kotest + Testcontainers + Kover + Konsist + slopTest source set |
| `clkx-detekt` | Detekt static analysis with `config/detekt.yml` |
| `clkx-ktlint` | ktlint formatting with `.editorconfig` rules |

The main `build.gradle.kts` is one line:

```kotlin
plugins {
    id("clkx-conventions")
}
```

### Project structure

```text
wrkx/
├── .github/workflows/
│   └── build.yml                <- CI: build + test on push/PR to main
├── config/
│   ├── detekt.yml               <- Detekt static analysis rules
│   └── hooks/
│       ├── pre-commit           <- Runs ./gradlew build before every commit
│       └── pre-push             <- Blocks direct push to main
├── build-logic/                 <- Convention plugins (clkx-*)
│   ├── build.gradle.kts         <- Plugin dependencies
│   ├── settings.gradle.kts
│   └── src/main/kotlin/         <- 9 convention plugin scripts
├── src/
│   ├── main/kotlin/zone/clanker/gradle/wrkx/
│   │   ├── Wrkx.kt             <- SettingsPlugin + SettingsExtension + constants
│   │   ├── WrkxDsl.kt          <- Settings.wrkx {} type-safe extension function
│   │   ├── model/
│   │   │   ├── ArtifactId.kt           <- Maven group:artifact value class
│   │   │   ├── ArtifactSubstitution.kt <- artifact-to-project mapping
│   │   │   ├── GitReference.kt         <- Branch/tag/SHA value class
│   │   │   ├── ProjectPath.kt          <- Gradle project path value class
│   │   │   ├── RepositoryEntry.kt      <- JSON deserialization model
│   │   │   ├── RepositoryUrl.kt        <- Git URL value class
│   │   │   └── WorkspaceRepository.kt  <- Gradle-managed repo in the container
│   │   ├── report/
│   │   │   └── ReposCatalogRenderer.kt <- Markdown report builder for wrkx-status
│   │   └── task/
│   │       ├── CheckoutTask.kt  <- git checkout per repo
│   │       ├── CloneTask.kt     <- git clone per repo
│   │       ├── PruneTask.kt     <- remove orphaned repo directories
│   │       ├── PullTask.kt      <- git fetch + merge per repo
│   │       └── StatusTask.kt    <- generate .wrkx/repos.md
│   ├── test/kotlin/             <- Unit + integration + plugin tests (Kotest BDD)
│   └── slopTest/kotlin/         <- Architecture tests (Konsist)
│       ├── PackageBoundaryTest.kt
│       ├── NamingConventionTest.kt
│       ├── TaskAnnotationTest.kt
│       ├── ForbiddenPackageTest.kt
│       └── ForbiddenPatternTest.kt
├── .editorconfig                <- ktlint + editor formatting rules
├── build.gradle.kts             <- One line: id("clkx-conventions")
├── settings.gradle.kts          <- Three lines: build-logic, clkx-settings, root name
├── gradle.properties            <- Version, Maven coordinates, POM metadata
└── install.sh                   <- Global installer via Gradle init script
```

## License

[MIT](LICENSE)
