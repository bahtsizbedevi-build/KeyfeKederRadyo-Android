"""Probe every stream in a stations.json and report which ones actually deliver audio.

Usage: python tools/check_stations.py stations.json [report.json]

A station counts as OK when the stream answers within the timeout and either
returns audio bytes (Icecast/Shoutcast/HTTP audio) or a playlist (HLS .m3u8,
.pls/.m3u) whose first entry is reachable.
"""
import concurrent.futures as cf
import json
import sys
import time
import urllib.parse
import urllib.request

TIMEOUT = 12
UA = "KeyfeKederRadyo/1.0 (Android; ExoPlayer)"
AUDIO_TYPES = ("audio/", "application/ogg", "video/mp2t", "application/octet-stream")
PLAYLIST_TYPES = ("mpegurl", "x-scpls", "audio/x-mpegurl")


def fetch(url, nbytes=8192):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Icy-MetaData": "1"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        ctype = (r.headers.get("Content-Type") or "").lower()
        body = r.read(nbytes)
        return r.status, ctype, body, r.geturl(), dict(r.headers)


def first_playlist_entry(base, body):
    text = body.decode("utf-8", "ignore")
    for line in text.splitlines():
        line = line.strip()
        if line.lower().startswith("file") and "=" in line:
            line = line.split("=", 1)[1].strip()
        if line and not line.startswith("#") and not line.lower().startswith(("[playlist", "numberofentries", "title", "length", "version")):
            return urllib.parse.urljoin(base, line)
    return None


def probe_icy(url):
    """Shoutcast v1 answers 'ICY 200 OK', which urllib rejects but OkHttp/ExoPlayer accept."""
    import socket
    import ssl
    u = urllib.parse.urlsplit(url)
    port = u.port or (443 if u.scheme == "https" else 80)
    sock = socket.create_connection((u.hostname, port), timeout=TIMEOUT)
    if u.scheme == "https":
        sock = ssl.create_default_context().wrap_socket(sock, server_hostname=u.hostname)
    path = (u.path or "/") + (("?" + u.query) if u.query else "")
    sock.sendall(f"GET {path} HTTP/1.0\r\nHost: {u.hostname}\r\nUser-Agent: {UA}\r\nIcy-MetaData: 1\r\n\r\n".encode())
    data = b""
    while len(data) < 8192:
        chunk = sock.recv(4096)
        if not chunk:
            break
        data += chunk
    sock.close()
    if not data.startswith(b"ICY 200") or len(data) < 2048:
        raise RuntimeError(f"ICY stream gave {len(data)} bytes")


def probe(station):
    url = (station.get("url_resolved") or station.get("url") or "").strip()
    started = time.time()
    result = {"name": station.get("name"), "url": url}
    try:
        status, ctype, body, final, headers = fetch(url)
        result.update(status=status, type=ctype, final=final)
        is_playlist = any(t in ctype for t in PLAYLIST_TYPES) or body[:7] == b"#EXTM3U" or body[:10].lower() == b"[playlist]"
        if is_playlist:
            nxt = first_playlist_entry(final, body)
            result["playlist"] = True
            if not nxt:
                raise RuntimeError("empty playlist")
            s2, t2, b2, f2, _ = fetch(nxt, 4096)
            if s2 >= 400 or not b2:
                raise RuntimeError(f"playlist entry HTTP {s2}")
            result["entry"] = f2
        elif not (ctype.startswith(AUDIO_TYPES) or headers.get("icy-name") or headers.get("icy-br")):
            raise RuntimeError(f"not audio ({ctype or 'no content-type'})")
        elif len(body) < 1024:
            raise RuntimeError(f"only {len(body)} bytes")
        result["ok"] = True
    except Exception as e:  # noqa: BLE001 - report every failure kind
        if "ICY 200" in str(e):
            try:
                probe_icy(url)
                result.update(ok=True, icy=True)
            except Exception as e2:  # noqa: BLE001
                result.update(ok=False, error=str(e2)[:160])
        else:
            result["ok"] = False
            result["error"] = str(e)[:160]
    result["ms"] = int((time.time() - started) * 1000)
    result["https"] = url.startswith("https:")
    return result


def main():
    src = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else None
    stations = json.load(open(src, encoding="utf-8"))
    with cf.ThreadPoolExecutor(max_workers=16) as ex:
        results = list(ex.map(probe, stations))
    ok = [r for r in results if r["ok"]]
    print(f"{len(ok)}/{len(results)} streams OK")
    for r in sorted(results, key=lambda r: (r["ok"], r["name"] or "")):
        if not r["ok"]:
            print(f"  FAIL {r['name']}: {r['error']}  <{r['url']}>")
    if out:
        json.dump(results, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
