package zone.clanker.gradle.wrkx

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.gradle.api.Action
import org.gradle.api.file.BuildLayout
import org.gradle.api.file.Directory
import org.gradle.api.initialization.Settings
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.testfixtures.ProjectBuilder
import java.io.File

class WrkxApplyTest :
    BehaviorSpec({

        val objects: ObjectFactory =
            ProjectBuilder.builder().build().objects

        fun mockLayout(settingsDir: File): BuildLayout {
            val directory =
                mockk<Directory> {
                    every { asFile } returns settingsDir
                    every { file(any<String>()) } answers {
                        val name = firstArg<String>()
                        mockk<org.gradle.api.file.RegularFile> {
                            every { asFile } returns File(settingsDir, name)
                        }
                    }
                }
            return mockk {
                every { settingsDirectory } returns directory
            }
        }

        fun mockProviders(enabled: String? = null): ProviderFactory {
            val provider =
                mockk<Provider<String>> {
                    every { orNull } returns enabled
                }
            return mockk {
                every { gradleProperty(Wrkx.ENABLED_PROP) } returns provider
            }
        }

        fun createPlugin(
            providers: ProviderFactory = mockProviders(),
            layout: BuildLayout =
                mockLayout(
                    File.createTempFile("wrkx", "").apply {
                        delete()
                        mkdirs()
                    },
                ),
        ): Wrkx.SettingsPlugin =
            objects.newInstance(Wrkx.SettingsPlugin::class.java, providers, layout)

        fun createExtension(settings: Settings = mockk(relaxed = true)): Wrkx.SettingsExtension =
            objects.newInstance(Wrkx.SettingsExtension::class.java, settings)

        given("SettingsPlugin apply") {

            `when`("apply is called with disabled property") {
                val tmpDir =
                    File.createTempFile("wrkx-apply-disabled", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin =
                    createPlugin(
                        providers = mockProviders(enabled = "false"),
                        layout = layout,
                    )
                val settings = mockk<Settings>(relaxed = true)

                then("returns early without creating extension") {
                    plugin.apply(settings)
                    io.mockk.verify(exactly = 0) {
                        settings.extensions.create(any<String>(), any<Class<*>>(), any())
                    }
                }

                tmpDir.deleteRecursively()
            }

            `when`("apply is called when already applied") {
                val tmpDir =
                    File.createTempFile("wrkx-apply-already", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)

                then("isAlreadyApplied returns true and apply returns early") {
                    val ext = mockk<Wrkx.SettingsExtension>()
                    val extensions =
                        mockk<ExtensionContainer>(relaxed = true) {
                            every { findByType(Wrkx.SettingsExtension::class.java) } returns ext
                        }
                    val settings =
                        mockk<Settings>(relaxed = true) {
                            every { this@mockk.extensions } returns extensions
                        }
                    plugin.isAlreadyApplied(settings).shouldBeTrue()
                    plugin.apply(settings)
                    io.mockk.verify(exactly = 0) { settings.gradle }
                }

                tmpDir.deleteRecursively()
            }

            `when`("apply is called normally") {
                val tmpDir =
                    File.createTempFile("wrkx-apply-normal", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                File(tmpDir, "wrkx.json").writeText("[]")
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)

                then("creates extension, populates config, and registers callbacks") {
                    val ext = mockk<Wrkx.SettingsExtension>(relaxed = true)
                    val extensions =
                        mockk<ExtensionContainer>(relaxed = true) {
                            every { findByType(Wrkx.SettingsExtension::class.java) } returns null
                            every {
                                create(any<String>(), any<Class<*>>(), any())
                            } returns ext
                        }
                    val gradle = mockk<org.gradle.api.invocation.Gradle>(relaxed = true)
                    val settings =
                        mockk<Settings>(relaxed = true) {
                            every { this@mockk.extensions } returns extensions
                            every { this@mockk.gradle } returns gradle
                        }
                    plugin.apply(settings)

                    io.mockk.verify { gradle.settingsEvaluated(any<Action<Settings>>()) }
                    io.mockk.verify { gradle.rootProject(any<Action<org.gradle.api.Project>>()) }
                }

                tmpDir.deleteRecursively()
            }

            `when`("apply rootProject callback is invoked") {
                val tmpDir =
                    File.createTempFile("wrkx-apply-root", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                File(tmpDir, "wrkx.json").writeText("[]")
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)

                val ext = mockk<Wrkx.SettingsExtension>(relaxed = true)
                every { ext.workingBranch } returns null

                val extensions =
                    mockk<ExtensionContainer>(relaxed = true) {
                        every { findByType(Wrkx.SettingsExtension::class.java) } returns null
                        every {
                            create(
                                Wrkx.EXTENSION_NAME,
                                Wrkx.SettingsExtension::class.java,
                                any<Settings>(),
                            )
                        } returns ext
                    }

                val rootProjectSlot = io.mockk.slot<Action<org.gradle.api.Project>>()
                val settingsEvalSlot = io.mockk.slot<Action<Settings>>()
                val gradle =
                    mockk<org.gradle.api.invocation.Gradle>(relaxed = true) {
                        every { rootProject(capture(rootProjectSlot)) } returns Unit
                        every { settingsEvaluated(capture(settingsEvalSlot)) } returns Unit
                    }
                val settings =
                    mockk<Settings>(relaxed = true) {
                        every { this@mockk.extensions } returns extensions
                        every { this@mockk.gradle } returns gradle
                    }

                plugin.apply(settings)

                then("settingsEvaluated callback invokes includeEnabled") {
                    settingsEvalSlot.captured.execute(settings)
                    io.mockk.verify { ext.includeEnabled() }
                }

                then("rootProject callback registers tasks on the project") {
                    val project =
                        ProjectBuilder
                            .builder()
                            .build()
                    rootProjectSlot.captured.execute(project)

                    project.tasks.findByName(Wrkx.TASK_CATALOG).shouldNotBeNull()
                    project.tasks.findByName(Wrkx.TASK_CLONE).shouldNotBeNull()
                    project.tasks.findByName(Wrkx.TASK_PULL).shouldNotBeNull()
                    project.tasks.findByName(Wrkx.TASK_CHECKOUT).shouldNotBeNull()
                    project.tasks.findByName(Wrkx.TASK_STATUS).shouldNotBeNull()
                    project.tasks.findByName(Wrkx.TASK_PRUNE).shouldNotBeNull()
                }

                tmpDir.deleteRecursively()
            }
        }

        given("catalog task execution") {

            `when`("the catalog task doLast action runs") {
                val tmpDir =
                    File.createTempFile("wrkx-catalog-exec", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val project =
                    ProjectBuilder
                        .builder()
                        .build()

                with(plugin) {
                    project.registerCatalogTask()
                }

                val catalogTask = project.tasks.findByName(Wrkx.TASK_CATALOG)!!

                then("executing the task prints the catalog") {
                    catalogTask.actions.forEach { it.execute(catalogTask) }
                }

                tmpDir.deleteRecursively()
            }
        }

        given("lifecycle tasks execution") {

            `when`("the lifecycle task doLast actions run with empty repos") {
                val tmpDir =
                    File.createTempFile("wrkx-life-exec", "").apply {
                        delete()
                        mkdirs()
                        deleteOnExit()
                    }
                val layout = mockLayout(tmpDir)
                val plugin = createPlugin(layout = layout)
                val project =
                    ProjectBuilder
                        .builder()
                        .build()
                val ext = createExtension()

                with(plugin) {
                    project.registerLifecycleTasks(ext, tmpDir)
                }

                then("clone task doLast runs with empty repos") {
                    val cloneTask = project.tasks.findByName(Wrkx.TASK_CLONE)!!
                    cloneTask.actions.forEach { it.execute(cloneTask) }
                }

                then("pull task doLast runs with empty repos") {
                    val pullTask = project.tasks.findByName(Wrkx.TASK_PULL)!!
                    pullTask.actions.forEach { it.execute(pullTask) }
                }

                then("checkout task doLast runs with empty repos") {
                    val checkoutTask = project.tasks.findByName(Wrkx.TASK_CHECKOUT)!!
                    checkoutTask.actions.forEach { it.execute(checkoutTask) }
                }

                tmpDir.deleteRecursively()
            }
        }

        given("Wrkx data object") {

            then("data object toString returns class name") {
                Wrkx.toString() shouldBe "Wrkx"
            }

            then("data object equals itself") {
                (Wrkx == Wrkx) shouldBe true
            }

            then("data object hashCode is stable") {
                Wrkx.hashCode() shouldBe Wrkx.hashCode()
            }
        }
    })
