package zone.clanker.gradle.wrkx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

/**
 * Tests for [CloneTask] -- clones a single repo via `git clone`.
 *
 * Uses local bare git repos as clone sources (no network, no Docker).
 *
 * Verifies:
 * - Cloning from a local bare repo creates the expected directory
 * - Skips when the target directory already exists
 * - Checks out a non-default baseBranch after cloning
 * - Creates a local branch when baseBranch doesn't exist remotely
 */
class CloneTaskTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("wrkx-clone", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("a local bare git repo") {
            val baseDir = tempDir()
            val bareRepo = createBareRepo(baseDir, "test-repo")
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val project = ProjectBuilder.builder().build()

            `when`("clone is executed") {
                val repo = createTestRepo(project.objects, bareRepo.absolutePath)
                repo.clonePath.set(File(repoDir, repo.directoryName))

                val task =
                    project.tasks
                        .register("wrkx-clone-test", CloneTask::class.java, repo, repoDir)
                        .get()
                task.clone()

                then("the repo is cloned to the target directory") {
                    File(repoDir, "test-repo").shouldExist()
                    File(repoDir, "test-repo/.git").shouldExist()
                    File(repoDir, "test-repo/README.md").shouldExist()
                }
            }

            `when`("clone is executed but directory already exists") {
                val existing = File(repoDir, "existing-repo").apply { mkdirs() }
                val repo =
                    createTestRepo(project.objects, bareRepo.absolutePath, name = "existing-repo")
                repo.clonePath.set(existing)

                val task =
                    project.tasks
                        .register("wrkx-clone-existing", CloneTask::class.java, repo, repoDir)
                        .get()
                task.clone()

                then("the clone is skipped -- directory preserved, no git init") {
                    existing.shouldExist()
                    File(existing, ".git").shouldNotExist()
                }
            }

            `when`("clone with a non-default baseBranch that doesn't exist remotely") {
                val branchBareRepo = createBareRepo(baseDir, "branch-test")
                val branchRepoDir = File(baseDir, "branch-repos").apply { mkdirs() }
                val repository =
                    createTestRepo(
                        project.objects,
                        branchBareRepo.absolutePath,
                        baseBranch = "feature/test",
                    )
                repository.clonePath.set(File(branchRepoDir, repository.directoryName))

                val cloneWithBranchTask =
                    project.tasks
                        .register(
                            "wrkx-clone-branch",
                            CloneTask::class.java,
                            repository,
                            branchRepoDir,
                        ).get()
                cloneWithBranchTask.clone()

                then("creates the local branch") {
                    val clonedDirectory = File(branchRepoDir, repository.directoryName)
                    clonedDirectory.shouldExist()
                    val branchProcess =
                        ProcessBuilder(
                            "git", "-C", clonedDirectory.absolutePath,
                            "branch", "--show-current",
                        ).redirectErrorStream(true).start()
                    val currentBranch =
                        branchProcess.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                    branchProcess.waitFor()
                    currentBranch shouldContain "feature/test"
                }
            }

            baseDir.deleteRecursively()
        }
    })
