# WRKX skills

These Agent Skills are the recommended operational guide for WRKX. Start with the plugin overview, then load the skill
whose name matches the Gradle task you need to run.

| Skill | Use it for |
|---|---|
| [`wrkx`](wrkx/SKILL.md) | Install and configure the plugin, choose repositories, and inspect task help |
| [`wrkx-clone`](wrkx-clone/SKILL.md) | Create or refresh shared bare repositories |
| [`wrkx-fetch`](wrkx-fetch/SKILL.md) | Fetch and prune remote references without creating worktrees |
| [`wrkx-worktree`](wrkx-worktree/SKILL.md) | Create branch-scoped worktrees for enabled repositories |
| [`wrkx-checkout`](wrkx-checkout/SKILL.md) | Use the compatibility alias for worktree creation |
| [`wrkx-pull`](wrkx-pull/SKILL.md) | Merge each repository's base branch into a clean selected worktree |
| [`wrkx-worktree-delete`](wrkx-worktree-delete/SKILL.md) | Safely remove one selected branch's managed worktrees |
| [`wrkx-status`](wrkx-status/SKILL.md) | Generate and interpret `.wrkx/repos.md` |
| [`wrkx-prune`](wrkx-prune/SKILL.md) | Remove clean, merged worktrees after their remote branches disappear |

Each lifecycle skill covers both the aggregate task and its generated per-repository `-<repo>` variant. `<repo>` is
the sanitized Git URL directory name, such as `shared-models` for `shared-models.git`; confirm exact names with
`./gradlew tasks --group wrkx`. Run commands from the workspace root, where `settings.gradle.kts` and `wrkx.json` are
located.
