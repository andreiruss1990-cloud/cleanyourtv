#!/usr/bin/env python3
"""Gather open-access paintings (CC0) from the National Gallery of Art and the Rijksmuseum into one candidate list.

    python3 -I tools/art_catalog.py nga  <nga data dir>  <out.json>      # from the NGA open data CSVs
    python3 -I tools/art_catalog.py rijks <out.json>                      # from the Rijksmuseum's public data API

<nga data dir> holds objects.csv, objects_terms.csv, published_images.csv, constituents.csv and objects_constituents.csv
from https://github.com/NationalGalleryOfArt/opendata (data/). Each candidate is
{"key", "museum", "title", "artist", "year", "iiif", "width", "height", "style"}: "iiif" is the image's IIIF base,
so a picture is "<iiif>/<region>/<size>/0/default.jpg". tools/art_sheets.py lays the candidates out for tagging and
tools/art_build.py turns the tagged ones into art/index.json.

Only paintings from 1700 to 1960 are kept, and not battles, hunts or formal portraits (the person's choice).
"""
import csv
import json
import re
import sys
import time
import urllib.request

UA = {"User-Agent": "art-collections/1.0 (personal TV wallpaper list)"}
YEARS = (1700, 1960)
SKIP_TITLE = re.compile(r"\b(portrait|portret|battle|slag bij|hunt|jacht|bildnis)\b", re.I)
SKIP_THEMES = {"portrait", "military"}


def nga(data, out):
    csv.field_size_limit(10 ** 9)

    def read(name):
        with open(f"{data}/{name}", encoding="utf-8") as f:
            yield from csv.DictReader(f)

    paintings = {r["objectid"]: r for r in read("objects.csv") if r["classification"] == "Painting"}
    themes, styles = {}, {}
    for r in read("objects_terms.csv"):
        o = r["objectid"]
        if o in paintings:
            if r["visualbrowsertheme"]:
                themes.setdefault(o, set()).add(r["visualbrowsertheme"])
            if r["visualbrowserstyle"]:
                styles.setdefault(o, set()).add(r["visualbrowserstyle"])
    rows = []
    for r in read("published_images.csv"):
        o = r["depictstmsobjectid"]
        if o not in paintings or r["openaccess"] != "1" or r["viewtype"] != "primary":
            continue
        p = paintings[o]
        year = int(p["beginyear"] or 0)
        w, h = int(r["width"] or 0), int(r["height"] or 0)
        if not YEARS[0] <= year <= YEARS[1] or min(w, h) < 1200:
            continue
        if themes.get(o, set()) & SKIP_THEMES or SKIP_TITLE.search(p["title"]):
            continue
        rows.append({"key": f"nga-{o}", "museum": "National Gallery of Art", "title": p["title"],
                     "artist": p["attribution"], "year": year, "iiif": r["iiifurl"], "width": w, "height": h,
                     "style": sorted(styles.get(o, ())), "theme": sorted(themes.get(o, ()))})
    json.dump(rows, open(out, "w"), ensure_ascii=False, indent=0)
    print(f"nga: {len(rows)} candidates", file=sys.stderr)


def get(url, accept=None):
    headers = dict(UA)
    if accept:
        headers["Accept"] = accept
    for attempt in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
                return json.loads(r.read())
        except Exception:
            time.sleep(2 + attempt * 3)
    return None


ENGLISH = "http://vocab.getty.edu/aat/300388277"


def label(node):
    """The English text of a Linked Art name list (language marked by its Getty AAT id), else the first one."""
    names = [n for n in node or [] if n.get("type") == "Name" and n.get("content")]
    for n in names:
        if any(l.get("id") == ENGLISH for l in n.get("language", [])):
            return n["content"]
    return names[0]["content"] if names else ""


def person(who):
    """A Linked Art person's English name: its notation, then its _label or names."""
    notation = who.get("notation", [])
    if isinstance(notation, (str, dict)):
        notation = [notation]
    for n in notation:
        if isinstance(n, str):
            return n
        if n.get("@language") == "en":
            return n.get("@value", "")
    return who.get("_label") or label(who.get("identified_by"))


def rijks(out):
    ids = []
    for century in ("17", "18", "19"):
        url = f"https://data.rijksmuseum.nl/search/collection?type=painting&imageAvailable=true&creationDate={century}*"
        while url:
            page = get(url)
            if not page:
                break
            ids += [i["id"] for i in page.get("orderedItems", [])]
            url = (page.get("next") or {}).get("id")
            time.sleep(0.2)
    rows = []
    for n, oid in enumerate(ids):
        rec = get(oid, "application/ld+json")
        time.sleep(0.15)
        if not rec:
            continue
        text = json.dumps(rec)
        m = re.search(r"https://iiif\.micr\.io/([A-Za-z0-9]+)/", text)
        if not m:
            continue
        title = label(rec.get("identified_by"))
        prod = rec.get("produced_by") or {}
        artist = ""
        for part in prod.get("part", []) + [prod]:
            for who in part.get("carried_out_by", []):
                artist = artist or person(who)
        years = re.findall(r"\b(1[6-9]\d\d)\b", json.dumps(prod.get("timespan", {})))
        year = int(years[0]) if years else 0
        if not YEARS[0] <= year <= YEARS[1] or SKIP_TITLE.search(title):
            continue
        base = f"https://iiif.micr.io/{m.group(1)}"
        info = get(base + "/info.json")
        if not info:
            continue
        w, h = int(info.get("width", 0)), int(info.get("height", 0))
        if min(w, h) < 1200:
            continue
        rows.append({"key": f"rijks-{oid.rsplit('/', 1)[-1]}", "museum": "Rijksmuseum", "title": title,
                     "artist": artist, "year": year, "iiif": base, "width": w, "height": h, "style": [], "theme": []})
        if n % 100 == 0:
            print(f"rijks: {n}/{len(ids)} looked at, {len(rows)} kept", file=sys.stderr, flush=True)
    json.dump(rows, open(out, "w"), ensure_ascii=False, indent=0)
    print(f"rijks: {len(rows)} candidates", file=sys.stderr)


if __name__ == "__main__":
    if sys.argv[1] == "nga":
        nga(sys.argv[2], sys.argv[3])
    else:
        rijks(sys.argv[2])
