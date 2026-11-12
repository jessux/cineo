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


def count(server, user, pwd, action):
    try:
        d = api(server, user, pwd, action)
        return len(d) if isinstance(d, list) else 0
    except Exception:
        return -1


def check(acc):
    server, user, pwd = acc
    row = {"server": server, "user": user, "pass": pwd, "status": "?",
           "expire": "", "trial": "", "cons": "", "films": "", "series": "", "live": "", "note": ""}
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
    row["films"] = count(server, user, pwd, "get_vod_streams")
    row["series"] = count(server, user, pwd, "get_series")
    row["live"] = count(server, user, pwd, "get_live_streams")
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
            print(f'     exp {r["expire"] or "?"} {r["trial"]} · connexions {r["cons"]} · '
                  f'films {r["films"]} séries {r["series"]} direct {r["live"]}')
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
