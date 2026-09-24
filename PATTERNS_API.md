# Lovense Patterns — API & format (reverse-engineered)

> Source: decompilation of `com.lovense.wear` 7.95.1 (jadx) + network probing.
> For personal/open-source use. ⚠️ Hitting the Lovense API touches their ToS.

## Community library (cloud)
- Base: `https://apps.lovense-api.com/`
- List/search: `POST /wear/pattern/v4/find` — body `SearchPatternBean`
  (fields: `page`, `pageSize`, `type`, `platform="android"`, `toys[]`,
  `minDuration`, `maxDuration`, `patternCategory`, `flexibleTagList`,
  `userTimezoneOffset`, `appVersion`, `connectedToy`).
  → returns `List<SearchPatternInfoBean>`.
- Ads: `GET /wear/pattern/advertisement?version&lang&pf&userTimezoneUtcOffset&author`
- Upload: `POST /wear/pattern/add_v2`, `POST /wear/pattern/add_v2_upload` (multipart)

### Useful `SearchPatternInfoBean` fields
`name`, `author`, `duration`, `timer`, `toyName`, `toyFunc`, `toySymbol`,
`version`/`version2`, and above all **`data`** (inline intensities) + **`cdnPath`/`path`**
(URL of the `.ta` file).

### Auth — blocking for browsing
Every `/wear/...` request requires the **`gtoken`** header (+ `ver`).
`gtoken = a.i(WearUtils.f34649y)` = **the logged-in Lovense account token**,
encrypted client-side. **No guest/anonymous endpoint** was found → browsing the
library in-app would require reversing the account login + the encryption
(heavy, fragile, ToS). Not implemented.

## `.ta` files — PUBLIC (no auth) ✅
Downloadable with a plain `GET` on the CDN. This is what the app uses
(`LovenseImporter` / `LovenseTa`).

### `.ta` format
```
V:1;T:Ambi;F:v;S:100;M:<md5>;#
0;3;8;9;9;7;11;13;11;12;...
```
- Header (before `#`): `V`=version, `T`=toy type, `F`=feature (`v`=vibrate),
  `S`=scale, `M`=md5 of the content.
- Body (after `#`): intensities separated by `;` (or `,` depending on the version), **0..20**.
- Playback: **1 point / 100 ms** (`PatternPlayManagerImpl.f31925m = 100`,
  `Observable.interval(100, MILLISECONDS)`).

→ Direct conversion to our `Pattern`: each value ⇒ `PatternStep{m1,m2,100ms}`.

## Related toy commands (`commandcore/constant` registry)
- `Preset:%s;` — patterns **built into** the toy (possible cloud-free future option)
- `Pattern:Clear;` — clears the current pattern
- `Level:%s;`, `SetLevel:%s:%s;`, `SetL:%s:%s:%s:%s:%s:%s;`
- (+ `Vibrate1:`/`Vibrate2:` confirmed, see PROTOCOL.md)
