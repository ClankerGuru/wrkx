package zone.clanker.gradle.wrkx.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A dependency substitution: maps a Maven [artifact] to a local Gradle [project].
 *
 * Serialized as a comma-separated string in `wrkx.json`:
 * ```json
 * "substitutions": ["zone.clanker:gort-tokens,tokens"]
 * ```
 *
 * When the plugin wires composite builds, each substitution tells Gradle to
 * resolve the [artifact] from the local [project] instead of Maven.
 *
 * @property artifact the Maven coordinate to replace, e.g. `zone.clanker:gort-tokens`
 * @property project the Gradle project that provides it, e.g. `tokens`
 * @see ArtifactId
 * @see ProjectPath
 * @see WorkspaceRepository.substitutions
 */
@Serializable(with = ArtifactSubstitutionSerializer::class)
data class ArtifactSubstitution(
    val artifact: ArtifactId,
    val project: ProjectPath,
) {
    override fun toString(): String = "${artifact.value},${project.value}"
}

/**
 * Serializer for [ArtifactSubstitution] that reads/writes the comma-separated format.
 *
 * Parses strings like `"zone.clanker:gort-tokens,tokens"` into an
 * [ArtifactId] and [ProjectPath] pair.
 *
 * @see ArtifactSubstitution
 */
object ArtifactSubstitutionSerializer : KSerializer<ArtifactSubstitution> {
    override val descriptor = PrimitiveSerialDescriptor("Substitution", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ArtifactSubstitution {
        val raw = decoder.decodeString()
        val parts = raw.split(",", limit = 2)
        if (parts.size != 2) {
            throw SerializationException(
                """
                Invalid substitution: '$raw'. Expected format: 'group:artifact,project' (comma-separated).
                Example: 'zone.clanker:gort-tokens,tokens'
                """.trimIndent(),
            )
        }
        return runCatching {
            ArtifactSubstitution(
                artifact = ArtifactId(parts[0].trim()),
                project = ProjectPath(parts[1].trim()),
            )
        }.getOrElse { e ->
            throw SerializationException(
                """
                Invalid substitution: '$raw'. ${e.message}
                Expected format: 'group:artifact,project'
                """.trimIndent(),
                e,
            )
        }
    }

    override fun serialize(encoder: Encoder, value: ArtifactSubstitution) {
        encoder.encodeString(value.toString())
    }
}
