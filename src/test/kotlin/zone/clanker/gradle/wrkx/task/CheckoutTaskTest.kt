package zone.clanker.gradle.wrkx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

/**
 * Tests for [CheckoutTask] -- checks out a branch via `git checkout`.
 *
 * Uses local bare git repos (no network, no Docker).
 *
 * Verifies:
 * - Checkout succeeds on a cloned repo with a valid branch
 * - Fails with clear error when working directory is dirty
 * - Creates workingBranch from baseBranch if it doesn't exist
 * - Warns and skips when directory doesn't exist
 */
class CheckoutTaskTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("wrkx-checkout", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        given("a cloned repo with a branch") {
            val baseDir = tempDir()
            val bareRepo = createBareRepo(baseDir, "checkout-test")
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val cloneDir = File(repoDir, "checkout-test")
            ProcessBuilder("git", "clone", bareRepo.absolutePath, cloneDir.absolutePath)
                .redirectErrorStream(true)
                .start()
                .waitFor()

            // Create a branch in the clone
            ProcessBuilder("git", "-C", cloneDir.absolutePath, "checkout", "-b", "feature/test")
                .redirectErrorStream(true)
                .start()
                .waitFor()
            ProcessBuilder("git", "-C", cloneDir.absolutePath, "checkout", "main")
                .redirectErrorStream(true)
                .start()
                .waitFor()

            val project = ProjectBuilder.builder().build()

            `when`("checkout is executed for an existing branch via baseBranch") {
                val repo =
                    createTestRepo(
                        project.objects,
                        bareRepo.absolutePath,
                        name = "checkout-test",
                        baseBranch = "feature/test",
                    )
                repo.clonePath.set(cloneDir)

                val task =
                    project.tasks
                        .register(
                            "wrkx-checkout-test",
                            CheckoutTask::class.java,
                            repo,
                            repoDir,
                            "",
                        ).get()
                task.checkout()

                then("switches to the correct branch") {
                    val branch =
                        ProcessBuilder("git", "-C", cloneDir.absolutePath, "branch", "--show-current")
                            .redirectErrorStream(true)
                            .start()
                    val branchName =
                        branch.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                    branch.waitFor()
                    branchName shouldBe "feature/test"
                }
            }

            `when`("checkout with workingBranch that doesn't exist yet") {
                // Reset to main first
                ProcessBuilder("git", "-C", cloneDir.absolutePath, "checkout", "main")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()

                val repo =
                    createTestRepo(
                        project.objects,
                        bareRepo.absolutePath,
                        name = "checkout-test",
                        baseBranch = "main",
                    )
                repo.clonePath.set(cloneDir)

                val task =
                    project.tasks
                        .register(
                            "wrkx-checkout-working",
                            CheckoutTask::class.java,
                            repo,
                            repoDir,
                            "feature/new-branch",
                        ).get()
                task.checkout()

                then("creates the new branch from baseBranch") {
                    val branch =
                        ProcessBuilder("git", "-C", cloneDir.absolutePath, "branch", "--show-current")
                            .redirectErrorStream(true)
                            .start()
                    val branchName =
                        branch.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                    branch.waitFor()
                    branchName shouldBe "feature/new-branch"
                }
            }

            `when`("checkout with dirty working directory") {
                // Reset to main first
                ProcessBuilder("git", "-C", cloneDir.absolutePath, "checkout", "main")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()

                // Create an uncommitted file
                File(cloneDir, "dirty-file.txt").writeText("dirty")

                val repo =
                    createTestRepo(
                        project.objects,
                        bareRepo.absolutePath,
                        name = "checkout-test",
                        baseBranch = "main",
                    )
                repo.clonePath.set(cloneDir)

                then("fails with descriptive error about dirty working directory") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            val task =
                                project.tasks
                                    .register(
                                        "wrkx-checkout-dirty",
                                        CheckoutTask::class.java,
                                        repo,
                                        repoDir,
                                        "",
                                    ).get()
                            task.checkout()
                        }
                    ex.message shouldContain "dirty"
                    ex.message shouldContain "dirty-file.txt"
                }

                // Clean up dirty file
                File(cloneDir, "dirty-file.txt").delete()
            }

            baseDir.deleteRecursively()
        }

        given("a repo that doesn't exist on disk") {
            val baseDir = tempDir()
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val project = ProjectBuilder.builder().build()
            val repo =
                createTestRepo(project.objects, "org/missing", name = "missing", baseBranch = "main")
            repo.clonePath.set(File(repoDir, "missing"))

            `when`("checkout is executed") {
                then("warns and skips without failing") {
                    val task =
                        project.tasks
                            .register(
                                "wrkx-checkout-missing",
                                CheckoutTask::class.java,
                                repo,
                                repoDir,
                                "",
                            ).get()
                    // Should not throw -- just warn and skip
                    task.checkout()
                }
            }

            baseDir.deleteRecursively()
        }
    })
