package zone.clanker.gradle.wrkx.task

import org.gradle.api.logging.Logging
import zone.clanker.gradle.wrkx.model.WorkspaceLayout
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Parallel git operations for lifecycle tasks.
 *
 * Runs git commands across multiple repos concurrently using a fixed thread pool.
 */
internal object GitOperations {
    private val logger = Logging.getLogger(GitOperations::class.java)
    private const val THREAD_POOL_SIZE = 4

    fun runParallel(
        repos: List<WorkspaceRepository>,
        action: String,
        work: (WorkspaceRepository) -> String,
    ) {
        if (repos.isEmpty()) {
            logger.lifecycle("wrkx: No repos to $action.")
            return
        }
        val pool = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
        val futures =
            repos.map { repo ->
                pool.submit(
                    Callable {
                        runCatching { work(repo) }
                            .getOrElse { e -> "FAIL ${repo.repoName}: ${e.message}" }
                    },
                )
            }
        val results = futures.map { it.get() }
        pool.shutdown()
        pool.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.MILLISECONDS)
        results.forEach { logger.lifecycle(it) }
        val failed = results.count { it.startsWith("FAIL") }
        logger.lifecycle("wrkx: $action complete — ${repos.size} repos, $failed failed")
        if (failed > 0) error("wrkx: $failed repo(s) failed during $action")
    }

    fun cloneRepo(repo: WorkspaceRepository, repoDir: File): String {
        val target = File(repoDir, repo.directoryName)
        if (target.exists()) return "SKIP ${repo.repoName} — already exists"
        val url = repo.path.get().value
        target.parentFile?.mkdirs()
        val cloneResult = exec("git", "clone", url, target.absolutePath)
        if (cloneResult != 0) return "FAIL ${repo.repoName}: git clone exit $cloneResult"
        return checkoutBaseBranch(repo, target)
    }

    fun createWorktree(
        repo: WorkspaceRepository,
        repoDir: File,
        workingBranch: String,
    ): String {
        require(workingBranch.isNotBlank()) { "wrkx: A working branch is required to create worktrees." }
        val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
        val cloneResult = ensureBareRepository(repo, bareDir)
        val fetchResult =
            if (cloneResult == null) {
                exec("git", "--git-dir=${bareDir.absolutePath}", "fetch", "origin", "--prune")
            } else {
                -1
            }
        val target = WorkspaceLayout.worktree(repoDir, workingBranch, repo)
        return when {
            cloneResult != null -> cloneResult
            fetchResult != 0 -> "FAIL ${repo.repoName}: fetch exit $fetchResult"
            target.exists() -> "SKIP ${repo.repoName} — worktree already exists"
            else -> {
                target.parentFile?.mkdirs()
                exec("git", "--git-dir=${bareDir.absolutePath}", "worktree", "prune")
                addWorktree(repo, bareDir, target, workingBranch)
            }
        }
    }

    private fun ensureBareRepository(
        repo: WorkspaceRepository,
        bareDir: File,
    ): String? {
        if (bareDir.exists()) return null
        bareDir.parentFile?.mkdirs()
        val result = exec("git", "clone", "--bare", repo.path.get().value, bareDir.absolutePath)
        if (result != 0) return "FAIL ${repo.repoName}: bare clone exit $result"
        val refspec = "+refs/heads/*:refs/remotes/origin/*"
        val configResult =
            exec("git", "--git-dir=${bareDir.absolutePath}", "config", "remote.origin.fetch", refspec)
        return if (configResult == 0) null else "FAIL ${repo.repoName}: remote configuration exit $configResult"
    }

    private fun addWorktree(
        repo: WorkspaceRepository,
        bareDir: File,
        target: File,
        branch: String,
    ): String {
        val localBranch = "refs/heads/$branch"
        val remoteBranch = "refs/remotes/origin/$branch"
        val base = repo.baseBranch.get().value
        val startPoint =
            when {
                refExists(bareDir, remoteBranch) -> "origin/$branch"
                refExists(bareDir, "refs/remotes/origin/$base") -> "origin/$base"
                refExists(bareDir, "refs/heads/$base") -> base
                else -> return "FAIL ${repo.repoName}: baseBranch '$base' not found"
            }
        val command =
            if (refExists(bareDir, localBranch)) {
                arrayOf("git", "--git-dir=${bareDir.absolutePath}", "worktree", "add", target.absolutePath, branch)
            } else {
                arrayOf(
                    "git",
                    "--git-dir=${bareDir.absolutePath}",
                    "worktree",
                    "add",
                    "-b",
                    branch,
                    target.absolutePath,
                    startPoint,
                )
            }
        val result = exec(*command)
        return if (result == 0) "OK ${repo.repoName}" else "FAIL ${repo.repoName}: worktree add exit $result"
    }

    private fun refExists(
        bareDir: File,
        reference: String,
    ): Boolean =
        exec(
            "git",
            "--git-dir=${bareDir.absolutePath}",
            "show-ref",
            "--verify",
            "--quiet",
            reference,
        ) == 0

    private fun checkoutBaseBranch(repo: WorkspaceRepository, target: File): String {
        val branch = repo.baseBranch.get().value
        if (branch.isBlank() || branch == "main") return "OK ${repo.repoName}"
        val result = exec("git", "-C", target.absolutePath, "checkout", branch)
        if (result == 0) return "OK ${repo.repoName}"
        val create = exec("git", "-C", target.absolutePath, "checkout", "-b", branch)
        return if (create ==
            0
        ) {
            "OK ${repo.repoName}"
        } else {
            "FAIL ${repo.repoName}: checkout baseBranch '$branch' exit $create"
        }
    }

    fun pullRepo(repo: WorkspaceRepository, repoDir: File): String {
        val dir = File(repoDir, repo.directoryName)
        if (!dir.exists()) return "SKIP ${repo.repoName} — not cloned"
        val remoteOut = execOutput("git", "-C", dir.absolutePath, "remote")
        if (remoteOut.isBlank()) return "SKIP ${repo.repoName} — no remote"
        return fetchAndMerge(repo, dir)
    }

    private fun fetchAndMerge(repo: WorkspaceRepository, dir: File): String {
        val base = repo.baseBranch.get().value
        val fetchResult = exec("git", "-C", dir.absolutePath, "fetch", "origin", base)
        if (fetchResult != 0) return "FAIL ${repo.repoName}: fetch exit $fetchResult"
        val mergeResult = exec("git", "-C", dir.absolutePath, "merge", "--ff-only", "origin/$base")
        return if (mergeResult == 0) "OK ${repo.repoName}" else "FAIL ${repo.repoName}: merge exit $mergeResult"
    }

    fun checkoutRepo(
        repo: WorkspaceRepository,
        repoDir: File,
        workingBranch: String,
    ): String {
        val dir = File(repoDir, repo.directoryName)
        if (!dir.exists()) return "SKIP ${repo.repoName} — not cloned"
        val branch = workingBranch.ifBlank { repo.baseBranch.get().value }
        val statusOut =
            execOutput("git", "-C", dir.absolutePath, "status", "--porcelain")
        if (statusOut.isNotBlank()) return "FAIL ${repo.repoName} — dirty working directory"
        val result = exec("git", "-C", dir.absolutePath, "checkout", branch)
        return when {
            result == 0 -> "OK ${repo.repoName}"
            else -> {
                val base = repo.baseBranch.get().value
                val create =
                    exec("git", "-C", dir.absolutePath, "checkout", "-b", branch, "origin/$base")
                if (create == 0) "OK ${repo.repoName}" else "FAIL ${repo.repoName}: checkout exit $create"
            }
        }
    }

    private const val PROCESS_TIMEOUT_SECONDS = 120L

    private fun exec(vararg cmd: String): Int {
        val process =
            ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Process timed out after ${PROCESS_TIMEOUT_SECONDS}s: ${cmd.joinToString(" ")}\nOutput: $output")
        }
        return process.exitValue()
    }

    private fun execOutput(vararg cmd: String): String {
        val process =
            ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .also { it.environment()["GIT_TERMINAL_PROMPT"] = "0" }
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
                .trim()
        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Process timed out after ${PROCESS_TIMEOUT_SECONDS}s: ${cmd.joinToString(" ")}\nOutput: $output")
        }
        return output
    }
}
