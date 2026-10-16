# Privacy policy

*Last updated: 2026-10-16 · applies to the Android app `com.edge2.remote`.*

**Français ci-dessous.**

## Summary

- No account, no sign-up, no analytics, no ads, no crash reporting.
- The developer runs **no server** and receives **no data** from the app.
- Everything stays on your phone, except what you explicitly choose to share
  with a remote controller (see below).

## What the app processes

| Data | Where it goes | Why |
|---|---|---|
| Bluetooth LE scan results and toy names/models | Stays on the phone | Find and drive your toys. |
| Intensity commands, battery level | Phone ↔ toy over Bluetooth | Control the toy. |
| Language and theme preferences | App-private storage on the phone | Remember your settings. |
| SSH key pair and relay host key (only if you use Internet sharing) | App-private storage on the phone | Reconnect to the relay and detect interception. |
| Pattern files you import from a URL | Downloaded over HTTPS from the address **you** type | Import a pattern. That server sees your IP address. |

Toy names and usage can reveal information about your sex life, which is a
special category of personal data (GDPR art. 9). The app is designed so that
this information never leaves your phone unless you start a share.

App data is excluded from Android backups (`allowBackup=false`). Uninstalling
the app deletes it.

## Remote sharing

Sharing is off by default and always started by you. A share uses a secret
link, a 6-digit PIN, your approval for each controller, and expires after
30 minutes. When control stops (sharing stopped or expired, a controller
leaves or is removed, STOP tile), the toys are stopped.

- **Same Wi-Fi (LAN)**: the controller's browser talks directly to your phone.
  This traffic is **not encrypted** (`http://` / `ws://`): someone on the same
  Wi-Fi network could read the PIN, toy names and commands. Only share on a
  network you trust.
- **Internet (optional, asks for your consent first)**: traffic goes through
  **localhost.run**, a third-party SSH tunnel service not operated by this
  project. TLS ends at localhost.run, so that service can see your IP address,
  the PIN, the reconnection token, the toy names and the commands, for as long
  as the share lasts. Its own privacy practices apply: <https://localhost.run/>.
  Do not use Internet sharing if you do not accept this.

The controller page does not store anything in the controller's browser
beyond the open tab.

## Screen privacy

The app blocks screenshots and hides its content in the Recents screen. Its
notification shows a neutral text on the lock screen.

## Your rights / contact

Since the developer holds no data about you, there is nothing to access, fix
or delete on our side: uninstall the app to erase local data. For questions,
open an issue at <https://github.com/kerstz/lovense-remote/issues> (public) or
use the private channel described in [SECURITY.md](SECURITY.md).

---

# Politique de confidentialité

*Dernière mise à jour : 2026-10-16 · application Android `com.edge2.remote`.*

## En bref

- Pas de compte, pas d'inscription, pas de statistiques, pas de publicité, pas
  de rapport de plantage.
- Le développeur n'exploite **aucun serveur** et ne reçoit **aucune donnée**.
- Tout reste sur votre téléphone, sauf ce que vous choisissez explicitement de
  partager avec une personne qui vous contrôle à distance.

## Données traitées

| Donnée | Destination | Finalité |
|---|---|---|
| Résultats du scan Bluetooth, noms et modèles des jouets | Restent sur le téléphone | Trouver et piloter vos jouets. |
| Commandes d'intensité, niveau de batterie | Téléphone ↔ jouet en Bluetooth | Piloter le jouet. |
| Langue et thème | Stockage privé de l'app | Retenir vos réglages. |
| Paire de clés SSH et clé du relais (seulement si partage Internet) | Stockage privé de l'app | Se reconnecter au relais et détecter une interception. |
| Fichiers de patterns importés par URL | Téléchargés en HTTPS depuis l'adresse **que vous saisissez** | Importer un pattern. Ce serveur voit votre adresse IP. |

Les noms des jouets et leur usage peuvent révéler des informations sur votre
vie sexuelle (catégorie particulière de données, art. 9 RGPD). L'app est conçue
pour que ces informations ne quittent jamais le téléphone sans partage lancé
par vous. Les données de l'app sont exclues des sauvegardes Android ; la
désinstallation les supprime.

## Partage à distance

Désactivé par défaut, toujours lancé par vous : lien secret, PIN à 6 chiffres,
approbation de chaque contrôleur, expiration après 30 minutes. Quand le
contrôle cesse (partage arrêté ou expiré, contrôleur parti ou exclu, tuile
STOP), les jouets sont arrêtés.

- **Même Wi-Fi (LAN)** : le navigateur du contrôleur parle directement à votre
  téléphone. Ce trafic **n'est pas chiffré** : une personne sur le même réseau
  Wi-Fi pourrait lire le PIN, les noms des jouets et les commandes. Ne partagez
  que sur un réseau de confiance.
- **Internet (optionnel, consentement demandé avant)** : le trafic passe par
  **localhost.run**, un service tiers de tunnel SSH non exploité par ce projet.
  Le TLS se termine chez localhost.run, qui peut donc voir votre adresse IP, le
  PIN, le jeton de reconnexion, les noms des jouets et les commandes pendant la
  durée du partage. Ses propres pratiques s'appliquent :
  <https://localhost.run/>. N'utilisez pas le partage Internet si vous ne
  l'acceptez pas.

## Confidentialité à l'écran

L'app bloque les captures d'écran et masque son contenu dans les applications
récentes. Sa notification affiche un texte neutre sur l'écran verrouillé.

## Vos droits / contact

Le développeur ne détient aucune donnée vous concernant : il n'y a rien à
consulter, rectifier ou effacer de notre côté ; désinstallez l'app pour effacer
les données locales. Questions : <https://github.com/kerstz/lovense-remote/issues>
(public) ou le canal privé décrit dans [SECURITY.md](SECURITY.md).
