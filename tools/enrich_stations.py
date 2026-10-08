"""Build data/stations.json: apply stream fixes, drop dead stations, attach verified logo URLs.

Usage: python tools/enrich_stations.py <source stations.json> <out data/stations.json>

Logo sources, in order: Radio Browser favicon for the same stream URL or name,
then Google's favicon service (PNG, 128px) for the station homepage.
A logo is kept only if it downloads as PNG/JPEG/WebP and is at least 48px.
"""
import concurrent.futures as cf
import json
import struct
import sys
import urllib.parse
import urllib.request

UA = "KeyfeKederRadyo/1.0 (+https://github.com/bahtsizbedevi-build/KeyfeKederRadyo-Android)"
RB = "https://de1.api.radio-browser.info/json"

STREAM_FIXES = {
    "http://radyo.turkuradyo.net:4591/turkuradyo": "https://eu8.fastcast4u.com/proxy/ugur4129?mp=/1",
    "https://moondigitaledge.radyotvonline.net/radyoviva/playlist.m3u8": "https://edge1.radyotvonline.net/shoutcast/play/radyoviva",
}
DEAD = {"https://anadolu.liderhost.com.tr:10929/"}


def get(url, limit=400_000, timeout=10):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(limit)


def image_size(data):
    """Return (w, h) for PNG/JPEG/WebP bytes, else None."""
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", data[16:24])
    if data[:2] == b"\xff\xd8":
        i = 2
        while i < len(data) - 9:
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xC0, 0xC1, 0xC2):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h
            seg = struct.unpack(">H", data[i + 2:i + 4])[0]
            i += 2 + seg
        return None
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        if data[12:16] == b"VP8 ":
            w, h = struct.unpack("<HH", data[26:30])
            return w & 0x3FFF, h & 0x3FFF
        if data[12:16] == b"VP8L":
            b = data[21:25]
            w = 1 + (((b[1] & 0x3F) << 8) | b[0])
            h = 1 + (((b[3] & 0xF) << 10) | (b[2] << 2) | ((b[1] & 0xC0) >> 6))
            return w, h
        if data[12:16] == b"VP8X":
            w = 1 + int.from_bytes(data[24:27], "little")
            h = 1 + int.from_bytes(data[27:30], "little")
            return w, h
    return None


def good_logo(url):
    if not url or not url.startswith("http"):
        return None
    try:
        size = image_size(get(url))
    except Exception:  # noqa: BLE001
        return None
    if size and min(size) >= 48:
        return url
    return None


def rb_candidates(station):
    urls = []
    for stream in {station.get("url_resolved"), station.get("url")}:
        if not stream:
            continue
        try:
            for s in json.loads(get(f"{RB}/stations/byurl?url={urllib.parse.quote(stream, safe='')}")):
                urls.append(s.get("favicon"))
        except Exception:  # noqa: BLE001
            pass
    try:
        q = urllib.parse.quote(station["name"])
        for s in json.loads(get(f"{RB}/stations/byname/{q}?limit=5&order=votes&reverse=true")):
            urls.append(s.get("favicon"))
    except Exception:  # noqa: BLE001
        pass
    return [u for u in dict.fromkeys(urls) if u]


def find_logo(station):
    for url in rb_candidates(station):
        if url.startswith("http://"):
            secure = "https://" + url[7:]
            if good_logo(secure):
                return secure
        if good_logo(url):
            return url
    for host in site_hosts(station):
        for cand in (f"https://{host}/apple-touch-icon.png", f"https://www.{host}/apple-touch-icon.png",
                     f"https://www.google.com/s2/favicons?domain={host}&sz=128"):
            if good_logo(cand):
                return cand
    return ""


# Stream hosts that belong to CDNs/hosting companies, not to the station itself
GENERIC_HOSTS = ("streamtheworld", "radyotvonline", "liderhost", "fastcast4u", "shoutcast", "icecast",
                 "radiohost", "canliradyo", "radyosfer", "zeno", "streamguys", "akamai", "cdn", "playerservices")


def registrable(host):
    parts = host.removeprefix("www.").split(".")
    if len(parts) >= 3 and parts[-2] in ("com", "net", "org", "gen", "web", "bel", "edu", "gov") and len(parts[-1]) == 2:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


# Station name -> official site, where the source data has a stream URL in "homepage" or nothing
SITE_OVERRIDES = {
    "JOY TÜRK TURKEY": "joyturk.com.tr", "JOY TÜRK ROCK TURKEY": "joyturkrock.com.tr",
    "SUPER FM 2 TURKEY": "superfm.com.tr", "Metro fm turkey": "metrofm.com.tr",
    "KRAL POP ALTERNATİF 2": "kralmuzik.com.tr", "Radyo Turkuvaz": "radyoturkuvaz.com.tr",
    "Kafa Radyo": "kafaradyo.com", "Radyo Viva": "radyoviva.com.tr", "Dost": "dostfm.com",
    "Radyo ODTU": "radyoodtu.com.tr", "Akra FM": "akradyo.net", "pop 90 turkey": "superfm.com.tr",
    "HOUSELAND TURKEY": "radyotvonline.com", "DANCELAND TURKEY": "radyotvonline.com",
}


def site_hosts(station):
    hosts = []
    override = SITE_OVERRIDES.get(station.get("name", "").strip()) or SITE_OVERRIDES.get(station.get("name", "").strip().upper())
    if override:
        hosts.append(override)
    home = urllib.parse.urlsplit(station.get("homepage") or "").hostname
    if home:
        hosts.append(home.removeprefix("www."))
    for stream in (station.get("url_resolved"), station.get("url")):
        h = urllib.parse.urlsplit(stream or "").hostname
        if h and not h.replace(".", "").isdigit() and not any(g in h for g in GENERIC_HOSTS):
            hosts.append(registrable(h))
    return list(dict.fromkeys(hosts))


def main():
    src, out = sys.argv[1], sys.argv[2]
    stations = json.load(open(src, encoding="utf-8"))
    kept = []
    for s in stations:
        url = (s.get("url_resolved") or s.get("url") or "").strip()
        if url in DEAD:
            continue
        if url in STREAM_FIXES:
            s["url"] = s["url_resolved"] = STREAM_FIXES[url]
        kept.append(s)
    with cf.ThreadPoolExecutor(max_workers=8) as ex:
        logos = list(ex.map(find_logo, kept))
    for s, logo in zip(kept, logos):
        s["logo"] = logo
    json.dump(kept, open(out, "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=1)
    print(f"{len(kept)} stations, {sum(1 for l in logos if l)} with logo -> {out}")


if __name__ == "__main__":
    main()
