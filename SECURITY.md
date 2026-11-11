# Security policy

Lovense Remote exposes an optional control server (same Wi-Fi / internet tunnel),
so security reports are taken seriously.

## Reporting a vulnerability

**Please don't open a public issue.** Use GitHub's private reporting:
<https://github.com/kerstz/lovense-remote/security/advisories/new>

Include the app version, what an attacker needs (network position, link, PIN…),
and steps to reproduce. You'll get an answer within a few days.

## Scope

In scope: the embedded HTTP/WebSocket server, link/PIN/approval flow, the deep
link handler, the SSH tunnel, BLE command handling.
Out of scope: vulnerabilities in third-party relays (localhost.run) or in toy
firmware itself.

## Supported versions

Only the latest release receives fixes.

## Threat model in short

The mitigations are summarised in the [README](README.md#-security--privacy);
the code lives in `app/src/main/java/com/edge2/remote/remote/`
(`RemoteServer`, `ShareSecurity`, `RemoteController`, `SshTunnel`,
`TofuHostKeyStore`) and every rule has a JVM test in `app/src/test/`.

What an attacker needs to drive a toy: the secret link (128-bit id, new on every
share), the 6-digit PIN (sent in the first WebSocket frame, never in a URL,
5 failures burn the share server-side), **and** the host's explicit approval of
that controller. Reconnection tokens rotate on every resume (the old one is
burnt as soon as the controller acknowledges the new one) and are revoked on
kick, stop, expiry (30 min) and lockout. Pending controllers receive no host state.

Known limits (by design, documented for users):

- **Same-Wi-Fi sharing is cleartext HTTP/WS**: anyone on that network can read
  the PIN, toy names and commands. The app tells users to share only on a
  trusted network. There is no TLS because the LAN address is dynamic and no
  certificate could be trusted by the controller's browser without a CA.
- **Internet sharing terminates TLS at localhost.run**: the relay sees the
  traffic (PIN, tokens, toy names, commands). It is opt-in behind a consent
  dialog. The relay's SSH host key is trust-on-first-use: the very first
  connection is not authenticated, every later one is (a changed key blocks the
  tunnel until the user re-accepts it).
- **Custom-scheme deep link** (`edge2remote://control?ws=…&pin=…`): Android
  does not verify custom schemes, so another app installed on the controller's
  phone could claim the scheme and receive the link + PIN. The host's approval
  is still required; the parameters are strictly validated and the user must
  confirm the host before any connection.
- **Resume tokens are bearer credentials** for the duration of a share: a token
  stolen in transit by the relay stays usable until the controller acknowledges
  its replacement (normally milliseconds; longer if the `TOKEN` frame is lost,
  so that a legitimate controller is not stranded in "pending"), and only until
  the host kicks the extra controller (which it sees in its list).
- **The PIN lockout is per share, not per client**: 5 wrong PINs from anyone who
  has the link burn the share for everybody, the legitimate controller
  included. Whoever holds the link can therefore deny service (not take
  control); the host simply starts a new share (new link + PIN). This trade-off
  is deliberate: per-client counters could be bypassed by changing IP through
  the relay.
- **Full BouncyCastle replaces Android's stripped "BC" provider** process-wide
  (needed for the SSH key exchange); the SSH private key is stored in
  app-private storage, not in the Android Keystore.
