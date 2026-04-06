plugins {
    id("clkx-conventions")
}

dependencies {
    testImplementation("io.mockk:mockk:1.13.13")
}

gradlePlugin {
    plugins {
        register("wrkx") {
            id = "zone.clanker.gradle.wrkx"
            implementationClass = "zone.clanker.gradle.wrkx.Wrkx\$SettingsPlugin"
            displayName = "Workspace Gradle Plugin (wrkx)"
            description = "Multi-repo workspace management with composite build wiring."
        }
    }
}
