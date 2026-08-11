package zone.clanker.gradle.wrkx

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.mockk.mockk
import org.gradle.api.Task
import org.gradle.api.file.BuildLayout
import org.gradle.api.initialization.Settings
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.RepositoryUrl
import zone.clanker.gradle.wrkx.task.CheckoutTask
import zone.clanker.gradle.wrkx.task.CloneTask
import zone.clanker.gradle.wrkx.task.PruneTask
import zone.clanker.gradle.wrkx.task.PullTask
import zone.clanker.gradle.wrkx.task.StatusTask
import zone.clanker.gradle.wrkx.task.createBareRepo
import java.io.File

class WrkxTaskActionsTest :
    BehaviorSpec({
        given("registered tasks backed by a local remote") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "task-actions")
            val disabledRemote = createBareRepo(baseDir, "disabled-actions")
            val repoDir = File(baseDir, "repos")
            val project = ProjectBuilder.builder().withProjectDir(File(baseDir, "workspace").apply { mkdirs() }).build()
            val plugin =
                project.objects.newInstance(
                    Wrkx.SettingsPlugin::class.java,
                    project.providers,
                    mockk<BuildLayout>(relaxed = true),
                )
            val extension =
                project.objects.newInstance(
                    Wrkx.SettingsExtension::class.java,
                    mockk<Settings>(relaxed = true),
                )
            extension.workingBranch = "feature/task-actions"
            extension.repos.register("taskActions") { repo ->
                repo.path.set(RepositoryUrl(remote.absolutePath))
                repo.enable()
            }
            extension.repos.register("disabledActions") { repo ->
                repo.path.set(RepositoryUrl(disabledRemote.absolutePath))
            }

            with(plugin) {
                project.registerCatalogTask()
                project.registerPerRepoTasks(extension, repoDir)
                project.registerLifecycleTasks(extension, repoDir)
                project.registerUtilityTasks(extension, repoDir)
            }

            `when`("every successful task action runs") {
                execute(project.tasks.getByName(Wrkx.TASK_CATALOG))
                (project.tasks.getByName("${Wrkx.TASK_CLONE}-task-actions") as CloneTask).clone()
                execute(project.tasks.getByName(Wrkx.TASK_CLONE))
                execute(project.tasks.getByName("${Wrkx.TASK_FETCH}-task-actions"))
                execute(project.tasks.getByName(Wrkx.TASK_FETCH))
                execute(project.tasks.getByName(Wrkx.TASK_WORKTREE))
                execute(project.tasks.getByName("${Wrkx.TASK_WORKTREE}-task-actions"))
                (project.tasks.getByName("${Wrkx.TASK_CHECKOUT}-task-actions") as CheckoutTask).checkout()
                execute(project.tasks.getByName(Wrkx.TASK_CHECKOUT))
                (project.tasks.getByName("${Wrkx.TASK_PULL}-task-actions") as PullTask).pull()
                execute(project.tasks.getByName(Wrkx.TASK_PULL))
                execute(project.tasks.getByName("${Wrkx.TASK_PRUNE}-task-actions"))
                (project.tasks.getByName(Wrkx.TASK_PRUNE) as PruneTask).prune()
                (project.tasks.getByName(Wrkx.TASK_STATUS) as StatusTask).generate()

                then("the worktree and status report exist") {
                    File(repoDir, "feature/task-actions/task-actions/README.md").shouldExist()
                    File(project.projectDir, ".wrkx/repos.md").shouldExist()
                }

                then("clone includes disabled repositories but worktree operations do not") {
                    File(repoDir, "bare/disabled-actions.git/HEAD").shouldExist()
                    File(repoDir, "feature/task-actions/disabled-actions").shouldNotExist()
                }

                then("aggregate delete removes only the selected branch worktrees") {
                    execute(project.tasks.getByName(Wrkx.TASK_WORKTREE_DELETE))
                    File(repoDir, "feature/task-actions/task-actions").shouldNotExist()
                    File(repoDir, "bare/task-actions.git/HEAD").shouldExist()
                }

                then("per-repository delete is an explicit override") {
                    execute(project.tasks.getByName("${Wrkx.TASK_WORKTREE_DELETE}-task-actions"))
                    File(repoDir, "feature/task-actions/task-actions").shouldNotExist()
                }
            }

            `when`("a worktree task runs without a selected branch") {
                extension.workingBranch = null

                then("it fails before changing repositories") {
                    shouldThrow<IllegalStateException> {
                        execute(project.tasks.getByName("${Wrkx.TASK_WORKTREE}-task-actions"))
                    }
                }
            }

            baseDir.deleteRecursively()
        }
    })

private fun tempDir(): File =
    File.createTempFile("wrkx-task-actions", "").apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

private fun execute(task: Task) {
    task.actions.forEach { action -> action.execute(task) }
}
