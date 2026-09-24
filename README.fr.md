<div align="center">

<img src="docs/logo.svg" width="104" alt="Logo Lovense Remote">

# Lovense Remote

**Pilote tes toys en Bluetooth direct — sans app, sans compte, sans cloud.**

Télécommande Android open source. Plusieurs toys en même temps, plusieurs marques, l'interface s'adapte à chacun.

[English](README.md) · **Français**

<img src="docs/screenshots/01-connection.png" width="232">&nbsp;
<img src="docs/screenshots/02-main.png" width="232">&nbsp;
<img src="docs/screenshots/03-sharing.png" width="232">

</div>

> ⚠️ Projet communautaire expérimental. **Non affilié à Lovense.**
>
> 🤖 **Vibe codé** — cette app a été écrite en grande partie avec un assistant de
> code IA, puis relue et testée à la main. Lis le code (et la section Sécurité)
> avant de lui confier quoi que ce soit d'important.

---

## ✨ Fonctionnalités

- **🔵 BLE direct** — scan, connexion et pilotage depuis ton téléphone.
- **🧸 Plusieurs toys à la fois** — jusqu'à 5 toys, pilotés un par un ou **tous**
  ensemble ; patterns, presets et contrôle à distance suivent la cible choisie.
- **🏷 Multi-marque** — Lovense (Lush, Hush, Edge, Nora, Max, Domi, Ferri,
  Gemini…) et, en **expérimental**, We-Vibe, Vorze et Magic Motion (voir
  [`docs/research/other-brands.md`](docs/research/other-brands.md)). L'interface
  s'adapte aux actionneurs de chaque toy (vibration, double moteur, rotation, succion).
- **👍 Pad XY à une main** — un pouce pilote les deux moteurs des toys à deux moteurs.
- **🎛 Patterns** — intégrés, import Lovense `.ta`, mode **Tease** aléatoire et
  mode **enregistrement** (joue → pattern sauvegardé).
- **🔗 Contrôle à distance par lien** — un·e partenaire pilote tes toys depuis le
  **même Wi-Fi** ou **par internet / 4G**, protégé par un lien secret, un **PIN à
  6 chiffres**, ton accord **pour chaque contrôleur** et une expiration automatique.
- **🌙 Arrière-plan** — la liaison reste active app fermée, plus une tuile **STOP**
  dans les réglages rapides.
- **🌍 Langues & thèmes** — anglais (par défaut) / français / espagnol, sombre & clair.
- **🟢 100 % AOSP** — sans Google Play Services (compatible GrapheneOS).

## 📲 Installation

Télécharge l'APK signé depuis la [dernière release](https://github.com/kerstz/lovense-remote/releases/latest),
ou compile-le toi-même.

> Si tu utilises un pare-feu / une app de contrôle réseau, autorise l'**accès
> réseau** pour l'app, sinon le partage ne peut pas ouvrir son socket.

## 🛠 Compilation

```bash
ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleDebug
```

Kotlin + Jetpack Compose · minSdk 26 · target/compile SDK 35.

## 🔒 Sécurité & vie privée

Le contrôle à distance est protégé en couches, connaître le lien ne suffit jamais :

- **Lien secret** — identifiant de session aléatoire de 128 bits (SecureRandom),
  nouveau à chaque partage ; les anciens liens meurent immédiatement.
- **PIN à 6 chiffres**, envoyé dans le WebSocket (jamais dans une URL, donc jamais
  dans les logs du relais), comparé en temps constant. **5 codes faux coupent le partage.**
- **Ton accord, pour chaque contrôleur** — un contrôleur authentifié ne pilote rien
  tant que tu ne l'as pas accepté *lui* ; tu peux en refuser/éjecter un sans couper
  le partage. Un contrôleur accepté reçoit un jeton de reprise en cas de coupure réseau.
- **Durcissement web** — CSP stricte (script épinglé par empreinte), pas de
  referrer, anti-iframe, contrôle de l'`Origin` contre le détournement de
  WebSocket, frames de 256 octets, limitation de débit, 3 sockets max, 10 s pour
  s'authentifier.
- **Surface réseau minimale** — écoute seulement sur 127.0.0.1 (pour le tunnel) et
  l'IP Wi-Fi, jamais sur le cellulaire/VPN. Expiration après 30 min.
- **Clé du relais mémorisée** (confiance à la première connexion) — un attaquant ne
  peut plus se faire passer pour localhost.run ; une clé modifiée bloque le lien internet.
- **Aucune sauvegarde** des données de l'app (clé du tunnel, réglages) ; les deep
  links sont validés et demandent ta confirmation.

Limites à connaître : le partage LAN est en HTTP non chiffré (Wi-Fi de confiance
uniquement). Le partage internet passe par **localhost.run** (un tiers) qui termine
le TLS et peut voir le trafic de contrôle — le PIN et ton accord restent la barrière.
Les marques autres que Lovense sont expérimentales (données communautaires, non testées ici).

## ⚖️ Licence

[GPLv3](LICENSE). Non affilié à Lovense, ni approuvé par Lovense.
