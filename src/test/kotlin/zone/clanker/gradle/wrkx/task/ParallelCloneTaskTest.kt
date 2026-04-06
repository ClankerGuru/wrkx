package zone.clanker.gradle.wrkx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.string.shouldContain
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.GitReference
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File

/**
 * Tests for [ParallelCloneTask] -- clones all repos in parallel.
 *
 * Uses local bare git repos (no network, no Docker).
 *
 * Verifies:
 * - Cloning multiple repos in parallel creates expected directories
 * - Skips repos whose target directory already exists
 * - Reports failure when a repo cannot be cloned
 * - Handles empty repo list gracefully
 */
class ParallelCloneTaskTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("wrkx-pclone", "").apply {
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

        given("multiple local bare git repos") {
            val baseDir = tempDir()
            val bareRepo1 = createBareRepo(baseDir, "repo-one")
            val bareRepo2 = createBareRepo(baseDir, "repo-two")
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val project = ProjectBuilder.builder().build()

            `when`("parallel clone is executed for all repos") {
                val repos = createReposContainer(project)
                repos.register("repoOne") { repo ->
                    repo.path.set(
                        zone.clanker.gradle.wrkx.model
                            .RepositoryUrl(bareRepo1.absolutePath),
                    )
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, repo.directoryName))
                }
                repos.register("repoTwo") { repo ->
                    repo.path.set(
                        zone.clanker.gradle.wrkx.model
                            .RepositoryUrl(bareRepo2.absolutePath),
                    )
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, repo.directoryName))
                }

                val task =
                    project.tasks
                        .register(
                            "wrkx-clone",
                            ParallelCloneTask::class.java,
                            repos,
                            repoDir,
                        ).get()
                task.cloneAll()

                then("both repos are cloned") {
                    File(repoDir, "repo-one").shouldExist()
                    File(repoDir, "repo-one/.git").shouldExist()
                    File(repoDir, "repo-two").shouldExist()
                    File(repoDir, "repo-two/.git").shouldExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("a repo that already exists on disk") {
            val baseDir = tempDir()
            val bareRepo = createBareRepo(baseDir, "existing-repo")
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()
            File(repoDir, "existing-repo").mkdirs()

            val project = ProjectBuilder.builder().build()

            `when`("parallel clone is executed") {
                val repos = createReposContainer(project)
                repos.register("existingRepo") { repo ->
                    repo.path.set(
                        zone.clanker.gradle.wrkx.model
                            .RepositoryUrl(bareRepo.absolutePath),
                    )
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, "existing-repo"))
                }

                val task =
                    project.tasks
                        .register(
                            "wrkx-clone-existing",
                            ParallelCloneTask::class.java,
                            repos,
                            repoDir,
                        ).get()
                task.cloneAll()

                then("skips the existing directory without error") {
                    File(repoDir, "existing-repo").shouldExist()
                }
            }

            baseDir.deleteRecursively()
        }

        given("an empty repos container") {
            val baseDir = tempDir()
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val project = ProjectBuilder.builder().build()

            `when`("parallel clone is executed") {
                val repos = createReposContainer(project)

                val task =
                    project.tasks
                        .register(
                            "wrkx-clone-empty",
                            ParallelCloneTask::class.java,
                            repos,
                            repoDir,
                        ).get()

                then("completes without error") {
                    task.cloneAll()
                }
            }

            baseDir.deleteRecursively()
        }

        given("a mix of valid and invalid repo URLs") {
            val baseDir = tempDir()
            val bareRepo = createBareRepo(baseDir, "valid-repo")
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val project = ProjectBuilder.builder().build()

            `when`("parallel clone is executed") {
                val repos = createReposContainer(project)
                repos.register("validRepo") { repo ->
                    repo.path.set(
                        zone.clanker.gradle.wrkx.model
                            .RepositoryUrl(bareRepo.absolutePath),
                    )
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, repo.directoryName))
                }
                repos.register("invalidRepo") { repo ->
                    repo.path.set(
                        zone.clanker.gradle.wrkx.model
                            .RepositoryUrl("/nonexistent/path/fake.git"),
                    )
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, "fake"))
                }

                then("reports the failure") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            val task =
                                project.tasks
                                    .register(
                                        "wrkx-clone-mixed",
                                        ParallelCloneTask::class.java,
                                        repos,
                                        repoDir,
                                    ).get()
                            task.cloneAll()
                        }
                    ex.message shouldContain "failed to clone"
                }
            }

            baseDir.deleteRecursively()
        }
    })
