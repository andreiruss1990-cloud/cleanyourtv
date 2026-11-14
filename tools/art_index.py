#!/usr/bin/env python3
"""Write art/index.json, the list of painting collections the launcher syncs (theme key art_source).

    python3 -I tools/art_index.py            # from the repository root, then commit and push art/

Two kinds of collections go in:

* the museum collections in art/museums.json (tools/art_build.py): paintings the TV downloads straight from the
  museum's IIIF image server, so no picture is stored in this repository;
* your own folders: every folder under art/ holding .jpg/.jpeg/.png/.webp pictures becomes a collection under
  "Your folders", with an optional name.txt (the name shown on the TV), captions.txt ("file<TAB>Title|Painter, year")
  and tags.txt ("file<TAB>winter,rain"; Auto uses spring, summer, autumn, winter, rain, snow, fog). The TV downloads
  these pictures from this repository.

index.json (version 2) lists every picture once under "pictures" (name -> url, fit, caption, tags) and each collection
as a list of picture names, so a painting in several collections is stored once on the TV. The TV picks a change up
within art_refresh_hours (6 by default) the next time Home starts.
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "art")
PICTURES = (".jpg", ".jpeg", ".png", ".webp")


def table(path):
    out = {}
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if line.startswith("#") or "\t" not in line:
                    continue
                name, value = line.rstrip("\n").split("\t", 1)
                out[name.strip()] = value.strip()
    return out


def main():
    pictures, collections = {}, []
    museums = os.path.join(ART, "museums.json")
    if os.path.isfile(museums):
        m = json.load(open(museums, encoding="utf-8"))
        for key, pic in m["pictures"].items():
            pictures[f"{key}.jpg"] = pic
        for c in m["collections"]:
            collections.append({"id": c["id"], "name": c["name"], "group": c["group"],
                                "files": [f"{k}.jpg" for k in c["files"]]})
    if os.path.isdir(ART):
        for folder in sorted(os.listdir(ART)):
            path = os.path.join(ART, folder)
            if not os.path.isdir(path):
                continue
            files = [f for f in sorted(os.listdir(path)) if f.lower().endswith(PICTURES)]
            if not files:
                continue
            captions = table(os.path.join(path, "captions.txt"))
            tags = table(os.path.join(path, "tags.txt"))
            name_file = os.path.join(path, "name.txt")
            name = open(name_file, encoding="utf-8").read().strip() if os.path.isfile(name_file) \
                else folder.replace("-", " ").capitalize()
            names = []
            for f in files:
                n = f"{folder}--{f}".replace(" ", "-")
                pictures[n] = {"url": f"art/{folder}/{f}", "fit": "auto", "caption": captions.get(f, ""),
                               "tags": [t.strip() for t in tags.get(f, "").split(",") if t.strip()]}
                names.append(n)
            collections.append({"id": f"own-{folder}", "name": name, "group": "Your folders", "files": names})
    with open(os.path.join(ART, "index.json"), "w", encoding="utf-8") as f:
        json.dump({"version": 2, "pictures": pictures, "collections": collections}, f, ensure_ascii=False, indent=1)
    for c in collections:
        print(f"{c['group']} / {c['name']}: {len(c['files'])}", file=sys.stderr)
    print(f"{len(pictures)} pictures", file=sys.stderr)


if __name__ == "__main__":
    main()
