# Relais Edge2 Remote (Phase 4B)

Petit serveur qui rend le contrôle à distance accessible **hors du réseau local**,
sans ouvrir de port ni dépendre d'un tiers (ngrok/Cloudflare) qui verrait passer le
trafic. Tu l'héberges toi-même → toi seul vois passer les commandes.

## Pourquoi un relais (et pas un port forward)

Le téléphone est derrière un NAT : injoignable depuis Internet. Le relais inverse
le sens : le **téléphone se connecte en sortant** au relais (`/host`), le
**contrôleur web** s'y connecte aussi (`/ctrl`), et le relais recopie juste les
trames de commande `contrôleur → host`. Il ne stocke rien et ne voit que du texte
opaque `M1/M2/B/S`. Le seul secret est l'`id` de session (dans l'URL `/s/<id>`).

```
téléphone (host)  --WS sortant-->  RELAIS  <--WS--  navigateur (contrôleur)
        ^                          /host    /ctrl              |
        |__________ commandes M1/M2/B/S recopiées _____________|
```

## Lancer en local (test)

```bash
RELAY_ONLY=1 ./gradlew :relay:run          # écoute sur :8080
# page de contrôle : http://localhost:8080/s/<id>
```

Pointe l'app sur ce relais en réglant `RelayConfig.BASE_URL` (dans
`app/src/main/java/com/edge2/remote/remote/RelayConfig.kt`) sur l'IP LAN de ta
machine, ex. `http://192.168.1.20:8080`.

## Déployer sur fly.io (internet)

Pré-requis : `flyctl` installé + `fly auth login`. Le `Dockerfile` et `fly.toml`
sont à la **racine du repo** (le build a besoin du repo entier).

```bash
# depuis la racine du repo
fly launch --no-deploy      # crée l'app (garde fly.toml ; choisis un nom unique)
fly deploy                  # build l'image (RELAY_ONLY=1 → pas de SDK Android) + push
```

fly fournit `https://<app>.fly.dev` (TLS auto → `wss://`). Mets cette URL dans
`RelayConfig.BASE_URL`, rebuild l'APK, et le bouton « Partager » affichera un lien
internet en plus du lien LAN.

> `min_machines_running = 0` : la machine s'éteint au repos (gratuit), se rallume
> à la première connexion. Premier lien peut mettre ~2 s à répondre (cold start).

## Endpoints

| Méthode | Chemin        | Rôle                                            |
|---------|---------------|-------------------------------------------------|
| GET     | `/s/{id}`     | Page de contrôle web (WebSocket vers `/ctrl`)   |
| WS      | `/host?id=`   | Le téléphone s'enregistre comme host            |
| WS      | `/ctrl?id=`   | Un contrôleur rejoint ; trames poussées au host |
