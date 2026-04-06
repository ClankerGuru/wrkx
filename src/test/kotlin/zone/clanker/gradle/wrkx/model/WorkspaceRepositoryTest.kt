package zone.clanker.gradle.wrkx.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import zone.clanker.gradle.wrkx.TestFactory

class WorkspaceRepositoryTest :
    BehaviorSpec({

        given("WorkspaceRepository properties") {

            `when`("toggling substitute") {
                val repo =
                    TestFactory.repo(
                        name = "repo",
                        path = "org/repo",
                        substitute = true,
                    )

                then("starts with configured value") {
                    repo.substitute.get().shouldBeTrue()
                }

                then("can be toggled") {
                    repo.substitute.set(false)
                    repo.substitute.get().shouldBeFalse()
                    repo.substitute.set(true)
                    repo.substitute.get().shouldBeTrue()
                }
            }

            `when`("reading repoName") {
                then("returns the name identifier") {
                    TestFactory
                        .repo(
                            name = "gort",
                            path = "git@github.com:org/my-lib.git",
                        ).repoName shouldBe "gort"
                }
            }

            `when`("reading directoryName") {
                then("derives from path") {
                    TestFactory
                        .repo(
                            name = "myLib",
                            path = "git@github.com:org/my-lib.git",
                        ).directoryName shouldBe "my-lib"
                }
            }

            `when`("reading getName (Named interface)") {
                then("returns the name identifier") {
                    val repo = TestFactory.repo(name = "myLib", path = "org/my-lib")
                    repo.name shouldBe "myLib"
                }
            }

            `when`("toggling enabled") {
                val repo = TestFactory.repo(name = "repo", path = "org/repo")

                then("defaults to false") {
                    repo.enabled.shouldBeFalse()
                }

                then("can be enabled") {
                    repo.enable(true)
                    repo.enabled.shouldBeTrue()
                }

                then("can be disabled") {
                    repo.enable(true)
                    repo.enable(false)
                    repo.enabled.shouldBeFalse()
                }

                then("enable with default parameter (no arg) enables") {
                    repo.enable()
                    repo.enabled.shouldBeTrue()
                }
            }
        }

        given("sanitizedBuildName") {

            `when`("name is normal") {
                then("derives from path directory name") {
                    TestFactory
                        .repo(
                            name = "myLib",
                            path = "org/my-lib",
                        ).sanitizedBuildName shouldBe "my-lib"
                }
            }

            `when`("path has uppercase") {
                then("lowercases") {
                    TestFactory
                        .repo(
                            name = "myLib",
                            path = "org/MyLib",
                        ).sanitizedBuildName shouldBe "mylib"
                }
            }

            `when`("path has spaces") {
                then("replaces with hyphens") {
                    TestFactory
                        .repo(
                            name = "myCoolProject",
                            path = "org/My Cool Project",
                        ).sanitizedBuildName shouldBe "my-cool-project"
                }
            }

            `when`("path has consecutive special chars") {
                then("collapses to single hyphen") {
                    TestFactory
                        .repo(
                            name = "myLib",
                            path = "org/my---lib",
                        ).sanitizedBuildName shouldBe "my-lib"
                    TestFactory
                        .repo(
                            name = "fooBar",
                            path = "org/foo__bar",
                        ).sanitizedBuildName shouldBe "foo-bar"
                }
            }

            `when`("path directory is all symbols") {
                then("throws IllegalArgumentException") {
                    shouldThrow<IllegalArgumentException> {
                        TestFactory
                            .repo(
                                name = "symbols",
                                path = "org/---",
                            ).sanitizedBuildName
                    }
                }
            }
        }
    })
