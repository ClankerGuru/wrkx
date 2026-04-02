package zone.clanker.gradle.wrkx.task

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.string.shouldContain
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.ArtifactId
import zone.clanker.gradle.wrkx.model.ArtifactSubstitution
import zone.clanker.gradle.wrkx.model.GitReference
import zone.clanker.gradle.wrkx.model.ProjectPath
import zone.clanker.gradle.wrkx.model.RepositoryUrl
import zone.clanker.gradle.wrkx.model.WorkspaceRepository
import java.io.File

/**
 * Tests for [StatusTask] -- generates `.wrkx/repos.md` from workspace repos.
 *
 * Verifies:
 * - Empty repos produce a "no repos" message
 * - Repos are listed with correct status (cloned/not cloned, substitute on/off)
 * - Category grouping works
 * - Machine-readable section is generated
 */
class StatusTaskTest :
    BehaviorSpec({

        fun tempDir(): File =
            File.createTempFile("wrkx-status", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        fun createContainer(): NamedDomainObjectContainer<WorkspaceRepository> {
            val project = ProjectBuilder.builder().build()
            return project.objects.domainObjectContainer(WorkspaceRepository::class.java) { name ->
                project.objects.newInstance(WorkspaceRepository::class.java, name)
            }
        }

        given("StatusTask with repos") {

            `when`("repos are registered with mixed status") {
                val repoDir = tempDir()
                val container = createContainer()

                // One repo "exists" on disk
                File(repoDir, "lib-a").mkdirs()

                container.register("libA") { repo ->
                    repo.path.set(RepositoryUrl("org/lib-a"))
                    repo.category.set("core")
                    repo.substitute.set(true)
                    repo.substitutions.set(
                        listOf(
                            ArtifactSubstitution(
                                ArtifactId("com.example:lib-a"),
                                ProjectPath("lib-a"),
                            ),
                        ),
                    )
                    repo.baseBranch.set(GitReference("main"))
                    repo.clonePath.set(File(repoDir, "lib-a"))
                }

                container.register("libB") { repo ->
                    repo.path.set(RepositoryUrl("org/lib-b"))
                    repo.category.set("tools")
                    repo.substitute.set(false)
                    repo.baseBranch.set(GitReference("develop"))
                    repo.clonePath.set(File(repoDir, "lib-b"))
                }

                val project = ProjectBuilder.builder().build()
                val task =
                    project.tasks
                        .register("wrkx-status", StatusTask::class.java, container, repoDir)
                        .get()
                task.generate()

                val output = File(project.projectDir, ".wrkx/repos.md")

                then("generates the output file") {
                    output.shouldExist()
                }

                then("header shows correct counts") {
                    val content = output.readText()
                    content shouldContain "2 repos"
                    content shouldContain "1 cloned on disk"
                    content shouldContain "1 with substitution"
                }

                then("lists both repos in the summary table") {
                    val content = output.readText()
                    content shouldContain "libA"
                    content shouldContain "libB"
                }

                then("shows clone status correctly") {
                    val content = output.readText()
                    // libA exists on disk
                    content shouldContain "| 1 | `libA` | `org/lib-a` | core | yes |"
                    // libB does not
                    content shouldContain "| 2 | `libB` | `org/lib-b` | tools | no |"
                }

                then("shows substitution info") {
                    val content = output.readText()
                    content shouldContain "`com.example:lib-a,lib-a`"
                }

                then("shows category breakdown") {
                    val content = output.readText()
                    content shouldContain "### core"
                    content shouldContain "### tools"
                }

                then("shows machine-readable config") {
                    val content = output.readText()
                    content shouldContain "name=libA"
                    content shouldContain "name=libB"
                    content shouldContain "substitute=true"
                    content shouldContain "substitute=false"
                }

                repoDir.deleteRecursively()
            }
        }

        given("StatusTask with empty container") {

            `when`("no repos are registered") {
                val repoDir = tempDir()
                val container = createContainer()
                val project = ProjectBuilder.builder().build()
                val task =
                    project.tasks
                        .register("wrkx-status", StatusTask::class.java, container, repoDir)
                        .get()
                task.generate()

                val output = File(project.projectDir, ".wrkx/repos.md")

                then("generates a file with no-repos message") {
                    output.shouldExist()
                    output.readText() shouldContain "No repos configured"
                }

                repoDir.deleteRecursively()
            }
        }
    })
