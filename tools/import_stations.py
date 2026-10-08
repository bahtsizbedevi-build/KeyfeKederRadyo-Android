"""Grow data/stations.json with verified Turkish stations from Radio Browser.

Usage: python tools/import_stations.py data/stations.json [max_new]

Steps: download Turkish stations, drop duplicates of what we already have, probe every
stream (only working ones are kept), derive genre and city (one of the 81 provinces, or
"Ulusal" for national networks), find a logo, then write the merged list back.
"""
import concurrent.futures as cf
import json
import os
import re
import sys
import unicodedata
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_stations import probe  # noqa: E402
from enrich_stations import find_logo, good_logo  # noqa: E402

RB = "https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/TR?hidebroken=true&order=votes&reverse=true&limit=2000"
UA = "KeyfeKederRadyo/1.0 (+https://github.com/bahtsizbedevi-build/KeyfeKederRadyo-Android)"

PROVINCES = ["Adana", "Adıyaman", "Afyonkarahisar", "Ağrı", "Amasya", "Ankara", "Antalya", "Artvin", "Aydın", "Balıkesir",
    "Bilecik", "Bingöl", "Bitlis", "Bolu", "Burdur", "Bursa", "Çanakkale", "Çankırı", "Çorum", "Denizli", "Diyarbakır",
    "Edirne", "Elazığ", "Erzincan", "Erzurum", "Eskişehir", "Gaziantep", "Giresun", "Gümüşhane", "Hakkari", "Hatay",
    "Isparta", "Mersin", "İstanbul", "İzmir", "Kars", "Kastamonu", "Kayseri", "Kırklareli", "Kırşehir", "Kocaeli",
    "Konya", "Kütahya", "Malatya", "Manisa", "Kahramanmaraş", "Mardin", "Muğla", "Muş", "Nevşehir", "Niğde", "Ordu",
    "Rize", "Sakarya", "Samsun", "Siirt", "Sinop", "Sivas", "Tekirdağ", "Tokat", "Trabzon", "Tunceli", "Şanlıurfa",
    "Uşak", "Van", "Yozgat", "Zonguldak", "Aksaray", "Bayburt", "Karaman", "Kırıkkale", "Batman", "Şırnak", "Bartın",
    "Ardahan", "Iğdır", "Yalova", "Karabük", "Kilis", "Osmaniye", "Düzce"]
ALIASES = {"istambul": "İstanbul", "constantinople": "İstanbul", "smyrna": "İzmir", "antep": "Gaziantep",
           "urfa": "Şanlıurfa", "maras": "Kahramanmaraş", "icel": "Mersin", "adapazari": "Sakarya", "izmit": "Kocaeli",
           "bandirma": "Balıkesir", "alanya": "Antalya", "bodrum": "Muğla", "fethiye": "Muğla", "marmaris": "Muğla",
           "iskenderun": "Hatay", "eregli": "Zonguldak", "corlu": "Tekirdağ", "kusadasi": "Aydın", "didim": "Aydın",
           "odtu": "Ankara", "ankara": "Ankara"}
NATIONAL = ["kral", "power", "joy", "super fm", "süper fm", "virgin", "number one", "number1", "trt", "metro fm",
            "fenomen", "slow türk", "slow turk", "radyo d", "show radyo", "best fm", "radyo fenomen", "pal ", "alem fm",
            "radyo 7", "radyo viva", "kafa radyo", "açık radyo", "acik radyo", "radyo eksen", "dream türk", "radyo 34",
            "ntv radyo", "habertürk", "haberturk", "cnn türk", "lig radyo", "radyo spor", "baba radyo", "damar"]
GENRES = [("Haber", ["haber", "news", "talk"]), ("Spor", ["spor", "sport"]), ("Dini", ["dini", "ilahi", "islam", "kuran", "religious"]),
          ("Türk Halk", ["türkü", "turku", "halk", "folk"]), ("Türk Sanat", ["sanat", "tsm", "türk sanat"]),
          ("Arabesk", ["arabesk", "fantezi", "fantazi", "damar"]), ("Slow", ["slow", "romantic", "love"]),
          ("Rock", ["rock", "metal"]), ("Rap", ["rap", "hip hop", "hiphop"]), ("Jazz", ["jazz", "blues"]),
          ("Klasik", ["classical", "klasik", "opera"]), ("Lounge", ["lounge", "chill", "ambient"]),
          ("Elektronik", ["dance", "electronic", "electro", "house", "techno", "edm", "trance"]),
          ("Nostalji", ["90", "80", "70", "oldies", "nostalji", "retro"]), ("Pop", ["pop", "hit", "top"])]
GENRE_RENAME = {"Classical": "Klasik", "Oldies": "Nostalji", "90'lar": "Nostalji", "Disco": "Elektronik"}


def fold(s):
    s = s.replace("İ", "i").replace("I", "ı").lower().replace("ı", "i")
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


FOLDED = {fold(p): p for p in PROVINCES}
FOLDED.update(ALIASES)


def clean_name(name):
    name = re.sub(r"\s+", " ", name).strip()
    name = re.sub(r"[\s\-–]*(turkey|türkiye|turkiye)$", "", name, flags=re.I).strip()
    return name or "Radyo"


def name_key(name):
    return re.sub(r"[^a-z0-9]", "", fold(clean_name(name)))


def _has(word, text):
    return re.search(r"(^|[^a-z0-9])" + re.escape(word) + r"([^a-z0-9]|$)", text) is not None


def city_for(station):
    """Province named in the station name wins (TRT Trabzon -> Trabzon), then national
    networks, then the Radio Browser state, then a province mentioned in tags/homepage."""
    name = fold(str(station.get("name", "")))
    for key, province in FOLDED.items():
        if len(key) >= 4 and _has(key, name):
            return province
    if any(_has(fold(n).strip(), name) for n in NATIONAL):
        return "Ulusal"
    state = fold(str(station.get("state", "")))
    for key, province in FOLDED.items():
        if key and _has(key, state):
            return province
    rest = fold(" ".join(str(station.get(k, "")) for k in ("tags", "homepage")))
    for key, province in FOLDED.items():
        if len(key) >= 5 and _has(key, rest):
            return province
    return ""


def genre_for(station):
    if station.get("genre") and station["genre"] not in ("Radyo", ""):
        return GENRE_RENAME.get(station["genre"], station["genre"])
    text = fold(" ".join(str(station.get(k, "")) for k in ("name", "tags")))
    for genre, words in GENRES:
        if any(fold(w) in text for w in words):
            return genre
    return "Karışık"


def main():
    path = sys.argv[1]
    max_new = int(sys.argv[2]) if len(sys.argv) > 2 else 400
    existing = json.load(open(path, encoding="utf-8"))
    req = urllib.request.Request(RB, headers={"User-Agent": UA})
    rb = json.loads(urllib.request.urlopen(req, timeout=60).read())

    urls = {(s.get("url_resolved") or s.get("url")).strip().lower() for s in existing}
    keys = {name_key(s["name"]) for s in existing}
    fresh = []
    for s in rb:
        url = (s.get("url_resolved") or s.get("url") or "").strip()
        key = name_key(s.get("name", ""))
        if not url or not key or url.lower() in urls or key in keys:
            continue
        urls.add(url.lower()); keys.add(key)
        fresh.append(s)
    print(f"{len(fresh)} new candidates after de-duplication")

    with cf.ThreadPoolExecutor(max_workers=24) as ex:
        results = list(ex.map(lambda s: probe({"name": s["name"], "url_resolved": s.get("url_resolved") or s.get("url")}), fresh))
    working = [s for s, r in zip(fresh, results) if r["ok"]][:max_new]
    print(f"{len(working)} new stations with a working stream")

    def logo(s):
        fav = (s.get("favicon") or "").strip()
        for cand in ([("https://" + fav[7:]) if fav.startswith("http://") else None, fav] if fav else []):
            if cand and good_logo(cand):
                return cand
        return find_logo({"name": s["name"], "url": s.get("url"), "url_resolved": s.get("url_resolved"), "homepage": s.get("homepage")})

    with cf.ThreadPoolExecutor(max_workers=12) as ex:
        logos = list(ex.map(logo, working))

    added = []
    for s, lg in zip(working, logos):
        bitrate = s.get("bitrate") or 0
        added.append({
            "name": clean_name(s["name"]),
            "url": s.get("url"), "url_resolved": s.get("url_resolved") or s.get("url"),
            "genre": genre_for(s), "language": "Turkish", "country": "Türkiye",
            "quality": f"{bitrate} kbps" if bitrate else "", "bitrate": bitrate, "votes": s.get("votes", 0),
            "song": "Canlı yayın", "codec": s.get("codec", ""), "homepage": s.get("homepage", ""),
            "logo": lg, "city": city_for(s), "tags": s.get("tags", ""),
        })

    for s in existing:
        s["name"] = clean_name(s["name"])
        s["genre"] = genre_for(s)
        s["city"] = city_for(s)

    merged = existing + added
    merged.sort(key=lambda s: fold(s["name"]))
    json.dump(merged, open(path, "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=1)
    cities = {}
    for s in merged:
        cities[s["city"] or "?"] = cities.get(s["city"] or "?", 0) + 1
    print(f"{len(merged)} stations total, {sum(1 for s in merged if s.get('logo'))} with logo")
    print(sorted(cities.items(), key=lambda kv: -kv[1])[:20])


if __name__ == "__main__":
    main()
