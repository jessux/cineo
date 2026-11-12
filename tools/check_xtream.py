#!/usr/bin/env python3
"""Vérifie des comptes Xtream Codes.

Usage :
  python3 check_xtream.py "http://host:port" USER PASS
  python3 check_xtream.py "http://host:port/get.php?username=U&password=P"
  python3 check_xtream.py -f comptes.txt        # une ligne par compte
  python3 check_xtream.py -f comptes.txt --csv resultats.csv

Format du fichier (une ligne par compte, séparateurs , ou espace, ou lien get.php) :
  http://host:port  USER  PASS
"""
import sys, json, csv, argparse, urllib.parse, urllib.request, datetime, re
from concurrent.futures import ThreadPoolExecutor

UA = "VLC/3.0.20 LibVLC/3.0.20"
TIMEOUT = 20


def norm(server):
    s = server.strip().split("/player_api.php")[0].split("/get.php")[0].rstrip("/")
    if not s.startswith("http"):
        s = "http://" + s
    return s


def parse_line(line):
    line = line.strip()
    if not line or line.startswith("#"):
        return None
    u = re.search(r"username=([^&\s]+)", line)
    p = re.search(r"password=([^&\s]+)", line)
    if u and p:
        return norm(line), u.group(1), p.group(1)
    parts = re.split(r"[,\s]+", line)
    if len(parts) >= 3:
        return norm(parts[0]), parts[1], parts[2]
    return None


def api(server, user, pwd, action=None):
    q = {"username": user, "password": pwd}
    if action:
        q["action"] = action
    url = f"{server}/player_api.php?" + urllib.parse.urlencode(q)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


FR = re.compile(r"\b(fr|france|français|francais|french|vf|vff|fr\|)\b|🇫🇷|^fr |fr -|\bqc\b|québec|quebec", re.I)

# Motifs appliqués au nom des catégories Xtream (sans tenir compte de la casse)
PLATFORMS = {
    "netflix": ("Netflix", re.compile(r"netflix|\bnf\b", re.I)),
    "disney": ("Disney+", re.compile(r"disney", re.I)),
    "apple": ("Apple TV+", re.compile(r"apple|\batv\b", re.I)),
    "amazon": ("Amazon Prime", re.compile(r"amazon|prime\s*video|\bprime\b", re.I)),
    "canal": ("Canal+", re.compile(r"canal", re.I)),
    "hbo": ("HBO Max", re.compile(r"\bhbo\b", re.I)),
    "paramount": ("Paramount+", re.compile(r"paramount", re.I)),
    "ocs": ("OCS", re.compile(r"\bocs\b", re.I)),
    "crunchyroll": ("Crunchyroll", re.compile(r"crunchyroll", re.I)),
}


def fetch(server, user, pwd, stream_action, cat_action):
    """Retourne (flux, catégories), ou None en cas d'erreur."""
    try:
        cats = api(server, user, pwd, cat_action)
        streams = api(server, user, pwd, stream_action)
        if not isinstance(streams, list) or not isinstance(cats, list):
            return [], []
        return streams, cats
    except Exception:
        return None


def tally(data, pattern=None):
    """Nombre de flux ; si pattern est fourni, seulement ceux dont la catégorie correspond."""
    if data is None:
        return -1
    streams, cats = data
    if pattern is None:
        return len(streams)
    ids = {str(c.get("category_id")) for c in cats if isinstance(c, dict) and pattern.search(c.get("category_name", ""))}
    return sum(1 for x in streams if str(x.get("category_id")) in ids)


def check(acc):
    server, user, pwd = acc
    row = {"server": server, "user": user, "pass": pwd, "status": "?",
           "expire": "", "trial": "", "cons": "", "max_cons": "", "active_cons": "",
           "films": "", "films_fr": "", "series": "", "series_fr": "",
           "live": "", "live_fr": "", "note": ""}
    for key in PLATFORMS:
        for t in ("films", "series", "live"):
            row[f"{key}_{t}"] = ""
    try:
        info = api(server, user, pwd).get("user_info", {})
    except Exception as e:
        row["status"] = "INJOIGNABLE"
        row["note"] = str(e)[:80]
        return row
    if info.get("auth") != 1:
        row["status"] = "REFUSÉ"
        return row
    st = info.get("status", "")
    exp = info.get("exp_date")
    row["status"] = "ACTIF" if st == "Active" else (st or "ACTIF")
    if exp and str(exp).isdigit():
        d = datetime.datetime.fromtimestamp(int(exp))
        left = (d - datetime.datetime.now()).days
        row["expire"] = d.strftime("%d/%m/%Y") + f" ({left}j)"
        if left < 0:
            row["status"] = "EXPIRÉ"
    elif not exp:
        row["expire"] = "illimité"
    row["trial"] = "essai" if str(info.get("is_trial")) == "1" else ""
    row["cons"] = f'{info.get("active_cons","?")}/{info.get("max_connections","?")}'
    row["max_cons"] = info.get("max_connections", "?")
    row["active_cons"] = info.get("active_cons", "?")
    data = {
        "films": fetch(server, user, pwd, "get_vod_streams", "get_vod_categories"),
        "series": fetch(server, user, pwd, "get_series", "get_series_categories"),
        "live": fetch(server, user, pwd, "get_live_streams", "get_live_categories"),
    }
    for t, d in data.items():
        row[t] = tally(d)
        row[f"{t}_fr"] = tally(d, FR)
        for key, (_, pat) in PLATFORMS.items():
            row[f"{key}_{t}"] = tally(d, pat)
    return row


def main():
    ap = argparse.ArgumentParser(description="Vérifie des comptes Xtream Codes")
    ap.add_argument("args", nargs="*", help='"serveur" USER PASS  ou  un lien get.php')
    ap.add_argument("-f", "--file", help="fichier, une ligne par compte")
    ap.add_argument("--csv", help="écrit le résultat dans ce fichier CSV")
    ap.add_argument("-j", "--jobs", type=int, default=8, help="comptes vérifiés en parallèle")
    a = ap.parse_args()

    accounts = []
    if a.file:
        with open(a.file, encoding="utf-8") as f:
            accounts = [p for p in (parse_line(l) for l in f) if p]
    elif len(a.args) == 1:
        p = parse_line(a.args[0])
        if p:
            accounts = [p]
    elif len(a.args) >= 3:
        accounts = [(norm(a.args[0]), a.args[1], a.args[2])]
    if not accounts:
        ap.print_help()
        sys.exit(1)

    rows = list(ThreadPoolExecutor(max_workers=a.jobs).map(check, accounts))

    ok = {"ACTIF", "Active"}
    for r in rows:
        mark = "✅" if r["status"] in ok else "❌"
        head = f'{mark} {r["status"]:<11} {r["server"]}  {r["user"]}/{r["pass"]}'
        print(head)
        if r["note"]:
            print(f'     {r["note"]}')
        elif r["status"] not in ("INJOIGNABLE", "REFUSÉ"):
            print(f'     Expiration : {r["expire"] or "?"} {r["trial"]}')
            print(f'     Connexions simultanées : {r["active_cons"]} en cours / {r["max_cons"]} autorisée(s)')
            print(f'     Films   : {r["films"]:>6} (FR {r["films_fr"]})')
            print(f'     Séries  : {r["series"]:>6} (FR {r["series_fr"]})')
            print(f'     Chaînes : {r["live"]:>6} (FR {r["live_fr"]})')
            plats = [(label, r[f"{key}_films"], r[f"{key}_series"], r[f"{key}_live"])
                     for key, (label, _) in PLATFORMS.items()
                     if any(isinstance(r[f"{key}_{t}"], int) and r[f"{key}_{t}"] > 0 for t in ("films", "series", "live"))]
            if plats:
                print("     Plateformes :")
                for label, f_, s_, c_ in plats:
                    print(f'       {label:<13} films {f_:>6} | séries {s_:>6} | chaînes {c_:>6}')
    good = sum(1 for r in rows if r["status"] in ok)
    print(f'\n{good}/{len(rows)} compte(s) actif(s)')

    if a.csv:
        with open(a.csv, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
        print(f"CSV écrit : {a.csv}")


if __name__ == "__main__":
    main()
