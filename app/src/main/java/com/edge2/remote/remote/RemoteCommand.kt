package com.edge2.remote.remote

/**
 * Protocole texte minimal échangé sur le WebSocket (contrôleur → host).
 * Volontairement trivial pour rester parsable en 3 lignes de JS côté page web.
 *
 *  - `M1:<n>` règle le moteur 1 (0..20)
 *  - `M2:<n>` règle le moteur 2
 *  - `B:<n>`  règle les deux
 *  - `S`      stop
 */
sealed interface RemoteCommand {
    data class SetMotor(val index: Int, val level: Int) : RemoteCommand
    data class SetBoth(val level: Int) : RemoteCommand
    data object Stop : RemoteCommand

    companion object {
        fun parse(text: String): RemoteCommand? {
            val t = text.trim()
            return when {
                t == "S" -> Stop
                t.startsWith("B:") -> t.removePrefix("B:").toIntOrNull()?.let { SetBoth(it) }
                t.startsWith("M1:") -> t.removePrefix("M1:").toIntOrNull()?.let { SetMotor(1, it) }
                t.startsWith("M2:") -> t.removePrefix("M2:").toIntOrNull()?.let { SetMotor(2, it) }
                else -> null
            }
        }

        fun format(cmd: RemoteCommand): String = when (cmd) {
            is SetMotor -> "M${cmd.index}:${cmd.level}"
            is SetBoth -> "B:${cmd.level}"
            Stop -> "S"
        }
    }
}
