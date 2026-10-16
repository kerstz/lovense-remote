#!/usr/bin/env python3
"""Generates docs/toys.json (used by the docs/index.html search) from docs/SUPPORTED_TOYS.md.

Usage: tools/gen_site_data.py   (run after tools/gen_supported.py)
"""
import json
import re

SRC = "docs/SUPPORTED_TOYS.md"
OUT = "docs/toys.json"
FUNCS = {"📳": "vibration", "🔄": "rotation", "↕️": "thrust", "💨": "suction",
         "🔥": "heat", "💡": "light", "💧": "pump", "🎚️": "stroker"}

brands, current, unsupported = [], None, False
for line in open(SRC, encoding="utf-8"):
    line = line.rstrip("\n")
    if line.startswith("## "):
        title = line[3:].strip()
        unsupported = title.startswith("Recognised but not supported")
        if unsupported:
            current = None
            continue
        tested = not title.endswith("(experimental)")
        name = re.sub(r"\s*\(experimental\)$", "", title)
        current = {"brand": name, "tested": tested, "toys": []}
        brands.append(current)
    elif line.startswith("- ") and current is not None:
        text = line[2:]
        fns = [v for k, v in FUNCS.items() if k in text]
        for k in FUNCS:
            text = text.replace(k, "")
        current["toys"].append([text.strip(), fns])

data = {"models": sum(len(b["toys"]) for b in brands), "brands": brands}
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
print(f"{data['models']} models, {len(brands)} brands -> {OUT}")
