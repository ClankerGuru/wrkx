---
name: wrkx
description: Use when installing or configuring the WRKX Gradle settings plugin, selecting repositories, defining branches and substitutions, or choosing the correct WRKX task.
---

# WRKX workspace setup

## When to use

- Creating a Gradle workspace that combines multiple repositories.
- Changing which repositories participate in the composite build.
- Configuring a shared working branch or per-repository base branch.
- Choosing a lifecycle task before changing local Git state.

## Setup

Apply WRKX in the workspace root `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("zone.clanker.gradle.wrkx") version "<version>"
}

rootProject.name = "my-workspace"

wrkx {
    workingBranch = "feature/checkout-flow"
    enable(sharedModels)
}
```

Replace `<version>` with a published WRKX version from Maven Central.

Define the catalog in root `wrkx.json`:

```json
[
  {
    "name": "sharedModels",
    "path": "git@github.com:MyOrg/shared-models.git",
    "baseBranch": "main",
    "categories": ["checkout", "core"],
    "substitute": true,
    "substitutions": ["com.myorg:shared-models,:"]
  }
]
```

Use `enableAll()`, `disableAll()`, `enable(repoA, repoB)`, or `repo.enable()` to control included builds. Categories are
status metadata, not an enablement selector. Configure one repository inline with
`enable(repo { baseBranch = "release/next" })`.

## Task choice

| Goal | Task |
|---|---|
| Show WRKX task help | `./gradlew wrkx` |
| Bootstrap every bare repository | `./gradlew wrkx-clone` |
| Refresh remote references only | `./gradlew wrkx-fetch` |
| Create the selected branch worktrees | `./gradlew wrkx-worktree` |
| Merge base branches into clean worktrees | `./gradlew wrkx-pull` |
| Inspect configured repository state | `./gradlew wrkx-status` |
| Delete the selected branch worktrees | `./gradlew wrkx-worktree-delete` |
| Prune merged worktrees after remote deletion | `./gradlew wrkx-prune` |

## Scope rules

- `wrkx-clone`, `wrkx-fetch`, `wrkx-status`, `wrkx-worktree-delete`, and `wrkx-prune` process the full catalog.
- `wrkx-worktree`, `wrkx-checkout`, and `wrkx-pull` aggregate tasks process enabled repositories only.
- Every generated `-<repo>` task is an explicit override and works regardless of repository enablement.
- `<repo>` is derived from the sanitized Git URL directory name, not necessarily the JSON `name`; list exact tasks with
  `./gradlew tasks --group wrkx`.
- `-Pwrkx.branch=<branch>` overrides `workingBranch` for that invocation.
- Aggregate lifecycle operations run repositories independently in a four-thread pool. A failure does not roll back
  repositories that already succeeded; inspect every result before retrying.

## Safety rules

- Run tasks from the workspace root; do not manually move WRKX-managed bare repositories or worktrees.
- WRKX never pushes, force-pushes, or deletes remote branches.
- Worktrees live beside the workspace under `<workspace-name>-repos/`; they are not ordinary standalone clones.
- Create missing worktrees in a separate Gradle invocation before syncing or building the composite.
