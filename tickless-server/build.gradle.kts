plugins {
    java
    application
    id("io.papermc.paperweight.core")
}

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://maven.fabricmc.net/") }
        filter { includeGroup("net.fabricmc") }
    }
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    mache("io.papermc:mache:26.3+build.1")
    paperclip("io.papermc:paperclip:3.0.4")

    compileOnly("org.jetbrains:annotations:24.0.1")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    compileOnly("org.checkerframework:checker-qual:3.49.0")
}

paperweight {
    minecraftVersion = providers.gradleProperty("mcVersion")
    mainClass = "net.minecraft.server.Main"
    bundlerJarName = "tickless"
    gitFilePatches = false

    // This project is the patched server itself, so its patches live here, not in paper-server/.
    paper {
        paperServerDir = layout.projectDirectory
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.isFork = true
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:-deprecation", "-Xlint:-removal"))
}

application {
    mainClass = "net.minecraft.server.Main"
    applicationName = "minecraft-server"
}

val buildtools = sourceSets.create("buildtools")
val launcher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }

val fabricLibraries = configurations.create("fabricLibraries")
dependencies {
    fabricLibraries("net.fabricmc:fabric-loader:0.19.5")
    fabricLibraries("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7")
    listOf("asm", "asm-analysis", "asm-commons", "asm-tree", "asm-util").forEach {
        fabricLibraries("org.ow2.asm:$it:9.10.1")
    }
}

// Knot must see the original patched bytecode. Ownership is applied by the agent after
// Mixin, including injected methods and mod-defined entities, immediately before defineClass.
val fabricGameJar = tasks.register<Jar>("fabricGameJar") {
    archiveFileName = "tickless-fabric-game.jar"
    from(sourceSets.main.get().output)
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    manifest { attributes("Main-Class" to application.mainClass.get()) }
}
val fabricAgentJar = tasks.register<Jar>("fabricAgentJar") {
    archiveFileName = "tickless-fabric-agent.jar"
    from(buildtools.output)
    manifest { attributes("Premain-Class" to "FabricOwnershipAgent") }
}
tasks.register<Sync>("installFabric") {
    dependsOn(fabricGameJar, fabricAgentJar)
    into(rootProject.layout.buildDirectory.dir("install/fabric"))
    from(fabricGameJar) { into("lib") }
    from(fabricAgentJar) { into("agent") }
    from(sourceSets.main.get().runtimeClasspath.filter { it.name.endsWith(".jar") }) { into("lib") }
    from(fabricLibraries) { into("lib") }
    from(rootProject.file("tools/fabric/minecraft-server")) {
        into("bin")
        filePermissions { unix("rwxr-xr-x") }
    }
}

tasks.jar {
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
    // Mojang's signature covers the unmodified classes; keeping it would describe a jar we no longer ship.
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    // Field writes on entities and block entities take their owner's world lock; see
    // WorldOwnership. The rewrite runs on the packaged jar, so compilation stays incremental.
    val tool = buildtools.runtimeClasspath
    val libs = sourceSets.main.get().runtimeClasspath.filter { it.name.endsWith(".jar") }
    inputs.files(tool)
    inputs.property("raceCheck", project.hasProperty("raceCheck"))
    dependsOn(tasks.named(buildtools.classesTaskName))
    doLast {
        val result = providers.javaexec {
            executable = launcher.get().executablePath.asFile.absolutePath
            classpath = tool
            mainClass = "OwnershipTransform"
            // -PraceCheck adds the collection race check hooks (RaceCheck); never for a release.
            val races = if (project.hasProperty("raceCheck")) listOf("--races") else listOf()
            args(races + listOf(archiveFile.get().asFile.absolutePath) + libs.files.map { it.absolutePath })
        }
        println(result.standardOutput.asText.get().trim())
    }
}

// The stress and check tools expect the server under <root>/build/install/minecraft-server.
// -PinstallTo=<dir> installs somewhere else, so a build never replaces the jar of a running server.
tasks.installDist {
    destinationDir = if (project.hasProperty("installTo")) {
        rootProject.file(project.property("installTo")!!)
    } else {
        rootProject.layout.buildDirectory.dir("install/minecraft-server").get().asFile
    }
}

tasks.build {
    dependsOn(tasks.installDist)
}

val downloadFabricTestMods = tasks.register<Exec>("downloadFabricTestMods") {
    workingDir(rootProject.projectDir)
    commandLine("python3", "tools/fabric/download-test-mods.py")
}
tasks.register<Exec>("buildFabricProbe") {
    dependsOn("installFabric", downloadFabricTestMods)
    workingDir(rootProject.projectDir)
    environment("JAVA_HOME", launcher.get().metadata.installationPath.asFile.absolutePath)
    commandLine("python3", "tools/fabric/build-probe.py")
}

val fabricProtectedClasses = tasks.register("fabricProtectedClasses") {
    mustRunAfter("rebuildSourcePatches")
    val patches = fileTree("patches/sources") { include("**/*.java.patch") }
    val manifest = layout.buildDirectory.file("fabric-conflicts/tickless-patched-classes.txt")
    inputs.files(patches)
    outputs.file(manifest)
    doLast {
        val directory = project.file("patches/sources")
        val names = patches.files.map { it.relativeTo(directory).invariantSeparatorsPath.removeSuffix(".java.patch") }.sorted()
        manifest.get().asFile.apply { parentFile.mkdirs(); writeText(names.joinToString("\n", postfix = "\n")) }
    }
}
tasks.processResources {
    dependsOn(fabricProtectedClasses)
    from(layout.buildDirectory.file("fabric-conflicts/tickless-patched-classes.txt"))
}
