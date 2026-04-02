package zone.clanker.gradle.wrkx

import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.spec.Spec
import org.testcontainers.DockerClientFactory
import kotlin.reflect.KClass

class DockerAvailable : EnabledCondition {
    override fun enabled(kclass: KClass<out Spec>): Boolean =
        try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (_: Exception) {
            false
        }
}
