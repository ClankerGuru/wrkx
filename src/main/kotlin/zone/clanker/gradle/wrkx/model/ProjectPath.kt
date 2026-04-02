package zone.clanker.gradle.wrkx.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A Gradle project path within an included build.
 *
 * ```kotlin
 * ProjectPath("tokens")   // resolves to :tokens
 * ProjectPath("core")     // resolves to :core
 * ProjectPath(":")        // resolves to root project
 * ```
 *
 * Used as the right side of a [ArtifactSubstitution] to identify which local
 * Gradle project replaces the Maven artifact.
 *
 * @property value the project name or ":" for root, must not be blank
 * @see ArtifactSubstitution
 * @see ArtifactId
 */
@Serializable(with = ProjectPathSerializer::class)
@JvmInline
value class ProjectPath(
    val value: String,
) {
    init {
        require(value.isNotBlank()) {
            """
            Project path is blank. Expected a Gradle project name or ':' for root.
            Example: 'tokens', 'core', or ':'
            """.trimIndent()
        }
    }

    /**
     * The full Gradle project path with leading colon.
     *
     * ```kotlin
     * ProjectPath("tokens").gradlePath  // ":tokens"
     * ProjectPath(":").gradlePath       // ":"
     * ```
     */
    val gradlePath: String
        get() = if (value == ":" || value.isBlank()) ":" else ":$value"

    override fun toString(): String = value
}

/**
 * Serializer for [ProjectPath] that reads/writes the raw string value.
 *
 * @see ProjectPath
 */
object ProjectPathSerializer : KSerializer<ProjectPath> {
    override val descriptor = PrimitiveSerialDescriptor("ProjectPath", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ProjectPath =
        runCatching { ProjectPath(decoder.decodeString()) }
            .getOrElse { e -> throw SerializationException(e.message, e) }

    override fun serialize(encoder: Encoder, value: ProjectPath) = encoder.encodeString(value.value)
}
