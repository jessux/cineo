# -*- coding: utf-8 -*-
"""Cinéo pour Kodi : client Xtream Codes / M3U (films, séries, TV en direct, replay)."""
import base64, json, re, sys, time, urllib.parse, urllib.request
import xbmcaddon, xbmcgui, xbmcplugin

ADDON = xbmcaddon.Addon()
URL, HANDLE = sys.argv[0], int(sys.argv[1])
ARGS = dict(urllib.parse.parse_qsl(sys.argv[2][1:]))
UA = "VLC/3.0.20 LibVLC/3.0.20"
KINDS = {  # kind: (catégories, flux, libellé)
    "movie": ("get_vod_categories", "get_vod_streams", "Films"),
    "series": ("get_series_categories", "get_series", "Séries"),
    "live": ("get_live_categories", "get_live_streams", "TV en direct"),
}


def normalize(s):
    u = s.strip().split("/player_api.php")[0].split("/get.php")[0].rstrip("/")
    return u if u.startswith("http") else "http://" + u


M3U = ADDON.getSettingBool("m3u")
SERVER = normalize(ADDON.getSetting("server")) if ADDON.getSetting("server") else ""
USER, PASS = ADDON.getSetting("user"), ADDON.getSetting("pass")


def fetch(u):
    req = urllib.request.Request(u, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace").strip()


def api(action=None, **extra):
    q = {"username": USER, "password": PASS}
    if action:
        q["action"] = action
    q.update(extra)
    try:
        return json.loads(fetch(SERVER + "/player_api.php?" + urllib.parse.urlencode(q)))
    except Exception as e:
        xbmcgui.Dialog().notification("Cinéo", "Erreur : %s" % e, xbmcgui.NOTIFICATION_ERROR)
        return None


def m3u_items():
    out, inf = [], ""
    for l in fetch(SERVER).splitlines():
        l = l.strip()
        if l.startswith("#EXTINF"):
            inf = l
        elif l and not l.startswith("#") and inf:
            attr = lambda k: (re.search(k + r'="([^"]*)"', inf) or [None, ""])[1]
            ext = l.rsplit(".", 1)[-1].split("?")[0].lower() if "." in l else ""
            vod = "/movie/" in l or "/series/" in l or ext in ("mp4", "mkv", "avi", "mov", "webm")
            out.append({"kind": "movie" if vod else "live", "name": inf.rsplit(",", 1)[-1].strip(),
                        "logo": attr("tvg-logo"), "group": attr("group-title") or "Autres", "url": l})
            inf = ""
    return out


def b64(s):
    try:
        return base64.b64decode(s).decode("utf-8")
    except Exception:
        return s or ""


def link(**q):
    return URL + "?" + urllib.parse.urlencode(q)


def add(label, query=None, url=None, img=None, plot="", folder=True, fanart=None):
    li = xbmcgui.ListItem(label)
    art = {"thumb": img, "icon": img, "poster": img}
    if fanart:
        art["fanart"] = fanart
    li.setArt({k: v for k, v in art.items() if v})
    li.getVideoInfoTag().setPlot(plot or "")
    if not folder:
        li.setProperty("IsPlayable", "true")
    xbmcplugin.addDirectoryItem(HANDLE, url or link(**query), li, folder)


def end(content="videos"):
    xbmcplugin.setContent(HANDLE, content)
    xbmcplugin.endOfDirectory(HANDLE)


def stream_url(kind, sid, ext):
    path = {"movie": "movie", "live": "live", "episode": "series"}[kind]
    return "%s/%s/%s/%s/%s.%s" % (SERVER, path, USER, PASS, sid, ext)


# ---------- Écrans ----------
def root():
    if not SERVER:
        ADDON.openSettings()
        xbmcplugin.endOfDirectory(HANDLE, succeeded=False)
        return
    kinds = ("movie", "live") if M3U else ("movie", "series", "live")
    for k in kinds:
        add(KINDS[k][2], {"m": "cats", "k": k})
    add("Rechercher", {"m": "search"})
    end("files")


def cats(k):
    if M3U:
        groups = sorted({i["group"] for i in m3u_items() if i["kind"] == k})
        for g in groups:
            add(g, {"m": "list", "k": k, "c": g})
    else:
        add("Tout", {"m": "list", "k": k, "c": ""})
        for c in api(KINDS[k][0]) or []:
            add(c.get("category_name", ""), {"m": "list", "k": k, "c": c.get("category_id", "")})
    end("files")


def items(k, cat=None, query=None):
    """Liste les flux d'un type, filtrés par catégorie ou par recherche."""
    if M3U:
        res = [i for i in m3u_items() if i["kind"] == k]
        res = [i for i in res if (cat is None or i["group"] == cat) and (query is None or query in i["name"].lower())]
        for i in res:
            add(i["name"], url=play_link(i["url"]), img=i["logo"], folder=False)
        return
    extra = {"category_id": cat} if cat else {}
    for s in api(KINDS[k][1], **extra) or []:
        name = s.get("name") or ""
        if query is not None and query not in name.lower():
            continue
        if k == "series":
            add(name, {"m": "seasons", "id": s.get("series_id")}, img=s.get("cover"), plot=s.get("plot") or "")
        else:
            ext = "ts" if k == "live" else (s.get("container_extension") or "mp4")
            img = s.get("stream_icon")
            add(name, url=play_link(stream_url(k, s.get("stream_id"), ext)), img=img, folder=False)
            if k == "live" and s.get("tv_archive") in (1, "1"):
                add("   ↺ Replay : " + name, {"m": "replay", "id": s.get("stream_id")}, img=img)


def play_link(u):
    return link(m="play", u=u)


def listing(k, c):
    items(k, cat=c or None)
    end("movies" if k == "movie" else "tvshows" if k == "series" else "videos")


def seasons(sid):
    o = api("get_series_info", series_id=sid) or {}
    info, eps = o.get("info") or {}, o.get("episodes") or {}
    if isinstance(eps, list):
        eps = {str(n + 1): e for n, e in enumerate(eps)}
    fan = info.get("backdrop_path")
    fan = fan[0] if isinstance(fan, list) and fan else fan if isinstance(fan, str) else None
    for s in sorted(eps, key=lambda x: int(x) if str(x).isdigit() else 0):
        for e in sorted(eps[s] or [], key=lambda e: int(e.get("episode_num") or 0)):
            ei = e.get("info") or {}
            if isinstance(ei, list):
                ei = {}
            label = "S%02dE%02d · %s" % (int(s) if str(s).isdigit() else 0, int(e.get("episode_num") or 0),
                                          e.get("title") or "")
            u = stream_url("episode", e.get("id"), e.get("container_extension") or "mp4")
            add(label, url=play_link(u), img=ei.get("movie_image") or info.get("cover"),
                plot=ei.get("plot") or info.get("plot") or "", folder=False, fanart=fan)
    end("episodes")


def replay(sid):
    o = api("get_simple_data_table", stream_id=sid) or {}
    now = time.time()
    for p in sorted(o.get("epg_listings") or [], key=lambda p: int(p.get("start_timestamp") or 0), reverse=True):
        start, stop = int(p.get("start_timestamp") or 0), int(p.get("stop_timestamp") or 0)
        if str(p.get("has_archive")) != "1" or start > now:
            continue
        st = p.get("start") or ""
        d, t = st[:10], st[11:16].replace(":", "-")
        u = "%s/timeshift/%s/%s/%d/%s:%s/%s.ts" % (SERVER, USER, PASS, (stop - start) // 60, d, t, sid)
        add("%s  %s" % (st[5:16], b64(p.get("title"))), url=play_link(u), plot=b64(p.get("description")), folder=False)
    end("videos")


def search():
    q = xbmcgui.Dialog().input("Rechercher")
    if not q:
        return
    q = q.lower()
    for k in (("movie", "live") if M3U else ("movie", "series", "live")):
        items(k, query=q)
    end("videos")


def play(u):
    li = xbmcgui.ListItem(path=u + "|User-Agent=" + urllib.parse.quote(UA))
    if u.endswith(".ts") or ".m3u8" in u:
        li.setMimeType("video/mp2t" if u.endswith(".ts") else "application/vnd.apple.mpegurl")
        li.setContentLookup(False)
    xbmcplugin.setResolvedUrl(HANDLE, True, li)


m = ARGS.get("m")
if m == "cats":
    cats(ARGS["k"])
elif m == "list":
    listing(ARGS["k"], ARGS.get("c", ""))
elif m == "seasons":
    seasons(ARGS["id"])
elif m == "replay":
    replay(ARGS["id"])
elif m == "search":
    search()
elif m == "play":
    play(ARGS["u"])
else:
    root()
