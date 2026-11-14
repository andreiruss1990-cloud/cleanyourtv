#!/usr/bin/env python3
"""Turn tagged candidates into the museum collections the TV shows: art/museums.json.

    python3 -I tools/art_build.py art/museums.json <candidates.json> <sheets.json> <tags.txt> [<candidates> <sheets> <tags> ...]

Each triple is one tagging run: the candidates (tools/art_catalog.py), the sheet numbers (tools/art_sheets.py) and the
tags written while looking at the sheets, one line per painting:

    n|subjects|season|weather|quality|mood       e.g.  27|sea||rain|3|m

subjects: sea, mtn, lake, city, int, life, still, gard, wint, sky, land, anim, mod; season: sp su au wi or empty;
weather: clear cloud rain snow fog night or -; quality 0-3 (0 never shown); mood c (calm), m (moody), b (bright).

Collections hold up to 40 paintings, best first; a subject collection takes at most 5 by one painter. Wide paintings
(aspect 1.45 to 2.1) are cropped to 16:9 by the museum's IIIF server; the rest come whole and the launcher shows them
on a blurred mat. tools/art_index.py then writes art/index.json from this file and any picture folders under art/.
"""
import hashlib
import json
import re
import sys

PER = 40
SEASONS = {"sp": "spring", "su": "summer", "au": "autumn", "wi": "winter"}
HUDSON = ["cole", "church", "bierstadt", "cropsey", "kensett", "gifford", "durand", "moran", "heade", "lane",
          "casilear", "whittredge", "haseltine", "bricher", "richards", "wyant", "hart", "doughty", "inness"]


def collections():
    """(id, name, group, test(p), artist cap) in the order the TV lists them."""
    a = lambda p, *names: any(n in p["artist"].lower() for n in names)
    s = lambda p, *tags: bool(set(tags) & p["subjects"])
    style = lambda p, *names: bool(set(names) & set(p["style"]))
    return [
        ("sea", "Sea and coast", "Subjects", lambda p: s(p, "sea"), 5),
        ("mountains", "Mountains and valleys", "Subjects", lambda p: s(p, "mtn"), 5),
        ("rivers-lakes", "Rivers and lakes", "Subjects", lambda p: s(p, "lake"), 5),
        ("cities", "Cities and streets", "Subjects", lambda p: s(p, "city"), 5),
        ("daily-life", "Interiors and daily life", "Subjects", lambda p: s(p, "int", "life"), 5),
        ("still-life", "Flowers and still life", "Subjects", lambda p: s(p, "still"), 5),
        ("gardens", "Gardens and parks", "Subjects", lambda p: s(p, "gard"), 5),
        ("winter", "Winter", "Subjects", lambda p: s(p, "wint") or p["season"] == "winter" or p["weather"] == "snow", 5),
        ("romanticism", "Romanticism and Hudson River School", "Movements",
         lambda p: style(p, "Romantic") or (a(p, *HUDSON) and p["year"] < 1890)
         or (p["museum"] == "Rijksmuseum" and 1790 <= p["year"] < 1860), 6),
        ("realism", "Realism", "Movements",
         lambda p: style(p, "Realist") or (p["museum"] == "Rijksmuseum" and 1860 <= p["year"] < 1910
                                           and "mod" not in p["subjects"]), 6),
        ("impressionism", "Impressionism", "Movements", lambda p: style(p, "Impressionist"), 6),
        ("modern", "Post-Impressionism and early Modern", "Movements",
         lambda p: style(p, "Post-Impressionist", "German Expressionist") or "mod" in p["subjects"], 6),
        ("monet", "Monet", "Painters", lambda p: a(p, "monet"), 99),
        ("van-gogh", "Van Gogh", "Painters", lambda p: a(p, "gogh"), 99),
        ("turner-constable", "Turner and Constable", "Painters", lambda p: a(p, "turner", "constable"), 99),
        ("sargent-homer", "Sargent and Homer", "Painters", lambda p: a(p, "sargent", "homer"), 99),
    ]


def picture(c):
    w, h = c["width"], c["height"]
    aspect = w / h
    if 1.45 <= aspect <= 2.1:
        cw, ch = (round(h * 16 / 9), h) if aspect > 16 / 9 else (w, round(w * 9 / 16))
        x, y = (w - cw) // 2, (h - ch) // 2
        url, fit = f"{c['iiif']}/{x},{y},{cw},{ch}/1920,1080/0/default.jpg", "fill"
    else:
        url, fit = f"{c['iiif']}/full/!1920,1080/0/default.jpg", "mat"
    artist = re.sub(r"^(Attributed to|Circle of|Follower of|Workshop of|Style of|Imitator of)\s+", "", c["artist"] or "")
    title = c["title"] or "Untitled"
    who = ", ".join(x for x in (artist, str(c["year"]) if c["year"] else "") if x)
    text = f"{title}|{who}"
    if len(text) > 80:
        title = re.sub(r"\s*\(.*?\)", "", title)
        text = f"{title}|{who}"
        if len(text) > 80:
            title = title[: max(10, 80 - len(who) - 2)].rstrip() + "…"
            text = f"{title}|{who}"
    return {"url": url, "fit": fit, "caption": text[:80], "museum": c["museum"]}


def main():
    out, runs = sys.argv[1], sys.argv[2:]
    tagged = []
    for i in range(0, len(runs), 3):
        cands = {c["key"]: c for c in json.load(open(runs[i]))}
        numbers = json.load(open(runs[i + 1]))
        for line in open(runs[i + 2], encoding="utf-8"):
            if line.startswith("#") or "|" not in line:
                continue
            n, subj, season, weather, q, mood = (line.strip().split("|") + [""] * 6)[:6]
            c = cands.get(numbers.get(n))
            if not c or not q.isdigit() or int(q) == 0:
                continue
            # The Rijksmuseum shows images of 20th-century works that may still be under copyright: leave them out.
            if c["museum"] == "Rijksmuseum" and c["year"] > 1905:
                continue
            p = dict(c)
            p.update(subjects=set(t for t in subj.split(",") if t), season=SEASONS.get(season, ""),
                     weather=weather if weather not in ("-", "") else "", quality=int(q), mood=mood)
            tagged.append(p)
    # Best first; among equals a stable shuffle, so one museum or painter does not come first by input order.
    tagged.sort(key=lambda p: (-p["quality"], hashlib.md5(p["key"].encode()).hexdigest()))
    pictures, result = {}, []
    for cid, name, group, test, cap in collections():
        chosen, per_artist = [], {}
        for p in tagged:
            if len(chosen) >= PER:
                break
            if not test(p) or (p["quality"] < 2 and group != "Painters"):
                continue
            artist = p["artist"].lower()
            if per_artist.get(artist, 0) >= cap:
                continue
            per_artist[artist] = per_artist.get(artist, 0) + 1
            chosen.append(p)
        if not chosen:
            continue
        for p in chosen:
            if p["key"] not in pictures:
                pic = picture(p)
                tags = [t for t in (p["season"], p["weather"]) if t in ("spring", "summer", "autumn", "winter",
                                                                       "rain", "snow", "fog", "night")]
                if "wint" in p["subjects"] and "winter" not in tags:
                    tags.append("winter")
                pic["tags"] = tags
                pictures[p["key"]] = pic
        result.append({"id": cid, "name": name, "group": group, "files": [p["key"] for p in chosen]})
        print(f"{name}: {len(chosen)}", file=sys.stderr)
    json.dump({"version": 2, "pictures": pictures, "collections": result}, open(out, "w"), ensure_ascii=False,
              indent=1)
    print(f"{len(pictures)} paintings in {len(result)} collections", file=sys.stderr)


if __name__ == "__main__":
    main()
