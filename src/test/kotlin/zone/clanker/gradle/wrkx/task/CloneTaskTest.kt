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

            `when`("clone with a non-default baseBranch that exists on the remote") {
                val existsBareRepo = createBareRepo(baseDir, "branch-exists-test")
                val existsBranchRepoDir = File(baseDir, "branch-exists-repos").apply { mkdirs() }

                // Push a "develop" branch to the bare repo
                val setupDir = File(baseDir, "branch-exists-setup")
                ProcessBuilder("git", "clone", existsBareRepo.absolutePath, setupDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                ProcessBuilder("git", "-C", setupDir.absolutePath, "checkout", "-b", "develop")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                File(setupDir, "dev.txt").writeText("develop branch")
                ProcessBuilder("git", "-C", setupDir.absolutePath, "add", ".")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                ProcessBuilder("git", "-C", setupDir.absolutePath, "commit", "-m", "Add dev file")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                ProcessBuilder("git", "-C", setupDir.absolutePath, "push", "origin", "develop")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                setupDir.deleteRecursively()

                val repository =
                    createTestRepo(
                        project.objects,
                        existsBareRepo.absolutePath,
                        baseBranch = "develop",
                    )
                repository.clonePath.set(File(existsBranchRepoDir, repository.directoryName))

                val cloneExistsTask =
                    project.tasks
                        .register(
                            "wrkx-clone-branch-exists",
                            CloneTask::class.java,
                            repository,
                            existsBranchRepoDir,
                        ).get()
                cloneExistsTask.clone()

                then("checks out the existing remote branch") {
                    val clonedDirectory = File(existsBranchRepoDir, repository.directoryName)
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
                    currentBranch shouldContain "develop"
                }

                existsBranchRepoDir.deleteRecursively()
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

            `when`("clone with baseBranch and git checkout -b fails") {
                val failBareRepo = createBareRepo(baseDir, "fail-create-branch")
                // Corrupt the bare repo by removing HEAD so checkout -b fails
                // after cloning an empty/broken state
                File(failBareRepo, "HEAD").delete()
                File(failBareRepo, "refs/heads").listFiles()?.forEach { it.delete() }
                val failRepoDir = File(baseDir, "fail-create-repos").apply { mkdirs() }

                val repository =
                    createTestRepo(
                        project.objects,
                        failBareRepo.absolutePath,
                        baseBranch = "feature/test-branch",
                        name = "failCreateBranch",
                    )
                repository.clonePath.set(File(failRepoDir, "fail-create-branch"))

                val cloneTask =
                    project.tasks
                        .register(
                            "wrkx-clone-fail-branch",
                            CloneTask::class.java,
                            repository,
                            failRepoDir,
                        ).get()

                then("fails with an error from git") {
                    io.kotest.assertions.throwables.shouldThrow<Exception> {
                        cloneTask.clone()
                    }
                }

                failRepoDir.deleteRecursively()
            }

            baseDir.deleteRecursively()
        }
    })
