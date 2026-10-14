plugins {
    java
    application
    id("io.papermc.paperweight.core")
}

repositories {
    mavenCentral()
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
    dependsOn(tasks.named(buildtools.classesTaskName))
    doLast {
        val result = providers.javaexec {
            executable = launcher.get().executablePath.asFile.absolutePath
            classpath = tool
            mainClass = "OwnershipTransform"
            args(listOf(archiveFile.get().asFile.absolutePath) + libs.files.map { it.absolutePath })
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
