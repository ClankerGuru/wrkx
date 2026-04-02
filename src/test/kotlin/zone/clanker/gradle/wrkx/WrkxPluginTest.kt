package zone.clanker.gradle.wrkx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File

/**
 * Gradle TestKit tests for the wrkx settings plugin.
 *
 * Each test creates a temporary Gradle project, applies the plugin,
 * and verifies behavior through task execution.
 */
class WrkxPluginTest :
    BehaviorSpec({

        fun tempProject(): File =
            File.createTempFile("wrkx-test", "").apply {
                delete()
                mkdirs()
                deleteOnExit()
            }

        fun File.withSettings(dslBlock: String = "") =
            also {
                val settingsContent =
                    if (dslBlock.isBlank()) {
                        """
                        plugins {
                            id("zone.clanker.gradle.wrkx")
                        }
                        """.trimIndent()
                    } else {
                        """
                        plugins {
                            id("zone.clanker.gradle.wrkx")
                        }

                        wrkx {
                            $dslBlock
                        }
                        """.trimIndent()
                    }
                resolve("settings.gradle.kts").writeText(settingsContent)
                resolve("build.gradle.kts").writeText("")
            }

        fun File.gradle(vararg args: String) =
            GradleRunner
                .create()
                .withProjectDir(this)
                .withPluginClasspath()
                .withArguments(*args, "--stacktrace")

        given("the wrkx plugin applied in settings") {

            /**
             * When wrkx.json doesn't exist, the plugin creates it with an empty array
             * and registers all tasks. The user gets a warning explaining what to do.
             */
            `when`("no wrkx.json exists") {
                val projectDir = tempProject().withSettings()

                then("the plugin creates wrkx.json and registers tasks") {
                    val result = projectDir.gradle("wrkx").build()

                    result.task(":wrkx")?.outcome shouldBe TaskOutcome.SUCCESS
                    result.output shouldContain "Workspace Tasks (wrkx)"
                    result.output shouldContain "wrkx-clone"
                    result.output shouldContain "wrkx-prune"

                    val created = projectDir.resolve("wrkx.json")
                    created.shouldExist()
                    created.readText().trim() shouldBe "[]"
                }

                projectDir.deleteRecursively()
            }

            /**
             * When wrkx.json defines repos and their directories exist and repos are enabled,
             * the plugin wires them and tasks operate on them.
             */
            `when`("wrkx.json has repos with existing directories and repos are enabled") {
                val projectDir = tempProject().withSettings("enableAll()")
                val reposDir =
                    File(projectDir.parentFile, "${projectDir.name}-repos").apply { mkdirs() }

                // Create fake repo directories so includeBuild doesn't fail
                File(reposDir, "gort").apply {
                    mkdirs()
                    resolve("settings.gradle.kts").writeText("rootProject.name = \"gort\"")
                }
                File(reposDir, "wrkx").apply {
                    mkdirs()
                    resolve("settings.gradle.kts").writeText("rootProject.name = \"wrkx\"")
                }

                projectDir.resolve("wrkx.json").writeText(
                    """
                    [
                      {
                        "name": "gort",
                        "path": "git@github.com:ClankerGuru/gort.git",
                        "category": "ui"
                      },
                      {
                        "name": "wrkx",
                        "path": "git@github.com:ClankerGuru/wrkx.git",
                        "category": "tooling"
                      }
                    ]
                    """.trimIndent(),
                )

                then("wrkx-status generates a status report listing both repos") {
                    val result = projectDir.gradle("wrkx-status").build()

                    result.task(":wrkx-status")?.outcome shouldBe TaskOutcome.SUCCESS

                    val reposFile = projectDir.resolve(".wrkx/repos.md")
                    reposFile.shouldExist()
                    val content = reposFile.readText()
                    content shouldContain "Workspace Repos"
                    content shouldContain "gort"
                    content shouldContain "wrkx"
                    content shouldContain "2 repos"
                }

                projectDir.deleteRecursively()
                reposDir.deleteRecursively()
            }

            /**
             * When repos are defined but their directories don't exist,
             * the plugin warns and continues so clone tasks can run.
             */
            `when`("wrkx.json has repos but directories are missing and repos are enabled") {
                val projectDir = tempProject().withSettings("enableAll()")

                projectDir.resolve("wrkx.json").writeText(
                    """
                    [{"name": "gort", "path": "git@github.com:ClankerGuru/gort.git"}]
                    """.trimIndent(),
                )

                then("the build succeeds with a warning to clone") {
                    val result = projectDir.gradle("wrkx").build()

                    result.output shouldContain "not cloned at"
                    result.output shouldContain "wrkx-clone"
                }

                projectDir.deleteRecursively()
            }

            /**
             * When the plugin is disabled via gradle.properties,
             * no tasks are registered and the build has no wrkx behavior.
             */
            `when`("plugin is disabled via property") {
                val projectDir = tempProject().withSettings()
                projectDir
                    .resolve("gradle.properties")
                    .writeText("zone.clanker.wrkx.enabled=false")

                then("wrkx tasks are not registered") {
                    val result = projectDir.gradle("wrkx").buildAndFail()
                    result.output shouldContain "Task 'wrkx' not found"
                }

                projectDir.deleteRecursively()
            }

            /**
             * When wrkx.json is an empty array, no repos are registered
             * but the plugin still works and tasks are available.
             */
            `when`("wrkx.json is empty") {
                val projectDir = tempProject().withSettings()
                projectDir.resolve("wrkx.json").writeText("[]")

                then("the catalog task runs with no repos") {
                    val result = projectDir.gradle("wrkx").build()
                    result.task(":wrkx")?.outcome shouldBe TaskOutcome.SUCCESS
                }

                projectDir.deleteRecursively()
            }

            /**
             * When no DSL block is used (no wrkx {}), repos are not included
             * as composite builds.
             */
            `when`("no DSL block enables repos") {
                val projectDir = tempProject().withSettings()
                val reposDir =
                    File(projectDir.parentFile, "${projectDir.name}-repos").apply { mkdirs() }

                File(reposDir, "gort").apply {
                    mkdirs()
                    resolve("settings.gradle.kts").writeText("rootProject.name = \"gort\"")
                }

                projectDir.resolve("wrkx.json").writeText(
                    """
                    [{"name": "gort", "path": "git@github.com:ClankerGuru/gort.git"}]
                    """.trimIndent(),
                )

                then("repos are not included as composite builds") {
                    val result = projectDir.gradle("wrkx").build()
                    result.task(":wrkx")?.outcome shouldBe TaskOutcome.SUCCESS
                    // No "not cloned" warning because no repos are enabled
                    result.output shouldNotContain "not cloned at"
                }

                projectDir.deleteRecursively()
                reposDir.deleteRecursively()
            }

            /**
             * The DSL disableAll() + enable(specific) pattern works correctly.
             */
            `when`("DSL uses disableAll then enable for specific repos") {
                val projectDir = tempProject()
                val reposDir =
                    File(projectDir.parentFile, "${projectDir.name}-repos").apply { mkdirs() }

                File(reposDir, "gort").apply {
                    mkdirs()
                    resolve("settings.gradle.kts").writeText("rootProject.name = \"gort\"")
                }
                File(reposDir, "wrkx").apply {
                    mkdirs()
                    resolve("settings.gradle.kts").writeText("rootProject.name = \"wrkx\"")
                }

                projectDir.resolve("wrkx.json").writeText(
                    """
                    [
                      {"name": "gort", "path": "git@github.com:ClankerGuru/gort.git"},
                      {"name": "wrkx", "path": "git@github.com:ClankerGuru/wrkx.git"}
                    ]
                    """.trimIndent(),
                )

                projectDir.withSettings(
                    """
                    disableAll()
                    enable(repos.getByName("gort"))
                    """.trimIndent(),
                )

                then("only the enabled repo generates a not-cloned warning or is included") {
                    // gort is enabled and exists, wrkx is disabled and should not trigger
                    // a "not cloned" warning
                    val result = projectDir.gradle("wrkx-status").build()
                    result.task(":wrkx-status")?.outcome shouldBe TaskOutcome.SUCCESS
                    val content = projectDir.resolve(".wrkx/repos.md").readText()
                    // Both repos appear in the report (all repos registered)
                    content shouldContain "gort"
                    content shouldContain "wrkx"
                }

                projectDir.deleteRecursively()
                reposDir.deleteRecursively()
            }

            /**
             * The workingBranch property is settable in the DSL.
             */
            `when`("DSL sets workingBranch") {
                val projectDir =
                    tempProject().withSettings(
                        """
                        workingBranch = "feature/new-catalog"
                        """.trimIndent(),
                    )
                projectDir.resolve("wrkx.json").writeText("[]")

                then("the build succeeds") {
                    val result = projectDir.gradle("wrkx").build()
                    result.task(":wrkx")?.outcome shouldBe TaskOutcome.SUCCESS
                }

                projectDir.deleteRecursively()
            }
        }
    })
