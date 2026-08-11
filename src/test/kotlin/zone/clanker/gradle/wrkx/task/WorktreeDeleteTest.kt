package zone.clanker.gradle.wrkx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.WorkspaceLayout
import java.io.File

class WorktreeDeleteTest :
    BehaviorSpec({
        fun tempDir(): File =
            File.createTempFile("wrkx-delete", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("a clean selected branch and another worktree") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "delete-dirty")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            GitOperations.createWorktree(repo, repoDir, "main") shouldStartWith "OK"
            GitOperations.createWorktree(repo, repoDir, "Feature/Discard-Me") shouldStartWith "OK"
            val selected = WorkspaceLayout.worktree(repoDir, "Feature/Discard-Me", repo)

            `when`("the selected worktree is deleted") {
                val result = GitOperations.deleteWorktree(repo, repoDir, "Feature/Discard-Me")

                then("its directory and safely deletable local branch are removed") {
                    result shouldStartWith "OK"
                    selected.shouldNotExist()
                    gitExit(
                        "git",
                        "--git-dir=${WorkspaceLayout.bareRepository(repoDir, repo).absolutePath}",
                        "show-ref",
                        "--verify",
                        "refs/heads/Feature/Discard-Me",
                    ) shouldNotBe 0
                }

                then("the bare repository and other worktree remain") {
                    WorkspaceLayout.bareRepository(repoDir, repo).resolve("HEAD").shouldExist()
                    WorkspaceLayout.worktree(repoDir, "main", repo).resolve("README.md").shouldExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("a dirty selected worktree") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "delete-dirty")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            val branch = "Feature/Keep-Dirty"
            GitOperations.createWorktree(repo, repoDir, branch)
            val worktree = WorkspaceLayout.worktree(repoDir, branch, repo)
            File(worktree, "unfinished.txt").writeText("keep")

            then("delete refuses to force removal") {
                GitOperations.deleteWorktree(repo, repoDir, branch) shouldStartWith "FAIL"
                worktree.resolve("unfinished.txt").shouldExist()
            }

            baseDir.deleteRecursively()
        }

        given("a selected branch that exists on origin") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "delete-remote")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            val branch = "Feature/Remote-Branch"
            GitOperations.createWorktree(repo, repoDir, branch)
            val worktree = WorkspaceLayout.worktree(repoDir, branch, repo)
            File(worktree, "remote.txt").writeText("remote")
            git(worktree, "add", ".")
            git(worktree, "commit", "-m", "Remote branch")
            git(worktree, "push", "-u", "origin", branch)
            GitOperations.fetchRepo(repo, repoDir)

            `when`("the local worktree and branch are deleted then recreated") {
                GitOperations.deleteWorktree(repo, repoDir, branch) shouldStartWith "OK"
                val bare = WorkspaceLayout.bareRepository(repoDir, repo)

                then("the remote branch remains recoverable without any remote mutation") {
                    gitOutput(bare, "refs/remotes/origin/$branch") shouldContain "refs/remotes/origin/$branch"
                    GitOperations.createWorktree(repo, repoDir, branch) shouldStartWith "OK"
                    WorkspaceLayout.worktree(repoDir, branch, repo).resolve("remote.txt").shouldExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("an unpushed worktree created from the wrong base branch") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "wrong-base")
            val setup = File(baseDir, "base-setup")
            process("git", "clone", remote.absolutePath, setup.absolutePath)
            git(setup, "checkout", "-b", "Release/Correct_Base-1.0")
            File(setup, "correct-base.txt").writeText("correct")
            git(setup, "add", ".")
            git(setup, "commit", "-m", "Correct base")
            git(setup, "push", "origin", "Release/Correct_Base-1.0")

            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            val branch = "Feature/Restart-Work"
            GitOperations.createWorktree(repo, repoDir, branch)
            val worktree = WorkspaceLayout.worktree(repoDir, branch, repo)
            worktree.resolve("correct-base.txt").shouldNotExist()

            `when`("the worktree is deleted and recreated after correcting baseBranch") {
                GitOperations.deleteWorktree(repo, repoDir, branch) shouldStartWith "OK"
                repo.baseBranch.set("Release/Correct_Base-1.0")
                GitOperations.createWorktree(repo, repoDir, branch) shouldStartWith "OK"

                then("the recreated branch starts from the corrected base") {
                    WorkspaceLayout.worktree(repoDir, branch, repo).resolve("correct-base.txt").shouldExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("a repository without a bare clone") {
            val baseDir = tempDir()
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, File(baseDir, "missing.git").absolutePath)

            then("delete is an explicit no-op") {
                GitOperations.deleteWorktree(repo, File(baseDir, "repos"), "Feature/Missing") shouldStartWith "SKIP"
            }

            baseDir.deleteRecursively()
        }

        given("an ordinary directory at the expected worktree path") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "ordinary-directory")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            GitOperations.cloneRepo(repo, repoDir)
            val target = WorkspaceLayout.worktree(repoDir, "Feature/Ordinary", repo).apply { mkdirs() }
            File(target, "keep.txt").writeText("not a worktree")

            then("delete refuses to remove an unregistered directory") {
                GitOperations.deleteWorktree(repo, repoDir, "Feature/Ordinary") shouldContain "not registered"
                target.resolve("keep.txt").shouldExist()
            }

            baseDir.deleteRecursively()
        }

        given("a local branch registered to a non-WRKX worktree") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "external-worktree")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            GitOperations.cloneRepo(repo, repoDir)
            val bare = WorkspaceLayout.bareRepository(repoDir, repo)
            val external = File(baseDir, "external")
            process(
                "git",
                "--git-dir=${bare.absolutePath}",
                "worktree",
                "add",
                "-b",
                "Feature/External",
                external.absolutePath,
                "origin/main",
            )

            then("delete refuses to touch the external path") {
                GitOperations.deleteWorktree(repo, repoDir, "Feature/External") shouldContain "non-WRKX worktree"
                external.resolve("README.md").shouldExist()
            }

            baseDir.deleteRecursively()
        }
    })

private fun gitExit(vararg command: String): Int {
    val child = ProcessBuilder(*command).redirectErrorStream(true).start()
    child.inputStream.bufferedReader().readText()
    return child.waitFor()
}

private fun gitOutput(bare: File, ref: String): String {
    val child =
        ProcessBuilder("git", "--git-dir=${bare.absolutePath}", "show-ref", "--verify", ref)
            .redirectErrorStream(true)
            .start()
    val output = child.inputStream.bufferedReader().readText()
    check(child.waitFor() == 0) { output }
    return output
}

private fun git(directory: File, vararg args: String) {
    process("git", "-C", directory.absolutePath, *args)
}

private fun process(vararg command: String) {
    val child = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = child.inputStream.bufferedReader().readText()
    check(child.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
}
