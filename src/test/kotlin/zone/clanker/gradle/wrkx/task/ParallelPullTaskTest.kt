package zone.clanker.gradle.wrkx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.GitReference
import zone.clanker.gradle.wrkx.model.RepositoryUrl
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File

/**
 * Tests for [ParallelPullTask] -- pulls all repos in parallel.
 *
 * Uses local bare git repos (no network, no Docker).
 *
 * Verifies:
 * - Pull succeeds for multiple cloned repos with no upstream changes
 * - Pull fetches new commits pushed to a bare repo
 * - Fails with clear error when a repo directory doesn't exist
 * - Handles empty repo list gracefully
 */
class ParallelPullTaskTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("wrkx-ppull", "").apply {
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

        given("multiple cloned repos from local bare sources") {
            val baseDir = tempDir()
            val bareRepo1 = createBareRepo(baseDir, "pull-one")
            val bareRepo2 = createBareRepo(baseDir, "pull-two")
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            ProcessBuilder(
                "git", "clone", bareRepo1.absolutePath,
                File(repoDir, "pull-one").absolutePath,
            ).redirectErrorStream(true).start().waitFor()

            ProcessBuilder(
                "git", "clone", bareRepo2.absolutePath,
                File(repoDir, "pull-two").absolutePath,
            ).redirectErrorStream(true).start().waitFor()

            val project = ProjectBuilder.builder().build()

            `when`("parallel pull is executed with no new changes") {
                val repos = createReposContainer(project)
                repos.register("pullOne") { repo ->
                    repo.path.set(RepositoryUrl(bareRepo1.absolutePath))
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, "pull-one"))
                }
                repos.register("pullTwo") { repo ->
                    repo.path.set(RepositoryUrl(bareRepo2.absolutePath))
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, "pull-two"))
                }

                val task =
                    project.tasks
                        .register(
                            "wrkx-pull",
                            ParallelPullTask::class.java,
                            repos,
                            repoDir,
                        ).get()
                task.pullAll()

                then("succeeds without error") {
                    File(repoDir, "pull-one/README.md").readText() shouldContain "pull-one"
                    File(repoDir, "pull-two/README.md").readText() shouldContain "pull-two"
                }
            }

            `when`("a new commit is pushed and parallel pull is executed") {
                val pushDir = File(baseDir, "push-work")
                ProcessBuilder("git", "clone", bareRepo1.absolutePath, pushDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                File(pushDir, "new-file.txt").writeText("parallel pull content")
                ProcessBuilder("git", "-C", pushDir.absolutePath, "add", ".")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                ProcessBuilder("git", "-C", pushDir.absolutePath, "commit", "-m", "Add new file")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                ProcessBuilder("git", "-C", pushDir.absolutePath, "push")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                pushDir.deleteRecursively()

                val repos = createReposContainer(project)
                repos.register("pullOneUpdated") { repo ->
                    repo.path.set(RepositoryUrl(bareRepo1.absolutePath))
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, "pull-one"))
                }

                val task =
                    project.tasks
                        .register(
                            "wrkx-pull-updated",
                            ParallelPullTask::class.java,
                            repos,
                            repoDir,
                        ).get()
                task.pullAll()

                then("pulls the new commit") {
                    File(repoDir, "pull-one/new-file.txt").readText() shouldContain "parallel pull content"
                }
            }

            baseDir.deleteRecursively()
        }

        given("a repo that doesn't exist on disk") {
            val baseDir = tempDir()
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val project = ProjectBuilder.builder().build()

            `when`("parallel pull is executed") {
                val repos = createReposContainer(project)
                repos.register("missingRepo") { repo ->
                    repo.path.set(RepositoryUrl("org/missing"))
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, "missing"))
                }

                then("fails with a descriptive error") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            val task =
                                project.tasks
                                    .register(
                                        "wrkx-pull-missing",
                                        ParallelPullTask::class.java,
                                        repos,
                                        repoDir,
                                    ).get()
                            task.pullAll()
                        }
                    ex.message shouldContain "failed to pull"
                }
            }

            baseDir.deleteRecursively()
        }

        given("an empty repos container") {
            val baseDir = tempDir()
            val repoDir = File(baseDir, "repos")
            repoDir.mkdirs()

            val project = ProjectBuilder.builder().build()

            `when`("parallel pull is executed") {
                val repos = createReposContainer(project)

                val task =
                    project.tasks
                        .register(
                            "wrkx-pull-empty",
                            ParallelPullTask::class.java,
                            repos,
                            repoDir,
                        ).get()

                then("completes without error") {
                    task.pullAll()
                }
            }

            baseDir.deleteRecursively()
        }
    })
