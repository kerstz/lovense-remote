package com.edge2.remote.remote

/**
 * Minimal text protocol spoken over the WebSocket (controller → host).
 * Deliberately trivial so it stays parsable in a few lines of JS.
 *
 *  - `M1:<n>` sets actuator 1 (0..20, a scale shared by every brand)
 *  - `M2:<n>` sets actuator 2
 *  - `B:<n>`  sets every actuator
 *  - `S`      stop
 *
 * Optional `@<i>:` prefix = targets toy #i of the list sent by the host
 * (`STATE` message); without a prefix, the command targets ALL toys.
 * E.g. `@1:M2:14`.
 *
 * Session messages (not commands): `AUTH:<pin>[:<token>]` (first message,
 * mandatory) from the controller; `STATE:{json}`, `TOKEN:<token>`, `DENIED` from the host.
 */
sealed interface RemoteCommand {
    /** Targeted toy (index in the STATE list), null = all. */
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
