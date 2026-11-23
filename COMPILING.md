Compiling
=========

You can compile WorldEdit as long as you have the [Java Development Kit (JDK)](https://www.azul.com/downloads/?package=jdk) for Java 21 (Azul Zulu is what CI uses).
Gradle runs on Java 21; the mod itself still compiles to Java 8 bytecode, and the build downloads the other toolchains it
needs (a JDK 17 compiler, and Java 8 for the unit tests) on its own.
You only need one version of the JDK installed.

The build process uses Gradle, which you do *not* need to download. This repository builds one Forge 1.12.2 mod with the
GregTechCEu buildscript and RetroFuturaGradle. Mod settings (id, name, jar name, shading) live in `buildscript.properties`;
`build.gradle` is the upstream buildscript and should not be edited by hand.

## To compile...

### On Windows

1. Shift + right click the folder with WorldEdit's files and click "Open command prompt".
2. `gradlew setupDecompWorkspace`
3. `gradlew build`

### On Linux, BSD, or Mac OS X

1. In your terminal, navigate to the folder with WorldEdit's files (`cd /folder/of/worldedit/files`)
2. `./gradlew setupDecompWorkspace`
3. `./gradlew build`

## Then you will find...

The mod jar in **build/libs**, named `LimitlessDevWorldEdit-<version>.jar`. The `-dev` and `-sources` jars next to it
are for development only.

### Note regarding `setupDecompWorkspace`
`setupDecompWorkspace` needs more memory than Gradle gets by default. `gradle.properties` already sets
`org.gradle.jvmargs = -Xmx3G` for it.

## Other commands

* `gradlew runClient` and `gradlew runServer` start a development client or dedicated server.
* `gradlew idea` will generate an [IntelliJ IDEA](http://www.jetbrains.com/idea/) project.
* `gradlew eclipse` will generate an [Eclipse](https://www.eclipse.org/downloads/) project.
