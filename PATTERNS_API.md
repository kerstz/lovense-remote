# Lovense Patterns — API & format (reverse-engineered)

> Source : décompilation de `com.lovense.wear` 7.95.1 (jadx) + sondage réseau.
> Pour usage perso/open-source. ⚠️ Hitter l'API Lovense touche leurs ToS.

## Bibliothèque communautaire (cloud)
- Base : `https://apps.lovense-api.com/`
- Liste/recherche : `POST /wear/pattern/v4/find` — body `SearchPatternBean`
  (champs : `page`, `pageSize`, `type`, `platform="android"`, `toys[]`,
  `minDuration`, `maxDuration`, `patternCategory`, `flexibleTagList`,
  `userTimezoneOffset`, `appVersion`, `connectedToy`).
  → renvoie `List<SearchPatternInfoBean>`.
- Pub : `GET /wear/pattern/advertisement?version&lang&pf&userTimezoneUtcOffset&author`
- Upload : `POST /wear/pattern/add_v2`, `POST /wear/pattern/add_v2_upload` (multipart)

### Champs utiles de `SearchPatternInfoBean`
`name`, `author`, `duration`, `timer`, `toyName`, `toyFunc`, `toySymbol`,
`version`/`version2`, et surtout **`data`** (intensités inline) + **`cdnPath`/`path`**
(URL du fichier `.ta`).

### Auth — bloquant pour le browse
Toutes les requêtes `/wear/...` exigent l'en-tête **`gtoken`** (+ `ver`).
`gtoken = a.i(WearUtils.f34649y)` = **token du compte Lovense connecté**, chiffré
côté client. **Aucun endpoint invité/anonyme** trouvé → parcourir la bibliothèque
in-app nécessiterait de reverser le login compte + le chiffrement (lourd, fragile,
ToS). Non implémenté.

## Fichiers `.ta` — PUBLICS (pas d'auth) ✅
Téléchargeables en simple `GET` sur le CDN. C'est ce que notre app exploite
(`LovenseImporter` / `LovenseTa`).

### Format `.ta`
```
V:1;T:Ambi;F:v;S:100;M:<md5>;#
0;3;8;9;9;7;11;13;11;12;...
```
- Header (avant `#`) : `V`=version, `T`=type de toy, `F`=feature (`v`=vibrate),
  `S`=scale, `M`=md5 du contenu.
- Corps (après `#`) : intensités séparées par `;` (ou `,` selon version), **0..20**.
- Lecture : **1 point / 100 ms** (`PatternPlayManagerImpl.f31925m = 100`,
  `Observable.interval(100, MILLISECONDS)`).

→ Conversion directe vers notre `Pattern` : chaque valeur ⇒ `PatternStep{m1,m2,100ms}`.

## Commandes toy liées (registre `commandcore/constant`)
- `Preset:%s;` — patterns **intégrés** au toy (option future sans cloud)
- `Pattern:Clear;` — efface le pattern courant
- `Level:%s;`, `SetLevel:%s:%s;`, `SetL:%s:%s:%s:%s:%s:%s;`
- (+ `Vibrate1:`/`Vibrate2:` confirmés, cf. PROTOCOL.md)
