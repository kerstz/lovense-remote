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

The threat model and mitigations are summarised in the
[README](README.md#-security--privacy).
