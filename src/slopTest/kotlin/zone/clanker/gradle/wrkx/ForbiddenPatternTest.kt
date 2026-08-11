package zone.clanker.gradle.wrkx

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.BehaviorSpec

/**
 * Bans code patterns that indicate poor design or inconsistency.
 *
 * Rules:
 * - No `try-catch` blocks: prefer `runCatching`/`fold` for explicit error flow
 * - No standalone constant files: constants belong in the object or class that owns them
 * - No wildcard imports: every import must be explicit
 * - No Git push or remote-delete commands: WRKX never mutates remotes
 */
class ForbiddenPatternTest :
    BehaviorSpec({

        val mainScope = Konsist.scopeFromSourceSet("main")

        given("error handling uses runCatching, not try-catch") {

            `when`("examining all main source functions") {
                then("no function contains a try-catch block") {
                    mainScope.functions().assertTrue {
                        !it.text.contains("try {") && !it.text.contains("try{")
                    }
                }
            }
        }

        given("no standalone constant files") {

            `when`("examining all main source files") {
                val forbidden = listOf("Constants", "Consts")

                then("no file is named as a constant container") {
                    mainScope.files.assertTrue { file ->
                        forbidden.none { name ->
                            file.name.removeSuffix(".kt") == name
                        }
                    }
                }
            }
        }

        given("no wildcard imports") {

            `when`("examining all main source files") {
                then("no import uses a wildcard") {
                    mainScope.files.assertTrue { file ->
                        file.imports.none { it.isWildcard }
                    }
                }
            }
        }

        given("remote Git state is read-only") {
            `when`("examining Git operations") {
                then("no command can push or delete a remote branch") {
                    mainScope.files.filter { it.name == "GitOperations.kt" }.assertTrue { file ->
                        !file.text.contains("\"push\"") && !file.text.contains("\"--delete\"")
                    }
                }
            }
        }
    })
