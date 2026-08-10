package zone.clanker.gradle.wrkx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.WorkspaceLayout
import java.io.File

/** Real-Git tests proving the per-repository clone task only creates bare repositories. */
class CloneTaskTest :
    BehaviorSpec({
        fun tempDir(): File =
            File.createTempFile("wrkx-clone", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        fun gitOutput(vararg command: String): String =
            ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
                .let { process ->
                    val output =
                        process.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                    process.waitFor() shouldBe 0
                    output
                }

        given("a repository that has not been cloned") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "sample")
            val repoDir = File(baseDir, "workspace-repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            val task = project.tasks.register("wrkx-clone-sample", CloneTask::class.java, repo, repoDir).get()

            `when`("the clone task runs") {
                task.clone()

                then("it creates only a bare repository under bare") {
                    val bareDir = WorkspaceLayout.bareRepository(repoDir, repo)
                    bareDir.shouldExist()
                    gitOutput("git", "--git-dir=${bareDir.absolutePath}", "rev-parse", "--is-bare-repository") shouldBe
                        "true"
                    File(repoDir, repo.directoryName).shouldNotExist()
                    File(bareDir, ".git").shouldNotExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("an invalid existing bare-repository path") {
            val baseDir = tempDir()
            val remote = createBareRepo(baseDir, "invalid-target")
            val repoDir = File(baseDir, "workspace-repos")
            val project = ProjectBuilder.builder().build()
            val repo = createTestRepo(project.objects, remote.absolutePath)
            WorkspaceLayout.bareRepository(repoDir, repo).mkdirs()
            val task = project.tasks.register("wrkx-clone-invalid", CloneTask::class.java, repo, repoDir).get()

            then("the task fails instead of overwriting the directory") {
                val error = shouldThrow<IllegalStateException> { task.clone() }
                error.message shouldContain "not a bare Git repository"
            }

            baseDir.deleteRecursively()
        }
    })
