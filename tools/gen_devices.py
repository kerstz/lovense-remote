#!/usr/bin/env python3
"""Generates app/src/main/assets/devices.json from the Buttplug device config.

The Buttplug device database (BSD-3-Clause, (c) Nonpolynomial Labs, LLC — see
THIRD_PARTY_NOTICES.md) lists how hundreds of toys advertise themselves over
Bluetooth LE and which outputs (vibrate, rotate, heat…) each model has. This
script keeps only the Bluetooth LE data the app needs, in a compact form.

Usage:
  tools/gen_devices.py <buttplug-device-config-v5.json> [output.json]

The JSON ships inside the `buttplug_server_device_config` crate
(build-config/buttplug-device-config-v5.json).
"""
import json
import sys

OUTPUT_TYPES = [
    "vibrate", "rotate", "oscillate", "constrict", "temperature",
    "led", "spray", "position", "hw_position_with_duration",
]


def features(raw):
    out = []
    for f in raw or []:
        outputs = {}
        for t, v in (f.get("output") or {}).items():
            if t not in OUTPUT_TYPES:
                continue
            entry = {"r": v["value"]}
            if "duration" in v:
                entry["d"] = v["duration"]
            outputs[t] = entry
        battery = "battery" in (f.get("input") or {})
        if not outputs and not battery:
            continue
        item = {"i": f["index"]}
        if outputs:
            item["o"] = outputs
        if battery:
            item["battery"] = True
        if f.get("description"):
            item["desc"] = f["description"]
        out.append(item)
    return out


def device(raw, with_ids):
    d = {"name": raw.get("name", "")}
    if with_ids:
        d["ids"] = raw.get("identifier", [])
    if "features" in raw:
        d["f"] = features(raw["features"])
    if raw.get("protocol_variant"):
        d["variant"] = raw["protocol_variant"]
    return d


def main():
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/devices.json"
    cfg = json.load(open(src, encoding="utf-8"))
    protocols = []
    for pid, p in sorted(cfg["protocols"].items()):
        btle = [c["btle"] for c in p.get("communication", []) if "btle" in c]
        if not btle:
            continue  # the app only speaks Bluetooth LE
        b = btle[0]
        spec = {
            "names": sorted(b.get("names", [])),
            "services": b.get("services", {}),
        }
        if b.get("manufacturer_data"):
            spec["mfr"] = [{"c": m["company"], "d": m.get("data", [])} for m in b["manufacturer_data"]]
        if b.get("advertised_services"):
            spec["adv"] = sorted(b["advertised_services"])
        entry = {"id": pid, "btle": spec}
        if p.get("defaults"):
            entry["defaults"] = device(p["defaults"], with_ids=False)
            entry["defaults"].setdefault("f", [])
        entry["configs"] = [device(c, with_ids=True) for c in p.get("configurations", []) if c.get("identifier")]
        protocols.append(entry)
    out = {
        "source": "Buttplug device config v%s (BSD-3-Clause, Nonpolynomial Labs, LLC)" % cfg.get("version", {}).get("major", "?"),
        "protocols": protocols,
    }
    with open(dst, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
    n = sum(1 + len(p["configs"]) for p in protocols)
    print("%d protocols, %d device entries → %s" % (len(protocols), n, dst))


if __name__ == "__main__":
    main()
