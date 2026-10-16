# 📦 Upload app F-Droid — checklist

Mémo issu de la soumission de **com.edge2.remote** (Lovense Remote) sur
[fdroiddata MR !41063](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/41063).

## 0. Prérequis app (sinon refus direct)
- **100% FOSS** : code source public (GitHub/GitLab), zéro dépendance proprio (pas de GMS, Firebase, analytics, AdMob…).
- **Licence libre** : fichier `LICENSE` + SPDX (ex. `GPL-3.0-only`).
- Pas de **blob committé** (`.jar`/`.aar`/`.so`/`.apk`) hors `gradle-wrapper.jar`.
- Repos maven libres seulement (`google()`, `mavenCentral()` OK ; pas de jcenter mort/proprio).
- **AntiFeatures honnêtes** : si l'app phone-home vers un service fermé → `NonFreeNet` (+ raison).

## 1. Préparer le repo source
- `versionCode` **monotone** (jamais réutilisé), `versionName` propre.
- **Tag** de version `vX.Y.Z` (le build F-Droid pointe dessus).
- ⚠️ `build.gradle.kts` → désactive le **DependencyInfoBlock** (bloc chiffré Google, casse repro) :
  ```kotlin
  android {
      dependenciesInfo {
          includeInApk = false
          includeInBundle = false
      }
  }
  ```
- Pas d'**appel externe caché** : Google Fonts CDN, trackers, etc. → self-host / vire-les.
- **Fastlane metadata** dans le repo source (F-Droid les récupère auto) :
  `fastlane/metadata/android/<locale>/` → `title.txt`, `short_description.txt`, `full_description.txt`, `images/phoneScreenshots/`.

## 2. Reproducible build (Binaries + AllowedAPKSigningKeys)
- 🔑 **Keystore = sacré** : sauvegarde `.jks` + 3 mots de passe en **plusieurs** lieux sûrs. Perte = plus jamais de MAJ matchable.
- Signe l'APK release, **publie-le** sur une URL stable (GitHub Releases).
- Récupère l'empreinte cert :
  ```bash
  apksigner verify --print-certs app-release.apk | grep -i SHA-256
  ```
  → hex minuscule = `AllowedAPKSigningKeys`.

## 3. Metadata fdroiddata
- **Fork** `gitlab.com/fdroid/fdroiddata`, branche `add-<applicationId>`.
- Fichier `metadata/<applicationId>.yml`, **ordre canonique** des champs (lint strict) :
  ```yaml
  AntiFeatures:
    NonFreeNet:
      en-US: <raison>          # map + raison, PAS une simple liste
  Categories:
    - Remote Controller        # vraie catégorie, pas un fourre-tout
  License: GPL-3.0-only
  AuthorName: ...
  SourceCode: ...
  IssueTracker: ...
  AutoName: ...
  RepoType: git
  Repo: ...git
  Binaries: https://github.com/<u>/<repo>/releases/download/v%v/app-release.apk
  Builds:
    - versionName: 0.1.1
      versionCode: 2
      commit: <sha ou tag>
      subdir: app
      gradle:
        - yes
  AllowedAPKSigningKeys: <sha256>
  AutoUpdateMode: Version
  UpdateCheckMode: Tags
  CurrentVersion: 0.1.1
  CurrentVersionCode: 2
  ```
  `%v` = versionName, `%c` = versionCode.
- **Lint avant push** (si `fdroidserver` installé) : `fdroid lint <applicationId>`.
- Build repro en local (optionnel) : `fdroid build -v -l <applicationId>`.

## 4. Soumettre la MR
- Push branche → **MR vers fdroiddata** (`New app: <Nom>`).
- Reviewers (linsui, licaon-kter…) commentent → corrige.
- **Pièges fréquents** (rencontrés) : raisons AntiFeatures manquantes, mauvaise catégorie, DependencyInfoBlock, Google Fonts CDN.

## 5. Après soumission
- **Grosse file d'attente** → review/test manuel + build repro, peut prendre **longtemps**.
- Nouvelle version ? → **mets à jour la MR** (commit sur la branche du fork).
- Merge si le build est reproductible et tout passe.

## 🛠 Outils
`gh` (GitHub) · `glab` (GitLab) · `apksigner` (SDK build-tools) · `fdroidserver` (lint/build) · CI release sur tag (`.github/workflows/release.yml`).

## ✅ Vérifier un APK publié (avant maj du MR)
```bash
# cert (doit == AllowedAPKSigningKeys)
apksigner verify --print-certs app-release.apk | grep -i SHA-256
# DependencyInfoBlock absent ? (id 0x504b4453 ne doit PAS apparaître)
# fonts/assets self-host ? (0 ref googleapis attendu)
unzip -p app-release.apk assets/controller.html | grep -c googleapis
```

## 📚 Sources
- [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)
- [Submitting Quick Start](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [Catégories F-Droid](https://f-droid.org/categories/)
