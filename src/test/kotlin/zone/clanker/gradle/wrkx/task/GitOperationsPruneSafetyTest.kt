package zone.clanker.gradle.wrkx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.WorkspaceLayout
import java.io.File

class GitOperationsPruneSafetyTest :
    BehaviorSpec({
        fun tempDir(): File =
            File.createTempFile("wrkx-prune-safety", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("fetch targets that are missing or not bare") {
            val baseDir = tempDir()
            val project = ProjectBuilder.builder().build()

            then("fetch reports both recovery paths") {
                val missing = createTestRepo(project.objects, File(baseDir, "missing.git").absolutePath)
                GitOperations.fetchRepo(missing, File(baseDir, "repos")) shouldStartWith "FAIL"

                val remote = createBareRepo(baseDir, "not-bare")
                val repo = createTestRepo(project.objects, remote.absolutePath)
                val repoDir = File(baseDir, "repos")
                val target = WorkspaceLayout.bareRepository(repoDir, repo)
                target.mkdirs()
                File(target, "ordinary.txt").writeText("not bare")
                GitOperations.fetchRepo(repo, repoDir) shouldContain "not a bare Git repository"
            }

            baseDir.deleteRecursively()
        }

        given("a local branch that has never existed on origin") {
            val fixture = branchFixture("never-pushed", push = false)

            then("prune retains it even when it equals the base branch") {
                val result = GitOperations.pruneRepo(fixture.repo, fixture.repoDir)
                result shouldContain "remote branch was never observed"
                fixture.worktree.shouldExist()
            }

            fixture.baseDir.deleteRecursively()
        }

        given("a branch that still exists on origin") {
            val fixture = branchFixture("remote-exists", push = true)

            then("prune retains it") {
                val result = GitOperations.pruneRepo(fixture.repo, fixture.repoDir)
                result shouldContain "origin/${fixture.branch} still exists"
                fixture.worktree.shouldExist()
            }

            fixture.baseDir.deleteRecursively()
        }

        given("a deleted remote branch with unmerged commits") {
            val fixture = branchFixture("unmerged", push = true, commit = true)
            gitCommand(fixture.worktree, "push", "origin", "--delete", fixture.branch)

            then("prune retains it") {
                val result = GitOperations.pruneRepo(fixture.repo, fixture.repoDir)
                result shouldContain "not fully merged"
                fixture.worktree.shouldExist()
            }

            fixture.baseDir.deleteRecursively()
        }

        given("a merged deleted remote branch with a dirty worktree") {
            val fixture = branchFixture("dirty", push = true, commit = true)
            mergeBranch(fixture)
            File(fixture.worktree, "dirty.txt").writeText("keep me")

            then("prune retains it and the local file") {
                val result = GitOperations.pruneRepo(fixture.repo, fixture.repoDir)
                result shouldContain "uncommitted changes"
                File(fixture.worktree, "dirty.txt").shouldExist()
            }

            fixture.baseDir.deleteRecursively()
        }

        given("a repository whose configured base branch does not exist") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "missing-base")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath, baseBranch = "develop")
            val repoDir = File(baseDir, "repos")
            GitOperations.cloneRepo(repo, repoDir)

            then("prune fails without removing worktrees") {
                GitOperations.pruneRepo(repo, repoDir) shouldContain "Base branch 'origin/develop' does not exist"
            }

            baseDir.deleteRecursively()
        }

        given("a base branch that conflicts with the selected worktree") {
            val fixture = branchFixture("pull-conflict", push = false)
            File(fixture.worktree, "README.md").writeText("feature\n")
            gitCommand(fixture.worktree, "add", ".")
            gitCommand(fixture.worktree, "commit", "-m", "Feature conflict")

            val integration = File(fixture.baseDir, "base-conflict")
            runProcess("git", "clone", fixture.remote.absolutePath, integration.absolutePath)
            File(integration, "README.md").writeText("base\n")
            gitCommand(integration, "add", ".")
            gitCommand(integration, "commit", "-m", "Base conflict")
            gitCommand(integration, "push", "origin", "main")

            then("pull aborts the merge and preserves the feature content") {
                val result = GitOperations.pullRepo(fixture.repo, fixture.repoDir, fixture.branch)
                result shouldContain "the merge was aborted"
                File(fixture.worktree, "README.md").readText() shouldContain "feature"
            }

            fixture.baseDir.deleteRecursively()
        }

        given("a selected worktree whose HEAD was manually detached") {
            val fixture = branchFixture("detached-pull", push = false)
            gitCommand(fixture.worktree, "checkout", "--detach")

            then("pull refuses to merge into the unexpected HEAD") {
                val result = GitOperations.pullRepo(fixture.repo, fixture.repoDir, fixture.branch)
                result shouldContain "HEAD is 'detached'"
                result shouldContain "No merge was attempted"
            }

            fixture.baseDir.deleteRecursively()
        }
    })

private data class BranchFixture(
    val baseDir: File,
    val remote: File,
    val repoDir: File,
    val repo: zone.clanker.gradle.wrkx.model.WorkspaceRepository,
    val branch: String,
    val worktree: File,
)

private fun branchFixture(name: String, push: Boolean, commit: Boolean = false): BranchFixture {
    val baseDir =
        File.createTempFile("wrkx-$name", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
    val remote = createBareRepo(baseDir, name)
    val project = ProjectBuilder.builder().build()
    val repo = createTestRepo(project.objects, remote.absolutePath)
    val repoDir = File(baseDir, "repos")
    val branch = "feature/$name"
    GitOperations.createWorktree(repo, repoDir, branch)
    val worktree = WorkspaceLayout.worktree(repoDir, branch, repo)
    if (commit) {
        File(worktree, "$name.txt").writeText(name)
        gitCommand(worktree, "add", ".")
        gitCommand(worktree, "commit", "-m", name)
    }
    if (push) {
        gitCommand(worktree, "push", "-u", "origin", branch)
        GitOperations.fetchRepo(repo, repoDir)
    }
    return BranchFixture(baseDir, remote, repoDir, repo, branch, worktree)
}

private fun mergeBranch(fixture: BranchFixture) {
    val integration = File(fixture.baseDir, "merge-${fixture.branch.substringAfterLast('/')}")
    runProcess("git", "clone", fixture.remote.absolutePath, integration.absolutePath)
    gitCommand(integration, "merge", "--no-edit", "origin/${fixture.branch}")
    gitCommand(integration, "push", "origin", "main")
    gitCommand(integration, "push", "origin", "--delete", fixture.branch)
}

private fun gitCommand(directory: File, vararg args: String) {
    runProcess("git", "-C", directory.absolutePath, *args)
}

private fun runProcess(vararg command: String) {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
}
