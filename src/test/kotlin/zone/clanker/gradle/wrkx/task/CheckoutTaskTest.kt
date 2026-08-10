package zone.clanker.gradle.wrkx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.WorkspaceLayout
import java.io.File

class CheckoutTaskTest :
    BehaviorSpec({
        fun tempDir(): File =
            File.createTempFile("wrkx-checkout", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("a repository with no local worktree") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "checkout-task")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)

            `when`("checkout runs for a selected branch") {
                project.tasks
                    .register(
                        "wrkx-checkout-test",
                        CheckoutTask::class.java,
                        repo,
                        repoDir,
                        project.provider { "feature/checkout-task" },
                        WorkspaceLayout.defaultBranchPrefixes,
                    ).get()
                    .checkout()

                then("it creates a branch-scoped worktree") {
                    val worktree = WorkspaceLayout.worktree(repoDir, "feature/checkout-task", repo)
                    worktree.resolve("README.md").shouldExist()
                    gitBranch(worktree) shouldBe "feature/checkout-task"
                }
            }

            baseDir.deleteRecursively()
        }

        given("no selected working branch") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "checkout-main-task")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)

            `when`("checkout runs") {
                project.tasks
                    .register(
                        "wrkx-checkout-main",
                        CheckoutTask::class.java,
                        repo,
                        repoDir,
                        project.provider { "" },
                        WorkspaceLayout.defaultBranchPrefixes,
                    ).get()
                    .checkout()

                then("it creates the repository base branch worktree") {
                    WorkspaceLayout.worktree(repoDir, "main", repo).resolve("README.md").shouldExist()
                }
            }

            baseDir.deleteRecursively()
        }
    })

private fun gitBranch(worktree: File): String {
    val process =
        ProcessBuilder("git", "-C", worktree.absolutePath, "branch", "--show-current")
            .redirectErrorStream(true)
            .start()
    val output =
        process.inputStream
            .bufferedReader()
            .readText()
            .trim()
    check(process.waitFor() == 0) { output }
    return output
}
