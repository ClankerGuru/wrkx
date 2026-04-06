package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Clones all workspace repositories in parallel using a fixed thread pool.
 *
 * Each repo is cloned in its own thread via [Executors.newFixedThreadPool].
 * Repos whose target directory already exists are skipped.
 * After cloning, checks out [WorkspaceRepository.baseBranch] if it differs from `main`.
 *
 * Reports per-repo success/failure and a summary at the end.
 *
 * ```bash
 * ./gradlew wrkx-clone   # clone all repos in parallel
 * ```
 *
 * @param repos the container of all registered [WorkspaceRepository] entries
 * @param repoDir base directory where repos are cloned
 * @see CloneTask
 * @see ParallelPullTask
 * @see ParallelCheckoutTask
 */
@org.gradle.api.tasks.UntrackedTask(because = "Clones all workspace repositories in parallel")
abstract class ParallelCloneTask
    @Inject
    constructor(
        private val repos: NamedDomainObjectContainer<WorkspaceRepository>,
        private val repoDir: File,
    ) : DefaultTask() {
        init {
            group = Wrkx.GROUP
            description = "Clone all repos defined in ${Wrkx.CONFIG_FILE} (parallel)"
        }

        /**
         * Clone all workspace repositories in parallel.
         *
         * Uses a thread pool of 4 threads. Each repo clone runs as a separate
         * [Callable] that returns a [RepoResult] indicating success or failure.
         *
         * ```bash
         * ./gradlew wrkx-clone
         * ```
         */
        @TaskAction
        fun cloneAll() {
            val repoList = repos.toList()
            if (repoList.isEmpty()) {
                logger.lifecycle("wrkx: No repos defined in ${Wrkx.CONFIG_FILE}")
                return
            }

            val executor = Executors.newFixedThreadPool(POOL_SIZE)
            val futures =
                repoList.map { repo ->
                    executor.submit(
                        Callable {
                            runCatching { cloneRepo(repo) }
                                .fold(
                                    onSuccess = { RepoResult(repo.repoName, true, it) },
                                    onFailure = { RepoResult(repo.repoName, false, it.message ?: "Unknown error") },
                                )
                        },
                    )
                }

            val results = futures.map { it.get() }
            executor.shutdown()

            logSummary(results)

            val failures = results.filter { !it.success }
            if (failures.isNotEmpty()) {
                error(
                    "wrkx: ${failures.size} repo(s) failed to clone: " +
                        failures.joinToString(", ") { it.name },
                )
            }
        }

        private fun cloneRepo(repo: WorkspaceRepository): String {
            val target = File(repoDir, repo.directoryName)
            if (target.exists()) {
                return "SKIP -- directory already exists at ${target.absolutePath}"
            }

            val url = repo.path.get().value
            target.parentFile?.mkdirs()

            val cloneProcess =
                ProcessBuilder("git", "clone", url, target.absolutePath)
                    .redirectErrorStream(true)
                    .start()
            val cloneOutput =
                cloneProcess.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val cloneExit = cloneProcess.waitFor()
            check(cloneExit == 0) { "git clone failed: $cloneOutput" }

            checkoutBaseBranch(repo, target)
            return "Cloned from $url"
        }

        private fun checkoutBaseBranch(repo: WorkspaceRepository, targetDir: File) {
            val branch = repo.baseBranch.get().value
            if (branch.isBlank() || branch == "main") return

            val checkout =
                ProcessBuilder("git", "checkout", branch)
                    .directory(targetDir)
                    .redirectErrorStream(true)
                    .start()
            checkout.inputStream.bufferedReader().readText()
            if (checkout.waitFor() == 0) return

            val create =
                ProcessBuilder("git", "checkout", "-b", branch)
                    .directory(targetDir)
                    .redirectErrorStream(true)
                    .start()
            val output =
                create.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            check(create.waitFor() == 0) { "Failed to create branch '$branch': $output" }
        }

        private fun logSummary(results: List<RepoResult>) {
            val succeeded = results.count { it.success }
            val failed = results.count { !it.success }

            results.forEach { result ->
                val icon = if (result.success) "OK" else "FAIL"
                logger.lifecycle("wrkx: [$icon] ${result.name} -- ${result.message}")
            }
            logger.lifecycle("wrkx: Clone complete. $succeeded succeeded, $failed failed, ${results.size} total.")
        }

        private data class RepoResult(
            val name: String,
            val success: Boolean,
            val message: String,
        )

        private companion object {
            private const val POOL_SIZE = 4
        }
    }
