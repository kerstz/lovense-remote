package com.edge2.remote.remote

/**
 * Protocole texte minimal échangé sur le WebSocket (contrôleur → host).
 * Volontairement trivial pour rester parsable en quelques lignes de JS.
 *
 *  - `M1:<n>` règle l'actionneur 1 (0..20, échelle commune à toutes les marques)
 *  - `M2:<n>` règle l'actionneur 2
 *  - `B:<n>`  règle tous les actionneurs
 *  - `S`      stop
 *
 * Préfixe optionnel `@<i>:` = cible le jouet n° i de la liste envoyée par le host
 * (message `STATE`) ; sans préfixe, la commande vise TOUS les jouets.
 * Ex. `@1:M2:14`.
 *
 * Messages de session (hors commandes) : `AUTH:<pin>[:<jeton>]` (1er message,
 * obligatoire) côté contrôleur ; `STATE:{json}`, `TOKEN:<jeton>`, `DENIED` côté host.
 */
sealed interface RemoteCommand {
    /** Jouet ciblé (index dans la liste STATE), null = tous. */
    val target: Int?

    data class SetMotor(val index: Int, val level: Int, override val target: Int? = null) : RemoteCommand
    data class SetBoth(val level: Int, override val target: Int? = null) : RemoteCommand
    data class Stop(override val target: Int? = null) : RemoteCommand

    companion object {
        const val MAX_LEVEL = 20
        private const val MAX_TARGET = 16

        fun parse(text: String): RemoteCommand? {
            var t = text.trim()
            if (t.length > 24) return null
            var target: Int? = null
            if (t.startsWith("@")) {
                val sep = t.indexOf(':')
                if (sep < 2) return null
                target = t.substring(1, sep).toIntOrNull()?.takeIf { it in 0 until MAX_TARGET } ?: return null
                t = t.substring(sep + 1)
            }
            fun lvl(s: String) = s.toIntOrNull()?.takeIf { it in 0..MAX_LEVEL }
            return when {
                t == "S" -> Stop(target)
                t.startsWith("B:") -> lvl(t.removePrefix("B:"))?.let { SetBoth(it, target) }
                t.startsWith("M1:") -> lvl(t.removePrefix("M1:"))?.let { SetMotor(1, it, target) }
                t.startsWith("M2:") -> lvl(t.removePrefix("M2:"))?.let { SetMotor(2, it, target) }
                else -> null
            }
        }

        fun format(cmd: RemoteCommand): String {
            val body = when (cmd) {
                is SetMotor -> "M${cmd.index}:${cmd.level}"
                is SetBoth -> "B:${cmd.level}"
                is Stop -> "S"
            }
            return cmd.target?.let { "@$it:$body" } ?: body
        }
    }
}
