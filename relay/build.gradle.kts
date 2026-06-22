plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("com.edge2.relay.RelayKt")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    runtimeOnly(libs.logback.classic)
}

// Source unique de vérité : la page de contrôle vit dans les assets de l'app.
// On l'embarque telle quelle dans les resources du relais (token __WS_PATH__
// substitué à l'exécution → "/ctrl" côté relais, "/ws" côté app embarquée).
tasks.named<ProcessResources>("processResources") {
    from("../app/src/main/assets/controller.html")
}
