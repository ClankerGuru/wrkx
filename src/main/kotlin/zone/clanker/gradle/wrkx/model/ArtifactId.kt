package zone.clanker.gradle.wrkx.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A Maven artifact coordinate in `group:name` format.
 *
 * ```kotlin
 * ArtifactId("zone.clanker:gort-tokens")
 * ArtifactId("com.example:core-api")
 * ```
 *
 * Used as the left side of a [ArtifactSubstitution] to identify which Maven
 * dependency should be replaced with local source.
 *
 * @property value the full artifact coordinate, must not be blank
 * @see ArtifactSubstitution
 * @see ProjectPath
 */
@Serializable(with = ArtifactIdSerializer::class)
@JvmInline
value class ArtifactId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) {
            """
            Artifact ID is blank. Expected format: 'group:name'.
            Example: 'zone.clanker:gort-tokens'
            """.trimIndent()
        }
    }

    override fun toString(): String = value
}

/**
 * Serializer for [ArtifactId] that reads/writes the raw string value.
 *
 * @see ArtifactId
 */
object ArtifactIdSerializer : KSerializer<ArtifactId> {
    override val descriptor = PrimitiveSerialDescriptor("ArtifactId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ArtifactId =
        runCatching { ArtifactId(decoder.decodeString()) }
            .getOrElse { e -> throw SerializationException(e.message, e) }

    override fun serialize(encoder: Encoder, value: ArtifactId) = encoder.encodeString(value.value)
}
