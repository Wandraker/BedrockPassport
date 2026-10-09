plugins {
    java
}

group = "dev.onelsey"
version = "1.3.6-SNAPSHOT"

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    compileOnly("org.geysermc.geyser:api:2.11.3-SNAPSHOT")
    compileOnly("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    compileOnly("io.netty:netty-handler:4.2.17.Final")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}


val verifyFoliaCompatibility by tasks.registering {
    val javaSources = fileTree("src/main/java") {
        include("**/*.java")
    }
    val pluginDescriptor = file("src/main/resources/plugin.yml")

    inputs.files(javaSources, pluginDescriptor)

    doLast {
        val forbidden = listOf(
            "Bukkit.getScheduler()",
            "getServer().getScheduler()",
            "BukkitRunnable"
        )

        val violations = javaSources.files.flatMap { source ->
            val text = source.readText()
            forbidden.filter(text::contains).map { token ->
                "${source.relativeTo(projectDir)} contains forbidden Folia scheduler pattern: $token"
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(violations.joinToString(System.lineSeparator()))
        }

        if (!pluginDescriptor.readText().contains("folia-supported: true")) {
            throw GradleException("plugin.yml must declare folia-supported: true")
        }
    }
}


val foliaCompileClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    foliaCompileClasspath("dev.folia:folia-api:26.2.build.7-beta")
    foliaCompileClasspath("org.geysermc.geyser:api:2.11.3-SNAPSHOT")
    foliaCompileClasspath("org.geysermc.floodgate:api:2.2.5-SNAPSHOT")
    foliaCompileClasspath("io.netty:netty-handler:4.2.17.Final")
}

val compileFoliaCheck by tasks.registering(JavaCompile::class) {
    source = sourceSets.main.get().java
    classpath = foliaCompileClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/foliaCheck"))
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    options.encoding = "UTF-8"
}

tasks.named("check") {
    dependsOn(verifyFoliaCompatibility, compileFoliaCheck)
}
