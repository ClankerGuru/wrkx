package zone.clanker.gradle.wrkx.task

import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.tasks.TaskAction
import zone.clanker.gradle.wrkx.Wrkx
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Pulls the latest changes for all workspace repositories in parallel.
 *
 * Each repo is pulled in its own thread via [Executors.newFixedThreadPool].
 * Uses `git fetch origin <baseBranch>` followed by `git merge --ff-only origin/<baseBranch>`.
 * Repos with no remote configured are skipped.
 *
 * Reports per-repo success/failure and a summary at the end.
 *
 * ```bash
 * ./gradlew wrkx-pull   # pull all repos in parallel
 * ```
 *
 * @param repos the container of all registered [WorkspaceRepository] entries
 * @param repoDir base directory where repos are cloned
 * @see PullTask
 * @see ParallelCloneTask
 * @see ParallelCheckoutTask
 */
@org.gradle.api.tasks.UntrackedTask(because = "Pulls latest changes for all repositories in parallel")
abstract class ParallelPullTask
    @Inject
    constructor(
        private val repos: NamedDomainObjectContainer<WorkspaceRepository>,
        private val repoDir: File,
    ) : DefaultTask() {
        init {
            group = Wrkx.GROUP
            description = "Pull baseBranch for all repos from their remotes (parallel)"
        }

        /**
         * Pull all workspace repositories in parallel.
         *
         * Uses a thread pool of 4 threads. Each repo pull runs as a separate
         * [Callable] that returns a [RepoResult] indicating success or failure.
         *
         * ```bash
         * ./gradlew wrkx-pull
         * ```
         */
        @TaskAction
        fun pullAll() {
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
                            runCatching { pullRepo(repo) }
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
                    "wrkx: ${failures.size} repo(s) failed to pull: " +
                        failures.joinToString(", ") { it.name },
                )
            }
        }

        private fun pullRepo(repo: WorkspaceRepository): String {
            val dir = File(repoDir, repo.directoryName)
            check(dir.exists()) {
                "Cannot pull '${repo.repoName}' -- directory not found at ${dir.absolutePath}. " +
                    "Run './gradlew ${Wrkx.TASK_CLONE}-${repo.sanitizedBuildName}' to clone it first."
            }

            if (!hasRemote(dir)) {
                return "SKIP -- no remote configured"
            }

            val base = repo.baseBranch.get().value

            val fetchProcess =
                ProcessBuilder("git", "fetch", "origin", base)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            val fetchOutput =
                fetchProcess.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val fetchExit = fetchProcess.waitFor()
            check(fetchExit == 0) { "git fetch failed: $fetchOutput" }

            val mergeProcess =
                ProcessBuilder("git", "merge", "--ff-only", "origin/$base")
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            val mergeOutput =
                mergeProcess.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val mergeExit = mergeProcess.waitFor()
            check(mergeExit == 0) { "git merge --ff-only failed: $mergeOutput" }

            return "Pulled from $base"
        }

        private fun hasRemote(dir: File): Boolean {
            val output = ByteArrayOutputStream()
            val process =
                ProcessBuilder("git", "remote")
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
            process.inputStream.copyTo(output)
            process.waitFor()
            return output.toString().trim().isNotEmpty()
        }

        private fun logSummary(results: List<RepoResult>) {
            val succeeded = results.count { it.success }
            val failed = results.count { !it.success }

            results.forEach { result ->
                val icon = if (result.success) "OK" else "FAIL"
                logger.lifecycle("wrkx: [$icon] ${result.name} -- ${result.message}")
            }
            logger.lifecycle("wrkx: Pull complete. $succeeded succeeded, $failed failed, ${results.size} total.")
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
