package zone.clanker.gradle.wrkx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.WorkspaceLayout
import java.io.File

class PullTaskTest :
    BehaviorSpec({
        fun tempDir(): File =
            File.createTempFile("wrkx-pull", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("a selected branch worktree") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "pull-task")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            GitOperations.createWorktree(repo, repoDir, "feature/pull-task")

            `when`("the per-repository pull task runs") {
                val task =
                    project.tasks
                        .register(
                            "wrkx-pull-test",
                            PullTask::class.java,
                            repo,
                            repoDir,
                            project.provider { "feature/pull-task" },
                            WorkspaceLayout.defaultBranchPrefixes,
                        ).get()
                task.pull()

                then("the selected worktree remains available") {
                    WorkspaceLayout
                        .worktree(repoDir, "feature/pull-task", repo)
                        .resolve("README.md")
                        .shouldExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("a missing selected branch worktree") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "missing-pull-task")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)

            then("the task fails with a worktree recovery command") {
                val exception =
                    shouldThrow<IllegalStateException> {
                        project.tasks
                            .register(
                                "wrkx-pull-missing",
                                PullTask::class.java,
                                repo,
                                repoDir,
                                project.provider { "feature/missing" },
                                WorkspaceLayout.defaultBranchPrefixes,
                            ).get()
                            .pull()
                    }
                exception.message shouldContain "wrkx-worktree-missing-pull-task"
                exception.message shouldContain "Existing repositories, worktrees, commits"
            }

            baseDir.deleteRecursively()
        }
    })
