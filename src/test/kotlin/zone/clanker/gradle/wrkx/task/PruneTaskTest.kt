package zone.clanker.gradle.wrkx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldNotExist
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.WorkspaceLayout
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File

class PruneTaskTest :
    BehaviorSpec({
        fun tempDir(): File =
            File.createTempFile("wrkx-prune", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("a clean merged worktree whose observed remote branch was deleted") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "prune-task")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            val branch = "feature/prune-task"
            GitOperations.createWorktree(repo, repoDir, branch)
            val worktree = WorkspaceLayout.worktree(repoDir, branch, repo)
            File(worktree, "feature.txt").writeText("merged")
            git(worktree, "add", ".")
            git(worktree, "commit", "-m", "Feature")
            git(worktree, "push", "-u", "origin", branch)
            GitOperations.fetchRepo(repo, repoDir)
            mergeAndDeleteRemoteBranch(baseDir, remote, branch)

            `when`("the prune task runs") {
                val repos = container(project, repo)
                project.tasks
                    .register(
                        "wrkx-prune-test",
                        PruneTask::class.java,
                        repos,
                        repoDir,
                        WorkspaceLayout.defaultBranchPrefixes,
                    ).get()
                    .prune()

                then("it removes the worktree") {
                    worktree.shouldNotExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("a missing repository directory") {
            val baseDir = tempDir()
            val repoDir = File(baseDir, "missing")
            val project = ProjectBuilder.builder().build()
            val repos = project.objects.domainObjectContainer(WorkspaceRepository::class.java)

            `when`("prune runs") {
                project.tasks
                    .register(
                        "wrkx-prune-empty",
                        PruneTask::class.java,
                        repos,
                        repoDir,
                        WorkspaceLayout.defaultBranchPrefixes,
                    ).get()
                    .prune()

                then("it completes without creating the directory") {
                    repoDir.shouldNotExist()
                }
            }

            baseDir.deleteRecursively()
        }
    })

private fun container(
    project: org.gradle.api.Project,
    repo: WorkspaceRepository,
) =
    project.objects.domainObjectContainer(WorkspaceRepository::class.java).apply {
        add(repo)
    }

private fun mergeAndDeleteRemoteBranch(baseDir: File, remote: File, branch: String) {
    val integration = File(baseDir, "integration")
    process("git", "clone", remote.absolutePath, integration.absolutePath)
    git(integration, "merge", "--no-edit", "origin/$branch")
    git(integration, "push", "origin", "main")
    git(integration, "push", "origin", "--delete", branch)
}

private fun git(directory: File, vararg args: String) {
    process("git", "-C", directory.absolutePath, *args)
}

private fun process(vararg command: String) {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$output" }
}
