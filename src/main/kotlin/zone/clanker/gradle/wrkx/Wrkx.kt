package zone.clanker.gradle.wrkx

import kotlinx.serialization.json.Json
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.BuildLayout
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.initialization.Settings
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import zone.clanker.gradle.wrkx.model.GitReference
import zone.clanker.gradle.wrkx.model.RepositoryEntry
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import zone.clanker.gradle.wrkx.task.CheckoutTask
import zone.clanker.gradle.wrkx.task.CloneTask
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

    /** Task name: pull baseBranch for all repos from their remotes. */
    const val TASK_PULL = "wrkx-pull"

    /** Task name: checkout workingBranch (or baseBranch) across all repos. */
    const val TASK_CHECKOUT = "wrkx-checkout"

    /** Task name: generate workspace status report at [OUTPUT_DIR]/repos.md. */
    const val TASK_STATUS = "wrkx-status"

    /** Task name: remove repo directories not defined in [CONFIG_FILE]. */
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
        ) {
            /** Base directory where repos are cloned (sibling to the project). */
            abstract val baseDir: DirectoryProperty

            /** Branch to checkout for enabled repos when running wrkx-checkout. */
            var workingBranch: String? = null

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
                repos.forEach { it.enable(true) }
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
             * ```kotlin
             * wrkx {
             *     enable(repos.getByName("gort"), repos.getByName("coreModels"))
             * }
             * ```
             *
             * @param repositories the repos to enable
             */
            fun enable(vararg repositories: WorkspaceRepository) {
                repositories.forEach { it.enable(true) }
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

            /**
             * Include only enabled repos as composite builds.
             * Called after settings evaluation so DSL has had a chance to run.
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

            private fun includeRepo(repo: WorkspaceRepository) {
                val cloneDir = repo.clonePath.asFile.get()
                if (!cloneDir.exists()) {
                    println(
                        "wrkx: Repository '${repo.repoName}' not cloned at ${cloneDir.absolutePath}. " +
                            "Run './gradlew $TASK_CLONE-${repo.sanitizedBuildName}' to clone it.",
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
                        project.registerLifecycleTasks()
                        project.registerUtilityTasks(extension, repoDir)
                    },
                )
            }

            private fun isDisabled(): Boolean =
                providers.gradleProperty(ENABLED_PROP).orNull?.lowercase() == "false"

            private fun isAlreadyApplied(settings: Settings): Boolean =
                settings.extensions.findByType(SettingsExtension::class.java) != null

            private fun resolveRepoDir(): File {
                val settingsDir = layout.settingsDirectory.asFile
                return File(settingsDir.parentFile ?: settingsDir, "${settingsDir.name}-repos")
            }

            private fun createExtension(settings: Settings, repoDir: File): SettingsExtension {
                val extension =
                    settings.extensions.create(EXTENSION_NAME, SettingsExtension::class.java, settings)
                extension.baseDir.set(repoDir)
                return extension
            }

            private val json = Json { ignoreUnknownKeys = true }

            private fun populateFromConfig(extension: SettingsExtension, repoDir: File) {
                val configFile = layout.settingsDirectory.file(CONFIG_FILE).asFile
                if (!configFile.exists()) {
                    configFile.writeText("[]\n")
                    println(
                        """
                        wrkx: Created empty $CONFIG_FILE at ${configFile.absolutePath}.
                        Add repositories to this file to manage your workspace. Example:
                        [
                          {
                            "name": "gort",
                            "path": "git@github.com:org/repo.git",
                            "baseBranch": "main",
                            "category": "lib",
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
                        extension.repos.register(entry.name) { repo ->
                            repo.path.set(entry.path)
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

            private val perRepoCloneTasks = mutableListOf<String>()
            private val perRepoPullTasks = mutableListOf<String>()
            private val perRepoCheckoutTasks = mutableListOf<String>()

            private fun Project.registerCatalogTask() {
                if (tasks.findByName(TASK_CATALOG) != null) return

                tasks.register(TASK_CATALOG).configure { task ->
                    task.group = GROUP
                    task.description = "List all available workspace tasks"
                    task.doLast {
                        println(
                            """
                        |
                        |Workspace Tasks ($GROUP)
                        |${"=".repeat(CATALOG_DIVIDER_LENGTH)}
                        |
                        |  $TASK_CLONE       Clone all repos defined in $CONFIG_FILE
                        |  $TASK_PULL        Pull baseBranch for all repos from their remotes
                        |  $TASK_CHECKOUT    Checkout workingBranch (or baseBranch) across all repos
                        |  $TASK_STATUS      Generate workspace status report at $OUTPUT_DIR/repos.md
                        |  $TASK_PRUNE       Remove repo directories not defined in $CONFIG_FILE
                        |
                        |Run any task:  ./gradlew <task-name>
                        |Full details:  ./gradlew help --task <task-name>
                        |
                            """.trimMargin(),
                        )
                    }
                }
            }

            private fun Project.registerPerRepoTasks(
                extension: SettingsExtension,
                repoDir: File,
            ) {
                extension.repos.all { repo ->
                    val safeName = repo.sanitizedBuildName

                    perRepoCloneTasks.add("$TASK_CLONE-$safeName")
                    tasks.register("$TASK_CLONE-$safeName", CloneTask::class.java, repo, repoDir)

                    perRepoPullTasks.add("$TASK_PULL-$safeName")
                    tasks.register("$TASK_PULL-$safeName", PullTask::class.java, repo, repoDir)

                    perRepoCheckoutTasks.add("$TASK_CHECKOUT-$safeName")
                    tasks.register(
                        "$TASK_CHECKOUT-$safeName",
                        CheckoutTask::class.java,
                        repo,
                        repoDir,
                        extension.workingBranch ?: "",
                    )
                }
            }

            private fun Project.registerLifecycleTasks() {
                tasks.register(TASK_CLONE).configure { task ->
                    task.group = GROUP
                    task.description = "Clone all repos defined in $CONFIG_FILE"
                    task.dependsOn(perRepoCloneTasks)
                }

                tasks.register(TASK_PULL).configure { task ->
                    task.group = GROUP
                    task.description = "Pull baseBranch for all repos from their remotes"
                    task.dependsOn(perRepoPullTasks)
                }

                tasks.register(TASK_CHECKOUT).configure { task ->
                    task.group = GROUP
                    task.description = "Checkout workingBranch (or baseBranch) across all repos"
                    task.dependsOn(perRepoCheckoutTasks)
                }
            }

            private fun Project.registerUtilityTasks(
                extension: SettingsExtension,
                repoDir: File,
            ) {
                tasks.register(TASK_STATUS, StatusTask::class.java, extension.repos, repoDir)
                tasks.register(TASK_PRUNE, PruneTask::class.java, extension.repos, repoDir)
            }
        }
}
