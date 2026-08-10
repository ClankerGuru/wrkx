package zone.clanker.gradle.wrkx

import kotlinx.serialization.json.Json
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.BuildLayout
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import zone.clanker.gradle.wrkx.model.GitReference
import zone.clanker.gradle.wrkx.model.RepositoryEntry
import zone.clanker.gradle.wrkx.model.WorkspaceLayout
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import zone.clanker.gradle.wrkx.task.CheckoutTask
import zone.clanker.gradle.wrkx.task.CloneTask
import zone.clanker.gradle.wrkx.task.GitOperations
import zone.clanker.gradle.wrkx.task.PruneTask
import zone.clanker.gradle.wrkx.task.PullTask
import zone.clanker.gradle.wrkx.task.StatusTask
import java.io.File
import javax.inject.Inject

/**
 * Root identity object for the wrkx workspace plugin.
 *
 * Contains all constants, the [SettingsExtension] DSL, and the [SettingsPlugin] entry point.
 * Tasks and repos reference [Wrkx.GROUP], [Wrkx.TASK_CLONE], etc.
 *
 * ```kotlin
 * // Access constants:
 * Wrkx.GROUP          // "wrkx"
 * Wrkx.TASK_CLONE     // "wrkx-clone"
 * Wrkx.TASK_STATUS    // "wrkx-status"
 * ```
 */
data object Wrkx {
    /** Gradle task group name for all wrkx tasks. */
    const val GROUP = "wrkx"

    /** Name of the DSL extension registered on Settings. */
    const val EXTENSION_NAME = "wrkx"

    /** JSON configuration file that defines workspace repos. */
    const val CONFIG_FILE = "wrkx.json"

    /** Output directory for generated reports. */
    const val OUTPUT_DIR = ".wrkx"

    /** Gradle property to disable the plugin entirely. */
    const val ENABLED_PROP = "zone.clanker.wrkx.enabled"

    /** Task name: list all available workspace tasks. */
    const val TASK_CATALOG = "wrkx"

    /** Task name: clone all repos defined in [CONFIG_FILE]. */
    const val TASK_CLONE = "wrkx-clone"

    /** Task name: fetch all branches and prune deleted remote references in existing bare repos. */
    const val TASK_FETCH = "wrkx-fetch"

    /** Task name: fetch all bare repos and merge base branches into selected worktrees. */
    const val TASK_PULL = "wrkx-pull"

    /** Task name: compatibility alias for creating selected branch worktrees. */
    const val TASK_CHECKOUT = "wrkx-checkout"

    /** Task name: create branch-scoped worktrees backed by bare repositories. */
    const val TASK_WORKTREE = "wrkx-worktree"

    /** Gradle property that overrides the branch configured in the DSL. */
    const val BRANCH_PROP = "wrkx.branch"

    /** Task name: generate workspace status report at [OUTPUT_DIR]/repos.md. */
    const val TASK_STATUS = "wrkx-status"

    /** Task name: remove clean, merged worktrees after their observed remote branch is deleted. */
    const val TASK_PRUNE = "wrkx-prune"

    private const val CATALOG_DIVIDER_LENGTH = 40

    /**
     * DSL extension registered as `wrkx { }` on the Settings object.
     *
     * Owns the [repos] container and the enable/disable DSL that controls
     * which repos are included as composite builds.
     *
     * ```kotlin
     * wrkx {
     *     workingBranch = "feature/new-catalog"
     *     disableAll()
     *     enable(repos["gort"], repos["coreModels"])
     * }
     * ```
     *
     * @see SettingsPlugin
     * @see WorkspaceRepository
     */
    abstract class SettingsExtension
        @Inject
        constructor(
            private val settings: Settings,
            private val objects: ObjectFactory,
            private val providers: ProviderFactory,
        ) {
            private val logger = Logging.getLogger(SettingsExtension::class.java)

            /** Base directory where repos are cloned (sibling to the project). */
            abstract val baseDir: DirectoryProperty

            /** Branch to checkout for enabled repos when running wrkx-checkout. */
            var workingBranch: String? = null

            private val extraBranchPrefixes = linkedSetOf<String>()

            internal val allowedBranchPrefixes: Set<String>
                get() = WorkspaceLayout.defaultBranchPrefixes + extraBranchPrefixes

            internal val workspaceRootNames: Set<String>
                get() = allowedBranchPrefixes + WorkspaceLayout.standaloneBranches + "bare"

            /** Allow additional branch prefixes without removing the built-in prefixes. */
            fun allowBranchPrefixes(vararg prefixes: String) {
                prefixes.forEach(WorkspaceLayout::validatePrefix)
                extraBranchPrefixes.addAll(prefixes)
            }

            /**
             * Container of all [WorkspaceRepository] entries loaded from [CONFIG_FILE].
             *
             * Repos are registered here during plugin application and can be
             * enabled/disabled from the DSL.
             */
            val repos: NamedDomainObjectContainer<WorkspaceRepository> =
                objects.domainObjectContainer(WorkspaceRepository::class.java) { name ->
                    objects.newInstance(WorkspaceRepository::class.java, name).apply {
                        substitute.convention(false)
                        baseBranch.convention(GitReference("main"))
                        categories.convention(emptyList())
                        @Suppress("DEPRECATION")
                        category.convention("")
                    }
                }

            /**
             * Configure repos using an [Action] block.
             *
             * ```kotlin
             * wrkx {
             *     repos {
             *         getByName("gort").enable(true)
             *     }
             * }
             * ```
             *
             * @param action configuration action applied to the repos container
             */
            fun repos(action: Action<NamedDomainObjectContainer<WorkspaceRepository>>) {
                action.execute(repos)
            }

            /**
             * Enable all registered repos for composite build inclusion.
             *
             * ```kotlin
             * wrkx {
             *     enableAll()
             * }
             * ```
             */
            fun enableAll() {
                repos.forEach {
                    it.enable(true)
                }
            }

            /**
             * Disable all registered repos for composite build inclusion.
             *
             * ```kotlin
             * wrkx {
             *     disableAll()
             *     enable("gort")  // then selectively re-enable
             * }
             * ```
             */
            fun disableAll() {
                repos.forEach { it.enable(false) }
            }

            /**
             * Enable specific repos by reference for composite build inclusion.
             *
             * Repos are included as composite builds after the settings DSL finishes,
             * so branch and enablement configuration are fully resolved first.
             *
             * ```kotlin
             * wrkx {
             *     enable(repos.getByName("gort"), repos.getByName("coreModels"))
             * }
             * ```
             *
             * @param repositories the repos to enable
             */
            fun enable(vararg repositories: WorkspaceRepository) {
                repositories.forEach {
                    it.enable(true)
                }
            }

            internal fun activeBranch(): String? {
                val branch =
                    providers
                        .gradleProperty(BRANCH_PROP)
                        .orNull
                        ?.takeIf { it.isNotEmpty() }
                        ?: workingBranch?.takeIf { it.isNotEmpty() }
                branch?.let { WorkspaceLayout.branchDirectory(it, allowedBranchPrefixes) }
                return branch
            }

            internal fun checkoutPath(repo: WorkspaceRepository): File? {
                val branch = activeBranch()
                return when {
                    branch != null && baseDir.isPresent ->
                        WorkspaceLayout.worktree(baseDir.asFile.get(), branch, repo, allowedBranchPrefixes)
                    repo.clonePath.isPresent -> repo.clonePath.asFile.get()
                    else -> null
                }
            }

            /**
             * Access a repo by name. Supports bracket syntax.
             *
             * ```kotlin
             * wrkx {
             *     this["turbine"].enable(true)
             * }
             * ```
             *
             * @param name the repo name to look up
             * @return the [WorkspaceRepository] with the given name
             * @throws IllegalStateException if the name is not found
             */
            operator fun get(name: String): WorkspaceRepository =
                repos.findByName(name)
                    ?: error("Repository '$name' not found in ${CONFIG_FILE}.")

            private val includedBuilds = mutableSetOf<String>()

            /**
             * Include only enabled repos as composite builds.
             * Acts as a safety net for repos enabled via [WorkspaceRepository.enable]
             * directly (not through [enable] or [enableAll]).
             */
            internal fun includeEnabled() {
                val enabledRepos = repos.filter { it.enabled }
                checkForDuplicateBuildNames(enabledRepos)
                enabledRepos.forEach { includeRepo(it) }
            }

            /**
             * Validate that no two enabled repos produce the same sanitized build name.
             *
             * @param repos the list of enabled repos to check
             * @throws IllegalStateException if duplicates are found
             */
            internal fun checkForDuplicateBuildNames(repos: List<WorkspaceRepository>) {
                val byName = repos.groupBy { it.sanitizedBuildName }
                val dupes = byName.filter { it.value.size > 1 }
                check(dupes.isEmpty()) {
                    val details =
                        dupes.entries.joinToString("\n") { (name, colliding) ->
                            "  '$name' <- ${colliding.joinToString(", ") { it.repoName }}"
                        }
                    """
                    Duplicate sanitized build names detected -- Gradle requires unique build names:
                    $details
                    """.trimIndent()
                }
            }

            internal fun includeRepo(repo: WorkspaceRepository) {
                if (!includedBuilds.add(repo.repoName)) return

                val cloneDir = checkoutPath(repo)?.canonicalFile ?: return
                if (!cloneDir.exists()) {
                    val task = if (activeBranch() == null) "$TASK_CLONE-${repo.sanitizedBuildName}" else TASK_WORKTREE
                    logger.warn(
                        "wrkx: Repository '${repo.repoName}' not cloned at ${cloneDir.absolutePath}. " +
                            "Run './gradlew $task' to create it.",
                    )
                    return
                }

                settings.includeBuild(cloneDir) { spec ->
                    spec.name = repo.sanitizedBuildName
                    if (repo.substitute.get() && repo.substitutions.get().isNotEmpty()) {
                        spec.dependencySubstitution { sub ->
                            repo.substitutions.get().forEach { s ->
                                sub
                                    .substitute(sub.module(s.artifact.value))
                                    .using(sub.project(s.project.gradlePath))
                            }
                        }
                    }
                }
            }
        }

    /**
     * Settings plugin entry point: `id("zone.clanker.gradle.wrkx")`.
     *
     * Sequence:
     * 1. Check if disabled or already applied
     * 2. Create the [SettingsExtension] and register it on Settings
     * 3. Read [CONFIG_FILE] and populate repos from JSON
     * 4. Use `settingsEvaluated` callback to include enabled repos AFTER DSL runs
     * 5. Register per-repo and lifecycle Gradle tasks
     *
     * ```kotlin
     * // settings.gradle.kts
     * plugins {
     *     id("zone.clanker.gradle.wrkx") version "0.36.0"
     * }
     * ```
     *
     * @see SettingsExtension
     */
    @Suppress("UnstableApiUsage")
    abstract class SettingsPlugin
        @Inject
        constructor(
            private val providers: ProviderFactory,
            private val layout: BuildLayout,
        ) : Plugin<Settings> {
            private val logger = Logging.getLogger(SettingsPlugin::class.java)

            override fun apply(settings: Settings) {
                if (isDisabled()) return
                if (isAlreadyApplied(settings)) return

                val repoDir = resolveRepoDir()
                val extension = createExtension(settings, repoDir)

                populateFromConfig(extension, repoDir)

                settings.gradle.settingsEvaluated {
                    extension.includeEnabled()
                }

                settings.gradle.rootProject(
                    Action { project ->
                        project.registerCatalogTask()
                        project.registerPerRepoTasks(extension, repoDir)
                        project.registerLifecycleTasks(extension, repoDir)
                        project.registerUtilityTasks(extension, repoDir)
                    },
                )
            }

            internal fun isDisabled(): Boolean =
                providers.gradleProperty(ENABLED_PROP).orNull?.lowercase() == "false"

            internal fun isAlreadyApplied(settings: Settings): Boolean =
                settings.extensions.findByType(SettingsExtension::class.java) != null

            internal fun resolveRepoDir(): File {
                val settingsDir = layout.settingsDirectory.asFile
                return File(settingsDir.parentFile ?: settingsDir, "${settingsDir.name}-repos")
            }

            internal fun createExtension(settings: Settings, repoDir: File): SettingsExtension {
                val extension =
                    settings.extensions.create(EXTENSION_NAME, SettingsExtension::class.java, settings)
                extension.baseDir.set(repoDir)
                return extension
            }

            internal val json = Json { ignoreUnknownKeys = true }

            internal fun populateFromConfig(extension: SettingsExtension, repoDir: File) {
                val configFile = layout.settingsDirectory.file(CONFIG_FILE).asFile
                if (!configFile.exists()) {
                    configFile.writeText("[]\n")
                    logger.lifecycle(
                        """
                        wrkx: Created empty $CONFIG_FILE at ${configFile.absolutePath}.
                        Add repositories to this file to manage your workspace. Example:
                        [
                          {
                            "name": "gort",
                            "path": "git@github.com:org/repo.git",
                            "baseBranch": "main",
                            "categories": ["libraries"],
                            "substitute": true,
                            "substitutions": ["com.example:lib,lib"]
                          }
                        ]
                        """.trimIndent(),
                    )
                    return
                }

                val configText = configFile.readText().trim()
                if (configText.isBlank() || configText == "[]") return

                val extAware = extension as org.gradle.api.plugins.ExtensionAware

                json
                    .decodeFromString<List<RepositoryEntry>>(configText)
                    .forEach { entry ->
                        @Suppress("DEPRECATION")
                        if (entry.category.isNotBlank()) {
                            logger.warn(
                                "wrkx: Repository '${entry.name}' uses deprecated JSON field 'category'. " +
                                    "Replace it with \"categories\": [\"${entry.category}\"].",
                            )
                        }
                        extension.repos.register(entry.name) { repo ->
                            repo.path.set(entry.path)
                            repo.categories.set(
                                entry.categories
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                                    .distinct(),
                            )
                            @Suppress("DEPRECATION")
                            repo.category.set(entry.category)
                            repo.substitutions.set(entry.substitutions)
                            repo.substitute.set(entry.substitute)
                            repo.baseBranch.set(entry.baseBranch)
                            repo.clonePath.set(File(repoDir, entry.directoryName))
                        }
                        val repo = extension.repos.getByName(entry.name)
                        extAware.extensions.add(
                            WorkspaceRepository::class.java,
                            entry.name,
                            repo,
                        )
                    }
            }

            internal fun Project.registerCatalogTask() {
                if (tasks.findByName(TASK_CATALOG) != null) return

                tasks.register(TASK_CATALOG).configure { task ->
                    task.group = GROUP
                    task.description = "List all available workspace tasks"
                    task.doLast {
                        logger.lifecycle(
                            """
                        |
                        |Workspace Tasks ($GROUP)
                        |${"=".repeat(CATALOG_DIVIDER_LENGTH)}
                        |
                        |  $TASK_CLONE       Create or fetch shared bare repositories; no worktrees are changed
                        |  $TASK_FETCH       Fetch all branches and prune deleted remote references
                        |  $TASK_PULL        Fetch remotes and merge base branches into selected clean worktrees
                        |  $TASK_CHECKOUT    Compatibility alias for $TASK_WORKTREE
                        |  $TASK_WORKTREE    Create or reuse worktrees for -P$BRANCH_PROP or workingBranch
                        |  $TASK_STATUS      Write bare repository, category, enablement, and substitution status
                        |  $TASK_PRUNE       Remove clean merged worktrees whose observed remote branch was deleted
                        |
                        |Run any task:  ./gradlew <task-name>
                        |Full details:  ./gradlew help --task <task-name>
                        |
                            """.trimMargin(),
                        )
                    }
                }
            }

            @Suppress("LongMethod")
            internal fun Project.registerPerRepoTasks(
                extension: SettingsExtension,
                repoDir: File,
            ) {
                extension.repos.all { repo ->
                    val safeName = repo.sanitizedBuildName
                    tasks.register("$TASK_CLONE-$safeName", CloneTask::class.java, repo, repoDir)
                    tasks.register("$TASK_FETCH-$safeName").configure { task ->
                        task.group = GROUP
                        task.description =
                            "Fetch all branches for ${repo.repoName} and prune deleted origin references; " +
                            "does not create or modify worktrees"
                        task.doLast {
                            val result = GitOperations.fetchRepo(repo, repoDir)
                            logger.lifecycle(result)
                            check(!result.startsWith("FAIL")) { "wrkx: $result" }
                        }
                    }
                    tasks.register(
                        "$TASK_PULL-$safeName",
                        PullTask::class.java,
                        repo,
                        repoDir,
                        provider { extension.activeBranch() ?: "" },
                        extension.allowedBranchPrefixes,
                    )
                    tasks.register(
                        "$TASK_CHECKOUT-$safeName",
                        CheckoutTask::class.java,
                        repo,
                        repoDir,
                        provider { extension.activeBranch() ?: "" },
                        extension.allowedBranchPrefixes,
                    )
                    tasks.register("$TASK_WORKTREE-$safeName").configure { task ->
                        task.group = GROUP
                        task.description =
                            "Fetch ${repo.repoName}'s shared bare repository and create or reuse its selected branch " +
                            "worktree; existing worktrees and uncommitted changes are never removed"
                        task.doLast {
                            val branch =
                                extension.activeBranch()
                                    ?: error(missingBranchMessage("$TASK_WORKTREE-$safeName"))
                            val result =
                                GitOperations.createWorktree(
                                    repo,
                                    repoDir,
                                    branch,
                                    extension.allowedBranchPrefixes,
                                )
                            logger.lifecycle(result)
                            check(!result.startsWith("FAIL")) { "wrkx: $result" }
                        }
                    }
                    tasks.register("$TASK_PRUNE-$safeName").configure { task ->
                        task.group = GROUP
                        task.description =
                            "Fetch ${repo.repoName}, then remove only clean WRKX worktrees whose branch was observed " +
                            "on origin, is now deleted there, and is fully merged into origin/baseBranch"
                        task.doLast {
                            val result = GitOperations.pruneRepo(repo, repoDir, extension.allowedBranchPrefixes)
                            logger.lifecycle(result)
                            check(!result.startsWith("FAIL")) { "wrkx: $result" }
                        }
                    }
                }
            }

            @Suppress("LongMethod")
            internal fun Project.registerLifecycleTasks(
                extension: SettingsExtension,
                repoDir: File,
            ) {
                val repos = extension.repos

                tasks.register(TASK_CLONE).configure { task ->
                    task.group = GROUP
                    task.description =
                        "Create missing shared bare repositories under <workspace>-repos/bare " +
                        "and fetch/prune existing " +
                        "ones; does not create, switch, merge, or delete worktrees"
                    task.doLast {
                        GitOperations.runParallel(repos.toList(), "clone") { repo ->
                            GitOperations.cloneRepo(repo, repoDir)
                        }
                    }
                }

                tasks.register(TASK_FETCH).configure { task ->
                    task.group = GROUP
                    task.description =
                        "Fetch all branches and prune deleted origin references in every existing shared bare " +
                        "repository; does not create or modify worktrees"
                    task.doLast {
                        GitOperations.runParallel(repos.toList(), "fetch") { repo ->
                            GitOperations.fetchRepo(repo, repoDir)
                        }
                    }
                }

                tasks.register(TASK_PULL).configure { task ->
                    task.group = GROUP
                    task.description =
                        "Fetch every shared bare repository and merge each origin/baseBranch into " +
                        "its selected branch " +
                        "worktree; refuses dirty worktrees and aborts conflicting merges"
                    task.doLast {
                        GitOperations.runParallel(repos.toList(), "pull") { repo ->
                            GitOperations.pullRepo(
                                repo,
                                repoDir,
                                extension.activeBranch(),
                                extension.allowedBranchPrefixes,
                            )
                        }
                    }
                }

                tasks.register(TASK_CHECKOUT).configure { task ->
                    task.group = GROUP
                    task.description =
                        "Compatibility alias for wrkx-worktree; create or reuse the selected branch worktree for " +
                        "every repository without changing another worktree"
                    task.doLast {
                        val wb = extension.activeBranch() ?: ""
                        GitOperations.runParallel(repos.toList(), "checkout") { repo ->
                            GitOperations.checkoutRepo(repo, repoDir, wb, extension.allowedBranchPrefixes)
                        }
                    }
                }

                tasks.register(TASK_WORKTREE).configure { task ->
                    task.group = GROUP
                    task.description =
                        "Fetch every shared bare repository and create or reuse its selected branch worktree; " +
                        "existing worktrees and uncommitted changes are never removed"
                    task.doLast {
                        val branch =
                            extension.activeBranch()
                                ?: error(missingBranchMessage(TASK_WORKTREE))
                        GitOperations.runParallel(repos.toList(), "worktree") { repo ->
                            GitOperations.createWorktree(repo, repoDir, branch, extension.allowedBranchPrefixes)
                        }
                    }
                }
            }

            internal fun Project.registerUtilityTasks(
                extension: SettingsExtension,
                repoDir: File,
            ) {
                tasks.register(TASK_STATUS, StatusTask::class.java, extension.repos, repoDir)
                tasks.register(
                    TASK_PRUNE,
                    PruneTask::class.java,
                    extension.repos,
                    repoDir,
                    extension.allowedBranchPrefixes,
                )
            }

            private fun missingBranchMessage(taskName: String): String =
                """
                wrkx: $taskName requires a selected branch.
                Cause: Neither wrkx.workingBranch nor -P$BRANCH_PROP was provided.
                Preservation: No repositories or worktrees were changed.
                Recovery: Run './gradlew $taskName -P$BRANCH_PROP=feature/<kebab-case-name>' or configure workingBranch.
                """.trimIndent()
        }
}
