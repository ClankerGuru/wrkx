package zone.clanker.gradle.wrkx

import org.gradle.api.initialization.Settings

/**
 * Type-safe accessor for the `wrkx { }` DSL block in `settings.gradle.kts`.
 *
 * ```kotlin
 * // settings.gradle.kts
 * plugins {
 *     id("zone.clanker.gradle.wrkx") version "0.36.0"
 * }
 *
 * wrkx {
 *     workingBranch = "feature/new-catalog"
 *     disableAll()
 *     enable("gort", "coreModels")
 * }
 * ```
 *
 * @param action configuration block applied to the [Wrkx.SettingsExtension]
 * @see Wrkx.SettingsExtension
 */
public fun Settings.wrkx(action: Wrkx.SettingsExtension.() -> Unit) {
    extensions.getByType(Wrkx.SettingsExtension::class.java).action()
}
