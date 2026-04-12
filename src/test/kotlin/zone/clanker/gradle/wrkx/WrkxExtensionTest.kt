package zone.clanker.gradle.wrkx

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.gradle.api.Action
import org.gradle.api.initialization.Settings
import org.gradle.api.model.ObjectFactory
import org.gradle.testfixtures.ProjectBuilder
import zone.clanker.gradle.wrkx.model.ArtifactId
import zone.clanker.gradle.wrkx.model.ArtifactSubstitution
import zone.clanker.gradle.wrkx.model.ProjectPath
import zone.clanker.gradle.wrkx.model.RepositoryUrl
import java.io.File

class WrkxExtensionTest :
    BehaviorSpec({

        val objects: ObjectFactory =
            ProjectBuilder.builder().build().objects

        fun createExtension(settings: Settings = mockk(relaxed = true)): Wrkx.SettingsExtension =
            objects.newInstance(Wrkx.SettingsExtension::class.java, settings)

        fun Wrkx.SettingsExtension.addRepo(
            name: String,
            path: String,
            cloneDir: File? = null,
        ) {
            repos.register(name) { repo ->
                repo.path.set(RepositoryUrl(path))
                if (cloneDir != null) {
                    repo.clonePath.set(cloneDir)
                }
            }
        }

        given("enableAll and disableAll") {

            `when`("enableAll is called") {
                val ext = createExtension()
                ext.addRepo("alpha", "org/alpha")
                ext.addRepo("beta", "org/beta")
                ext.enableAll()

                then("all repos are enabled") {
                    ext.repos
                        .getByName("alpha")
                        .enabled
                        .shouldBeTrue()
                    ext.repos
                        .getByName("beta")
                        .enabled
                        .shouldBeTrue()
                }
            }

            `when`("disableAll is called after enableAll") {
                val ext = createExtension()
                ext.addRepo("alpha", "org/alpha")
                ext.addRepo("beta", "org/beta")
                ext.enableAll()
                ext.disableAll()

                then("all repos are disabled") {
                    ext.repos
                        .getByName("alpha")
                        .enabled
                        .shouldBeFalse()
                    ext.repos
                        .getByName("beta")
                        .enabled
                        .shouldBeFalse()
                }
            }
        }

        given("enable(vararg)") {

            `when`("specific repos are enabled") {
                val ext = createExtension()
                ext.addRepo("alpha", "org/alpha")
                ext.addRepo("beta", "org/beta")
                ext.addRepo("gamma", "org/gamma")

                val alpha = ext.repos.getByName("alpha")
                val gamma = ext.repos.getByName("gamma")
                ext.enable(alpha, gamma)

                then("only those repos are enabled") {
                    ext.repos
                        .getByName("alpha")
                        .enabled
                        .shouldBeTrue()
                    ext.repos
                        .getByName("beta")
                        .enabled
                        .shouldBeFalse()
                    ext.repos
                        .getByName("gamma")
                        .enabled
                        .shouldBeTrue()
                }
            }
        }

        given("operator get") {

            `when`("repo exists") {
                val ext = createExtension()
                ext.addRepo("gort", "org/gort")

                then("returns the repo") {
                    ext["gort"].repoName shouldBe "gort"
                }
            }

            `when`("repo does not exist") {
                val ext = createExtension()

                then("throws IllegalStateException") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            ext["missing"]
                        }
                    ex.message shouldContain "not found"
                    ex.message shouldContain "wrkx.json"
                }
            }
        }

        given("repos(action)") {

            `when`("action enables a repo by name") {
                val ext = createExtension()
                ext.addRepo("gort", "org/gort")

                ext.repos(
                    Action { container ->
                        container.getByName("gort").enable(true)
                    },
                )

                then("the repo is enabled") {
                    ext.repos
                        .getByName("gort")
                        .enabled
                        .shouldBeTrue()
                }
            }
        }

        given("workingBranch") {

            `when`("default") {
                val ext = createExtension()

                then("is null") {
                    ext.workingBranch shouldBe null
                }
            }

            `when`("set to a value") {
                val ext = createExtension()
                ext.workingBranch = "feature/test"

                then("returns the set value") {
                    ext.workingBranch shouldBe "feature/test"
                }
            }
        }

        given("checkForDuplicateBuildNames") {

            `when`("no duplicates") {
                val ext = createExtension()
                ext.addRepo("alpha", "org/alpha")
                ext.addRepo("beta", "org/beta")

                then("does not throw") {
                    ext.checkForDuplicateBuildNames(ext.repos.toList())
                }
            }

            `when`("duplicates exist") {
                val ext = createExtension()
                ext.addRepo("alpha", "org/shared-name")
                ext.addRepo("beta", "other/shared-name")

                then("throws IllegalStateException with details") {
                    val ex =
                        shouldThrow<IllegalStateException> {
                            ext.checkForDuplicateBuildNames(ext.repos.toList())
                        }
                    ex.message shouldContain "Duplicate sanitized build names"
                    ex.message shouldContain "shared-name"
                }
            }

            `when`("empty list") {
                val ext = createExtension()

                then("does not throw") {
                    ext.checkForDuplicateBuildNames(emptyList())
                }
            }
        }

        given("includeEnabled") {

            `when`("no repos are enabled") {
                val settings = mockk<Settings>(relaxed = true)
                val ext = createExtension(settings)
                ext.addRepo("alpha", "org/alpha")

                then("includeEnabled does not call includeBuild") {
                    ext.includeEnabled()
                    verify(exactly = 0) { settings.includeBuild(any<File>(), any()) }
                }
            }

            `when`("enabled repo directory does not exist") {
                val settings = mockk<Settings>(relaxed = true)
                val ext = createExtension(settings)
                val tmpDir =
                    File.createTempFile("wrkx-test", "").apply {
                        delete()
                    }
                ext.addRepo("alpha", "org/alpha", cloneDir = tmpDir)
                ext.repos.getByName("alpha").enable(true)

                then("prints a warning and does not call includeBuild") {
                    ext.includeEnabled()
                    verify(exactly = 0) { settings.includeBuild(any<File>(), any()) }
                }
            }

            `when`("enabled repo directory exists") {
                val settings = mockk<Settings>(relaxed = true)
                val ext = objects.newInstance(Wrkx.SettingsExtension::class.java, settings)
                val tmpDir =
                    File.createTempFile("wrkx-repo", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                ext.addRepo("alpha", "org/alpha", cloneDir = tmpDir)
                ext.repos.getByName("alpha").enable(true)

                then("calls settings.includeBuild") {
                    ext.includeEnabled()
                    verify { settings.includeBuild(tmpDir.canonicalFile, any()) }
                }

                tmpDir.deleteRecursively()
            }
        }

        given("includeRepo") {

            `when`("repo has substitutions enabled") {
                val settings = mockk<Settings>(relaxed = true)
                val ext = objects.newInstance(Wrkx.SettingsExtension::class.java, settings)
                val tmpDir =
                    File.createTempFile("wrkx-sub", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }

                ext.repos.register("lib") { repo ->
                    repo.path.set(RepositoryUrl("org/lib"))
                    repo.clonePath.set(tmpDir)
                    repo.substitute.set(true)
                    repo.substitutions.set(
                        listOf(
                            ArtifactSubstitution(
                                ArtifactId("com.example:lib"),
                                ProjectPath("lib"),
                            ),
                        ),
                    )
                }

                val repo = ext.repos.getByName("lib")

                then("calls includeBuild with substitution config") {
                    ext.includeRepo(repo)
                    verify { settings.includeBuild(tmpDir.canonicalFile, any()) }
                }

                then("invokes the includeBuild action to wire spec name and substitutions") {
                    val actionSlot = slot<Action<org.gradle.api.initialization.ConfigurableIncludedBuild>>()
                    verify { settings.includeBuild(tmpDir.canonicalFile, capture(actionSlot)) }

                    val spec = mockk<org.gradle.api.initialization.ConfigurableIncludedBuild>(relaxed = true)
                    every { spec.dependencySubstitution(any()) } answers {
                        firstArg<Action<org.gradle.api.artifacts.DependencySubstitutions>>().execute(
                            mockk(relaxed = true),
                        )
                    }

                    actionSlot.captured.execute(spec)

                    verify { spec.name = "lib" }
                    verify { spec.dependencySubstitution(any()) }
                }

                tmpDir.deleteRecursively()
            }

            `when`("repo has substitute=false") {
                val settings = mockk<Settings>(relaxed = true)
                val ext = objects.newInstance(Wrkx.SettingsExtension::class.java, settings)
                val tmpDir =
                    File.createTempFile("wrkx-nosub", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }

                ext.repos.register("lib") { repo ->
                    repo.path.set(RepositoryUrl("org/lib"))
                    repo.clonePath.set(tmpDir)
                    repo.substitute.set(false)
                }

                val repo = ext.repos.getByName("lib")

                then("calls includeBuild without substitution") {
                    ext.includeRepo(repo)
                    verify { settings.includeBuild(tmpDir.canonicalFile, any()) }
                }

                then("invokes the action but does not configure substitutions") {
                    val actionSlot = slot<Action<org.gradle.api.initialization.ConfigurableIncludedBuild>>()
                    verify { settings.includeBuild(tmpDir.canonicalFile, capture(actionSlot)) }

                    val spec = mockk<org.gradle.api.initialization.ConfigurableIncludedBuild>(relaxed = true)
                    actionSlot.captured.execute(spec)

                    verify { spec.name = "lib" }
                    verify(exactly = 0) { spec.dependencySubstitution(any()) }
                }

                tmpDir.deleteRecursively()
            }
        }

        given("includeRepo idempotency") {

            `when`("includeRepo is called twice for the same repo") {
                val settings = mockk<Settings>(relaxed = true)
                val ext = objects.newInstance(Wrkx.SettingsExtension::class.java, settings)
                val tmpDir =
                    File.createTempFile("wrkx-idem", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }

                ext.repos.register("lib") { repo ->
                    repo.path.set(RepositoryUrl("org/lib"))
                    repo.clonePath.set(tmpDir)
                    repo.substitute.set(false)
                }

                val repo = ext.repos.getByName("lib")

                then("calls settings.includeBuild exactly once") {
                    ext.includeRepo(repo)
                    ext.includeRepo(repo)
                    verify(exactly = 1) { settings.includeBuild(tmpDir.canonicalFile, any()) }
                }

                tmpDir.deleteRecursively()
            }
        }

        given("includeRepo early return") {

            `when`("clonePath is not set") {
                val settings = mockk<Settings>(relaxed = true)
                val ext = objects.newInstance(Wrkx.SettingsExtension::class.java, settings)

                ext.repos.register("lib") { repo ->
                    repo.path.set(RepositoryUrl("org/lib"))
                    repo.substitute.set(false)
                }

                val repo = ext.repos.getByName("lib")

                then("does not call settings.includeBuild") {
                    ext.includeRepo(repo)
                    verify(exactly = 0) { settings.includeBuild(any<File>(), any()) }
                }
            }
        }

        given("repos container factory") {

            `when`("a repo is registered via the container") {
                val ext = createExtension()
                ext.repos.register("test") { }

                then("has default conventions") {
                    val repo = ext.repos.getByName("test")
                    repo.substitute.get().shouldBeFalse()
                    repo.baseBranch.get().value shouldBe "main"
                    repo.category.get() shouldBe ""
                }
            }

            `when`("multiple repos are registered") {
                val ext = createExtension()
                ext.addRepo("a", "org/a")
                ext.addRepo("b", "org/b")
                ext.addRepo("c", "org/c")

                then("all repos are in the container") {
                    ext.repos shouldHaveSize 3
                }
            }
        }
    })
