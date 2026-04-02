package zone.clanker.gradle.wrkx

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

/**
 * Enforces directional import boundaries between packages.
 *
 * The dependency direction is:
 * ```
 * task → model (tasks consume models)
 * report → model (renderers consume models)
 * model → (nothing internal — models are leaf nodes)
 * ```
 *
 * Models must never depend on tasks or reports.
 * Tasks may depend on models but not on reports.
 * This prevents circular dependencies and keeps the model layer pure.
 */
class PackageBoundaryTest :
    BehaviorSpec({

        val mainScope = Konsist.scopeFromSourceSet("main")

        given("import direction enforcement") {

            `when`("files are in the model package") {
                val modelFiles =
                    mainScope.files.filter {
                        it.packagee?.name?.contains("wrkx.model") == true
                    }

                then("models never import from the task package") {
                    modelFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("wrkx.task") }
                    }
                }

                then("models never import from the report package") {
                    modelFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("wrkx.report") }
                    }
                }
            }

            `when`("files are in the report package") {
                val reportFiles =
                    mainScope.files.filter {
                        it.packagee?.name?.contains("wrkx.report") == true
                    }

                then("reports never import from the task package") {
                    reportFiles.assertTrue {
                        it.imports.none { imp -> imp.name.contains("wrkx.task") }
                    }
                }
            }
        }
    })
