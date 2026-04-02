package zone.clanker.gradle.wrkx

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

/**
 * Enforces that all Gradle task classes carry required annotations.
 *
 * Every task in wrkx interacts with external state (git repos, filesystem)
 * that Gradle cannot track for up-to-date checks. Each task must declare
 * `@UntrackedTask` to make this explicit and prevent false cache hits.
 */
class TaskAnnotationTest :
    BehaviorSpec({

        val mainScope = Konsist.scopeFromSourceSet("main")

        given("task classes require @UntrackedTask") {

            `when`("classes in the task package end with Task") {
                val taskClasses =
                    mainScope
                        .classes()
                        .filter { it.packagee?.name?.contains("wrkx.task") == true }
                        .withNameEndingWith("Task")

                then("every task class has @UntrackedTask annotation") {
                    taskClasses.assertTrue {
                        it.annotations.any { ann -> ann.name == "UntrackedTask" }
                    }
                }
            }
        }
    })
