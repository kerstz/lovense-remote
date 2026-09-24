<div align="center">

<img src="docs/logo.svg" width="104" alt="Logo Lovense Remote">

# Lovense Remote

### Une seule télécommande gratuite pour **800+ toys** de **120+ marques**

**Lovense, Satisfyer, We-Vibe, Svakom, Lelo, Kiiroo, Magic Motion, JoyHub, Hismith, Vorze…**
pilotés directement depuis ton téléphone Android en Bluetooth —
**sans app constructeur, sans compte, sans cloud.**

[English](README.md) · **Français**

<p>
  <img src="https://img.shields.io/badge/Toys-803%20mod%C3%A8les-FF5C7A?style=flat-square" alt="803 modèles">
  <img src="https://img.shields.io/badge/Marques-120%2B-FF8BA1?style=flat-square" alt="120+ marques">
  <img src="https://img.shields.io/badge/Licence-GPLv3-8B6BFF?style=flat-square" alt="GPLv3">
  <img src="https://img.shields.io/badge/Android-8.0%2B-46E0A0?style=flat-square" alt="Android 8+">
  <img src="https://img.shields.io/badge/100%25-AOSP%20·%20sans%20Google-46E0A0?style=flat-square" alt="AOSP">
  <img src="https://img.shields.io/badge/Langues-EN%20·%20FR%20·%20ES-A98BFF?style=flat-square" alt="Langues">
  <img src="https://img.shields.io/github/v/release/kerstz/lovense-remote?style=flat-square&color=FF8BA1" alt="Release">
</p>

<img src="docs/screenshots/01-connection.png" width="232">&nbsp;
<img src="docs/screenshots/02-main.png" width="232">&nbsp;
<img src="docs/screenshots/03-sharing.png" width="232">

**[⬇️ Télécharger l'APK](https://github.com/kerstz/lovense-remote/releases/latest)** ·
**[🧸 Mon toy est-il compatible ?](docs/SUPPORTED_TOYS.md)**

</div>

---

## 💡 En bref

- **Une app au lieu de dix.** Plus besoin d'installer une app différente (et de
  créer un compte différent) pour chaque marque : cette app parle le protocole
  Bluetooth de **803 modèles de toys de 127 familles de marques**.
- **Plusieurs toys en même temps.** Connecte jusqu'à 5 toys — même de marques
  différentes — et pilote-les un par un ou tous ensemble.
- **L'écran s'adapte à ton toy.** Un vibro a un curseur de vibration, un toy
  rotatif un bouton de sens, un toy chauffant un interrupteur de chauffe, un
  stroker un réglage de vitesse… tu ne vois que ce que ton toy sait faire.
- **Privé par conception.** Tout reste sur ton téléphone. Pas d'inscription, pas
  de pistage, pas de services Google. Le partage avec un·e partenaire est
  optionnel et protégé par un PIN et ton accord.

## 🧸 Toys pris en charge — 803 modèles, 127 familles de marques

La liste complète (modèle par modèle, avec les fonctions de chaque toy) est dans
**[`docs/SUPPORTED_TOYS.md`](docs/SUPPORTED_TOYS.md)** (en anglais). Les plus
grandes familles :

| Marque | Modèles | | Marque | Modèles |
|---|---:|---|---|---:|
| **JoyHub** | 159 | | **Sexverse** | 22 |
| **Galaku** | 97 | | **Honey Play Box** | 21 |
| **Satisfyer** | 84 | | **Hismith** | 20 |
| **Svakom** | 51 | | **Lelo** | 15 |
| **Lovense** ✅ | 36 | | **Libo** | 14 |
| **Kiiroo** | 31 | | **Sistalk MonsterPub** | 13 |
| **Magic Motion** | 29 | | **Sensee** | 12 |
| **Foreo** | 26 | | **Love Distance** | 9 |
| **We-Vibe** | 23 | | **MysteryVibe** · **Vorze** | 8 · 7 |

…et aussi **Vibio, VibCrafter, Fluffer, Motorbunny, OSSM, Lioness, Aneros, Je Joue,
Lovehoney, Picobong, Pink Punch, Amorelie, Adrien Lastic, F-Machine, Nexus,
Utimi, Zalo, TryFun, Umove** et environ 80 autres.

**Ce que ces toys peuvent faire dans l'app :**

| Fonction | Toys | Comment on la contrôle |
|---|---:|---|
| 📳 Vibration | 743 | Un curseur par moteur ; pad XY à un pouce pour les toys à deux moteurs |
| 🔄 Rotation | 59 | Curseur + bouton d'inversion du sens |
| ↕️ Va-et-vient | 130 | Curseur de vitesse |
| 💨 Succion / pompe | 51 | Curseur de niveau |
| 🔥 Chauffe | 21 | Interrupteur, ou curseur de température en °C |
| 🎚️ Strokers | 19 | Curseur de vitesse — l'app génère les mouvements |
| 💡 Lumières | 2 | Interrupteur ou luminosité |
| 💧 Pompe à lubrifiant | 2 | Bouton *Spray* |
| 🔋 Batterie | 200+ | Affichée à côté du nom du toy |

> **✅ Testé vs 🧪 expérimental.** Lovense est testé sur du vrai matériel. Toutes
> les autres marques utilisent des protocoles rétro-conçus par la communauté
> [Buttplug](https://github.com/buttplugio/buttplug) et sont marquées
> **expérimental** dans l'app : ça devrait marcher, mais ça n'a pas été vérifié
> sur un vrai toy par ce projet. Les retours (ça marche / ça ne marche pas) sont
> les bienvenus dans les issues !
>
> **🚫 Pas encore pris en charge :** The Handy, KGoal Boost, Muse, Cueme, SayberX,
> Kiiroo V1, Libo Karen, Twerking Butt. Ils apparaissent grisés dans la recherche.

## 🚀 Démarrage rapide

1. **Installe** l'APK depuis la [dernière release](https://github.com/kerstz/lovense-remote/releases/latest)
   (Android 8.0+).
2. **Allume ton toy** — et ferme son app officielle : un toy n'accepte qu'une
   seule connexion à la fois.
3. **Ouvre l'app.** Elle cherche toute seule les toys à proximité ; le tien
   apparaît avec sa marque et son modèle. Touche-le pour te connecter (touche
   les autres pour en ajouter).
4. **Joue.** Curseurs, pad XY, presets ou patterns. **TOUT ARRÊTER** (aussi
   disponible en tuile des réglages rapides) arrête tout immédiatement.

Quelques toys demandent une étape d'appairage, et l'app te le signale : appuie
sur le **bouton d'allumage** des toys Lelo, entre le **PIN 6496** sur Lioness.

## ✨ Toutes les fonctionnalités

| | |
|---|---|
| 🔵 **Bluetooth direct** | Recherche, connexion et pilotage depuis ton téléphone ; reconnexion automatique. |
| 🧸 **Multi-toys** | Jusqu'à 5 toys à la fois, marques mélangées ; pilote-en un ou **tous**. |
| 🎚 **Contrôles adaptés** | Un contrôle par fonction du toy (voir le tableau ci-dessus). |
| 👍 **Pad XY à une main** | Un pouce pilote les deux moteurs des toys à deux moteurs. |
| 🎛 **Patterns** | Patterns intégrés, import Lovense `.ta`, mode **Tease** aléatoire, mode **enregistrement** (joue → sauvegarde en pattern). |
| 🔗 **Contrôle à distance par lien** | Un·e partenaire pilote tes toys depuis le **même Wi-Fi** ou **par internet / 4G** (voir plus bas). |
| 🌙 **Arrière-plan** | Continue de fonctionner app fermée ; tuile **STOP** dans les réglages rapides. |
| 🌍 **Langues & thèmes** | Anglais / français / espagnol, sombre & clair. |
| 🟢 **100 % AOSP** | Sans Google Play Services — marche sur GrapheneOS, LineageOS… |

## 🔗 Contrôle à distance par lien

1. Touche **Partager** : tu obtiens un lien *Même Wi-Fi* et un lien *Internet*.
2. Envoie le lien à ton/ta partenaire, et donne-lui le **PIN à 6 chiffres** à part.
3. Quand il/elle l'ouvre, **tu l'acceptes** sur ton téléphone. Seulement là, il/elle
   peut piloter tes toys, depuis n'importe quel navigateur — sans app de son côté.
4. Arrête le partage quand tu veux ; il expire aussi tout seul après 30 minutes.

## 🔒 Sécurité & vie privée

Connaître le lien ne suffit jamais pour piloter tes toys :

- **Lien secret** — identifiant aléatoire de 128 bits, nouveau à chaque partage ;
  les anciens liens meurent immédiatement.
- **PIN à 6 chiffres** — jamais dans l'URL, vérifié en temps constant ; **5 codes
  faux coupent le partage**.
- **Ton accord, pour chaque personne** — tu acceptes (ou éjectes) chaque
  contrôleur individuellement.
- **Page web durcie** — CSP stricte, anti-iframe, contrôle de l'`Origin` contre
  le détournement de WebSocket, petites trames, limitation de débit, 3 sockets
  max, 10 s pour s'authentifier.
- **Surface réseau minimale** — écoute seulement sur 127.0.0.1 et l'IP Wi-Fi,
  jamais sur les données mobiles / le VPN.
- **Clé du relais mémorisée** — personne ne peut se faire passer pour le relais internet.
- **Aucune sauvegarde** des données de l'app ; les deep links sont validés et
  demandent ta confirmation.

**Limites à connaître :** le partage sur le même Wi-Fi est en HTTP non chiffré
(utilise un Wi-Fi de confiance). Le partage internet passe par
[localhost.run](https://localhost.run), un tiers qui peut voir le trafic de
contrôle — le PIN et ton accord restent la barrière. Si tu utilises un pare-feu,
autorise l'**accès réseau** pour l'app, sinon le partage ne peut pas démarrer.

## ❓ FAQ

<details>
<summary><b>Mon toy n'apparaît pas.</b></summary>

Vérifie qu'il est allumé, qu'il n'est pas connecté à son app officielle ou à un
autre téléphone, et que les autorisations Bluetooth et localisation / appareils à
proximité sont accordées. Puis regarde [la liste](docs/SUPPORTED_TOYS.md) : s'il
n'y est pas, ouvre une issue avec le nom que le toy affiche dans une app de scan BLE.
</details>

<details>
<summary><b>Que veut dire « expérimental » ? Ça peut abîmer mon toy ?</b></summary>

Ça veut dire que les commandes viennent de documentation communautaire et n'ont
pas été essayées sur ce modèle par ce projet. L'app envoie uniquement le même type
de commandes que les apps officielles, et seulement sur les canaux connus pour ce modèle.
</details>

<details>
<summary><b>Pourquoi « Lovense Remote » si d'autres marques sont prises en charge ?</b></summary>

Le projet a commencé comme télécommande Lovense uniquement ; le multi-marque est
venu ensuite. Il n'est affilié ni à Lovense ni à aucune autre marque.
</details>

## 🛠 Pour les développeurs

```bash
ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleDebug   # compilation
./gradlew :app:testDebugUnitTest                           # tests unitaires
```

Kotlin + Jetpack Compose · minSdk 26 · target/compile SDK 35.

<details>
<summary>🧩 Architecture</summary>

- `ble/` — recherche et identification (`ToyManager`), une `ToyConnection` par
  toy (GATT à endpoints nommés, opérations sérialisées, écritures regroupées,
  keepalive, générateur de mouvements, batterie, reconnexion).
- `ble/db/` — la base d'appareils (`assets/devices.json`, générée depuis la config
  Buttplug par `tools/gen_devices.py`).
- `ble/proto/` — un `ProtocolHandler` par protocole (~128), porté depuis Buttplug :
  handshakes, chiffrement, checksums, commandes par fonction. Voir
  [`docs/research/other-brands.md`](docs/research/other-brands.md).
- `RemoteEngine` — cœur au niveau du process (BLE + serveur + tunnel + état), pour
  que le contrôle survive à la fermeture de l'app.
- `remote/` — serveur HTTP + WebSocket embarqué, page de contrôle web, tunnel
  internet (`SshTunnel`).

Pour régénérer la liste des toys après une mise à jour de la base :
`python3 tools/gen_supported.py`.
</details>

## ⚖️ Licence & crédits

[GPLv3](LICENSE). Non affilié à Lovense ni à aucune autre marque citée ici, ni
approuvé par elles.

La base d'appareils et les implémentations de protocoles viennent de
[Buttplug](https://github.com/buttplugio/buttplug) (BSD-3-Clause, © Nonpolynomial
Labs) — voir [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Un grand merci à
la communauté Buttplug / Intiface pour des années de rétro-ingénierie.

> 🤖 **Vibe codé** — cette app a été écrite en grande partie avec un assistant de
> code IA, puis relue et testée à la main. Lis le code (et la section sécurité)
> avant de lui confier quoi que ce soit d'important.
