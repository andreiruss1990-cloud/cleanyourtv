#!/usr/bin/env python3
"""Lay candidate paintings out on numbered contact sheets, for tagging them by eye.

    tools/venv/bin/python -I tools/art_sheets.py <candidates.json> <out dir> [--per 25]

Writes sheet-001.png, sheet-002.png ... (5 x 5 thumbnails, each numbered) and sheets.json, which maps every number
to its candidate key. Thumbnails come from each museum's IIIF server at 300 pixels wide. Needs Pillow
(tools/requirements-photos.txt).
"""
import argparse
import io
import json
import os
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from PIL import Image, ImageDraw, ImageFont

UA = {"User-Agent": "art-collections/1.0 (personal TV wallpaper list)"}
CELL_W, CELL_H, COLS = 320, 220, 5


def thumb(c, cache):
    path = os.path.join(cache, c["key"] + ".jpg")
    if not os.path.exists(path):
        url = f"{c['iiif']}/full/300,/0/default.jpg"
        for _ in range(3):
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                    data = r.read()
                with open(path, "wb") as f:
                    f.write(data)
                break
            except Exception:
                continue
    try:
        return Image.open(path).convert("RGB")
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("candidates")
    ap.add_argument("out")
    ap.add_argument("--per", type=int, default=25)
    args = ap.parse_args()
    cands = json.load(open(args.candidates))
    os.makedirs(args.out, exist_ok=True)
    cache = os.path.join(args.out, "thumbs")
    os.makedirs(cache, exist_ok=True)
    with ThreadPoolExecutor(6) as pool:
        images = list(pool.map(lambda c: thumb(c, cache), cands))
    try:
        font = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Bold.ttf", 26)
    except Exception:
        font = ImageFont.load_default()
    index = {}
    rows = (args.per + COLS - 1) // COLS
    for s in range(0, len(cands), args.per):
        sheet = Image.new("RGB", (CELL_W * COLS, CELL_H * rows), (24, 24, 26))
        draw = ImageDraw.Draw(sheet)
        for i, (c, img) in enumerate(zip(cands[s:s + args.per], images[s:s + args.per])):
            n = s + i + 1
            index[n] = c["key"]
            x, y = (i % COLS) * CELL_W, (i // COLS) * CELL_H
            if img is not None:
                img.thumbnail((CELL_W - 10, CELL_H - 10))
                sheet.paste(img, (x + (CELL_W - img.width) // 2, y + (CELL_H - img.height) // 2))
            draw.rectangle([x + 4, y + 4, x + 62, y + 36], fill=(0, 0, 0))
            draw.text((x + 8, y + 6), str(n), fill=(255, 255, 255), font=font)
        sheet.save(os.path.join(args.out, f"sheet-{s // args.per + 1:03d}.png"))
    json.dump(index, open(os.path.join(args.out, "sheets.json"), "w"), indent=0)
    print(f"{(len(cands) + args.per - 1) // args.per} sheets, {len(cands)} paintings")


if __name__ == "__main__":
    main()
