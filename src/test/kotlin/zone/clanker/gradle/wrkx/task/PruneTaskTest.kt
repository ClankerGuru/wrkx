package zone.clanker.gradle.wrkx.task

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.string.shouldContain
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.GitReference
import zone.clanker.gradle.wrkx.model.RepositoryUrl
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File

/**
 * Tests for [PruneTask] -- removes directories not in wrkx.json.
 *
 * Verifies:
 * - Orphaned directories are removed
 * - Known directories are preserved
 * - Empty repos directory succeeds with no-op
 * - Missing repos directory fails with clear error
 */
class PruneTaskTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("wrkx-prune", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        fun createContainer(
            vararg names: String,
        ): NamedDomainObjectContainer<WorkspaceRepository> {
            val project = ProjectBuilder.builder().build()
            val container =
                project.objects.domainObjectContainer(WorkspaceRepository::class.java) { name ->
                    project.objects.newInstance(WorkspaceRepository::class.java, name)
                }
            names.forEach { name ->
                container.register(name) { repo ->
                    repo.path.set(RepositoryUrl("org/$name"))
                    repo.category.set("")
                    repo.substitute.set(false)
                    repo.baseBranch.set(GitReference("main"))
                }
            }
            return container
        }

        given("repos directory with matching and orphaned directories") {

            `when`("prune is executed") {
                val repoDir = tempDir()
                File(repoDir, "lib-a").mkdirs()
                File(repoDir, "lib-b").mkdirs()
                val orphan = File(repoDir, "old-repo").apply { mkdirs() }

                val container = createContainer("libA", "libB")
                // Register with matching directory names
                container.getByName("libA").path.set(RepositoryUrl("org/lib-a"))
                container.getByName("libB").path.set(RepositoryUrl("org/lib-b"))

                val project = ProjectBuilder.builder().build()
                val task =
                    project.tasks
                        .register("wrkx-prune", PruneTask::class.java, container, repoDir)
                        .get()
                task.prune()

                then("orphan directory is removed") {
                    orphan.shouldNotExist()
                }

                then("known directories are preserved") {
                    File(repoDir, "lib-a").shouldExist()
                    File(repoDir, "lib-b").shouldExist()
                }

                repoDir.deleteRecursively()
            }
        }

        given("repos directory with no orphans") {

            `when`("prune is executed") {
                val repoDir = tempDir()
                File(repoDir, "lib-a").mkdirs()

                val container = createContainer("libA")
                container.getByName("libA").path.set(RepositoryUrl("org/lib-a"))

                val project = ProjectBuilder.builder().build()
                val task =
                    project.tasks
                        .register("wrkx-prune-noop", PruneTask::class.java, container, repoDir)
                        .get()
                task.prune()

                then("all directories are preserved") {
                    File(repoDir, "lib-a").shouldExist()
                }

                repoDir.deleteRecursively()
            }
        }

        given("repos directory is a file (listFiles returns null)") {

            `when`("prune is executed") {
                val baseDir = tempDir()
                val repoFile = File(baseDir, "repos-file")
                repoFile.writeText("not a directory")

                val container = createContainer("libA")
                container.getByName("libA").path.set(RepositoryUrl("org/lib-a"))

                val project = ProjectBuilder.builder().build()

                then("handles null listFiles gracefully") {
                    // repoFile.exists() is true but listFiles() returns null because
                    // it's a file not a directory -- tests the ?: emptyList() branch
                    val task =
                        project.tasks
                            .register(
                                "wrkx-prune-file",
                                PruneTask::class.java,
                                container,
                                repoFile,
                            ).get()
                    task.prune()
                }

                baseDir.deleteRecursively()
            }
        }

        given("repos directory that doesn't exist") {

            `when`("prune is executed") {
                val repoDir = File("/tmp/wrkx-nonexistent-${System.nanoTime()}")
                val container = createContainer("libA")

                then("fails with descriptive error") {
                    val project = ProjectBuilder.builder().build()
                    val ex =
                        shouldThrow<IllegalStateException> {
                            val task =
                                project.tasks
                                    .register(
                                        "wrkx-prune-missing",
                                        PruneTask::class.java,
                                        container,
                                        repoDir,
                                    ).get()
                            task.prune()
                        }
                    ex.message shouldContain "does not exist"
                    ex.message shouldContain "wrkx-clone"
                }
            }
        }
    })
