package zone.clanker.gradle.wrkx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.GitReference
import zone.clanker.gradle.wrkx.model.RepositoryUrl
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File

/**
 * Tests for [ParallelCheckoutTask] -- checks out branches in all repos in parallel.
 *
 * Uses local bare git repos (no network, no Docker).
 *
 * Verifies:
 * - Checkout succeeds for multiple cloned repos with valid branches
 * - Creates workingBranch from baseBranch when it doesn't exist
 * - Fails with clear error when working directory is dirty
 * - Skips repos that are not cloned
 * - Handles empty repo list gracefully
 */
class ParallelCheckoutTaskTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("wrkx-pcheckout", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        fun createReposContainer(
            project: org.gradle.api.Project,
        ): NamedDomainObjectContainer<WorkspaceRepository> =
            project.objects.domainObjectContainer(WorkspaceRepository::class.java) { name ->
                project.objects.newInstance(WorkspaceRepository::class.java, name).apply {
                    substitute.convention(false)
                    baseBranch.convention(GitReference("main"))
                    category.convention("")
                }
            }

        fun currentBranch(dir: File): String {
            val process =
                ProcessBuilder("git", "-C", dir.absolutePath, "branch", "--show-current")
                    .redirectErrorStream(true)
                    .start()
            val name =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            process.waitFor()
            return name
        }

        given("multiple cloned repos with branches") {
            val baseDir = tempDir()
            val bareRepo1 = createBareRepo(baseDir, "co-one")
            val bareRepo2 = createBareRepo(baseDir, "co-two")
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val clone1 = File(repoDir, "co-one")
            val clone2 = File(repoDir, "co-two")
            ProcessBuilder("git", "clone", bareRepo1.absolutePath, clone1.absolutePath)
                .redirectErrorStream(true)
                .start()
                .waitFor()
            ProcessBuilder("git", "clone", bareRepo2.absolutePath, clone2.absolutePath)
                .redirectErrorStream(true)
                .start()
                .waitFor()

            // Create a feature branch in clone1
            ProcessBuilder("git", "-C", clone1.absolutePath, "checkout", "-b", "feature/test")
                .redirectErrorStream(true)
                .start()
                .waitFor()
            ProcessBuilder("git", "-C", clone1.absolutePath, "checkout", "main")
                .redirectErrorStream(true)
                .start()
                .waitFor()

            val project = ProjectBuilder.builder().build()

            `when`("parallel checkout with baseBranch") {
                val repos = createReposContainer(project)
                repos.register("coOne") { repo ->
                    repo.path.set(RepositoryUrl(bareRepo1.absolutePath))
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(clone1)
                }
                repos.register("coTwo") { repo ->
                    repo.path.set(RepositoryUrl(bareRepo2.absolutePath))
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(clone2)
                }

                val task =
                    project.tasks
                        .register(
                            "wrkx-checkout",
                            ParallelCheckoutTask::class.java,
                            repos,
                            repoDir,
                            "",
                        ).get()
                task.checkoutAll()

                then("both repos are on their baseBranch") {
                    currentBranch(clone1) shouldBe "main"
                    currentBranch(clone2) shouldBe "main"
                }
            }

            `when`("parallel checkout with workingBranch that doesn't exist") {
                // Reset both to main
                ProcessBuilder("git", "-C", clone1.absolutePath, "checkout", "main")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                ProcessBuilder("git", "-C", clone2.absolutePath, "checkout", "main")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()

                val repos = createReposContainer(project)
                repos.register("coOneWorking") { repo ->
                    repo.path.set(RepositoryUrl(bareRepo1.absolutePath))
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(clone1)
                }
                repos.register("coTwoWorking") { repo ->
                    repo.path.set(RepositoryUrl(bareRepo2.absolutePath))
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(clone2)
                }

                val task =
                    project.tasks
                        .register(
                            "wrkx-checkout-working",
                            ParallelCheckoutTask::class.java,
                            repos,
                            repoDir,
                            "feature/parallel-new",
                        ).get()
                task.checkoutAll()

                then("creates the new branch in both repos") {
                    currentBranch(clone1) shouldBe "feature/parallel-new"
                    currentBranch(clone2) shouldBe "feature/parallel-new"
                }
            }

            `when`("parallel checkout with dirty working directory") {
                // Reset to main and clean up
                ProcessBuilder("git", "-C", clone1.absolutePath, "checkout", "main")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()

                File(clone1, "dirty-file.txt").writeText("dirty content")

                val repos = createReposContainer(project)
                repos.register("coDirty") { repo ->
                    repo.path.set(RepositoryUrl(bareRepo1.absolutePath))
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(clone1)
                }

                then("fails with descriptive error about dirty working directory") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            val task =
                                project.tasks
                                    .register(
                                        "wrkx-checkout-dirty",
                                        ParallelCheckoutTask::class.java,
                                        repos,
                                        repoDir,
                                        "",
                                    ).get()
                            task.checkoutAll()
                        }
                    ex.message shouldContain "failed to checkout"
                }

                File(clone1, "dirty-file.txt").delete()
            }

            baseDir.deleteRecursively()
        }

        given("a repo that doesn't exist on disk") {
            val baseDir = tempDir()
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val project = ProjectBuilder.builder().build()

            `when`("parallel checkout is executed") {
                val repos = createReposContainer(project)
                repos.register("missingRepo") { repo ->
                    repo.path.set(RepositoryUrl("org/missing"))
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, "missing"))
                }

                val task =
                    project.tasks
                        .register(
                            "wrkx-checkout-missing",
                            ParallelCheckoutTask::class.java,
                            repos,
                            repoDir,
                            "",
                        ).get()

                then("skips without failing") {
                    task.checkoutAll()
                }
            }

            baseDir.deleteRecursively()
        }

        given("an empty repos container") {
            val baseDir = tempDir()
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val project = ProjectBuilder.builder().build()

            `when`("parallel checkout is executed") {
                val repos = createReposContainer(project)

                val task =
                    project.tasks
                        .register(
                            "wrkx-checkout-empty",
                            ParallelCheckoutTask::class.java,
                            repos,
                            repoDir,
                            "",
                        ).get()

                then("completes without error") {
                    task.checkoutAll()
                }
            }

            baseDir.deleteRecursively()
        }
    })
