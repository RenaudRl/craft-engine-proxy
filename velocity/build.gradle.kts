import net.momirealms.netty

plugins {
    id("craft-engine-proxy.run-velocity")
    id("net.kyori.blossom") version "2.2.0"
}

// BTC : ce fork compile contre l'API du proxy qui le charge, BTCVelocity (`dev.btc.velocity:api`),
// et non contre l'API Velocity amont. Si le plugin appelle une methode que BTCVelocity n'a pas, la
// compilation echoue ici au lieu d'un NoSuchMethodError au demarrage du proxy.
// Depot statique publie par BTCVelocity (`repo/`), voisin par defaut ; `-PbtcVelocityRepo=<chemin>`.
val btcVelocityRepo = rootProject.file(providers.gradleProperty("btcVelocityRepo").getOrElse("../BTCVelocity/repo"))
val btcVelocityApi = "dev.btc.velocity:api:${rootProject.properties["btc_velocity_api_version"]}"
if (!btcVelocityRepo.resolve("dev/btc/velocity/api").isDirectory) {
    throw GradleException("API BTCVelocity introuvable sous $btcVelocityRepo (-PbtcVelocityRepo=<depot statique BTCVelocity>)")
}
repositories {
    maven(btcVelocityRepo.toURI()) {
        content { includeGroup("dev.btc.velocity") }
    }
}

dependencies {
    implementation(project(":common"))
    netty(project)
    // Platform
    compileOnly(btcVelocityApi)
    compileOnly("com.github.ben-manes.caffeine:caffeine:${rootProject.properties["caffeine_version"]}")
    annotationProcessor(btcVelocityApi)
    // Reflection
    compileOnly(files("${rootProject.rootDir}/libs/jni-internal-lookup-1.9.jar"))
    compileOnly("net.momirealms:sparrow-reflection:${rootProject.properties["sparrow_reflection_version"]}")
}

sourceSets {
    main {
        blossom {
            javaSources {
                property("version", rootProject.properties["project_version"] as String)
            }
        }
    }
}

tasks {
    shadowJar {
        relocation.applyProxy(this)
        archiveFileName = "${rootProject.name}-velocity-plugin-${rootProject.properties["project_version"]}.jar"
        destinationDirectory.set(file("$rootDir/target"))
    }
}

artifacts {
    implementation(tasks.shadowJar)
}
