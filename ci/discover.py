#!/usr/bin/env python3
"""CI : versions Modrinth des dependances (tests serveur/client) + jars des mods optionnels
(CobbleDollars, Impactor, Mega Showdown, mods CobblePulse) pour la compilation locale et les tests."""
import json, os, subprocess, sys, urllib.request, zipfile

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/discover"
JARS = os.path.join(OUT, "jars")
os.makedirs(JARS, exist_ok=True)
UA = {"User-Agent": "cobblepulse-utils-ci/1.0 (github actions)"}


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


lines = []
SLUGS = ["cobblemon", "fabric-api", "fabric-language-kotlin", "luckperms", "cobbledollars", "impactor",
         "cobblemon-mega-showdown", "architectury-api", "placeholder-api"]
modrinth = {}
for slug in SLUGS:
    try:
        vs = json.loads(get(f"https://api.modrinth.com/v2/project/{slug}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%221.21.1%22%5D"))
        modrinth[slug] = vs
        lines.append("== modrinth " + slug)
        lines.extend(f'{v["version_number"]} | {v["date_published"]} | deps={[d.get("project_id") for d in v.get("dependencies", []) if d.get("dependency_type") == "required"]}' for v in vs[:5])
    except Exception as e:
        lines.append(f"== modrinth {slug} ERR {e}")

# Dependances requises (non listees) des mods optionnels
extra = {}
for slug in ("cobbledollars", "impactor", "cobblemon-mega-showdown"):
    for v in (modrinth.get(slug) or [])[:1]:
        for d in v.get("dependencies", []):
            if d.get("dependency_type") != "required" or not d.get("project_id"):
                continue
            try:
                proj = json.loads(get(f"https://api.modrinth.com/v2/project/{d['project_id']}"))
                s = proj["slug"]
                if s in modrinth or s in extra:
                    continue
                extra[s] = json.loads(get(f"https://api.modrinth.com/v2/project/{s}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%221.21.1%22%5D"))
                lines.append(f"== dep of {slug}: {s} -> {[x['version_number'] for x in extra[s][:3]]}")
            except Exception as e:
                lines.append(f"== dep {d} ERR {e}")
modrinth.update(extra)

with open(os.path.join(OUT, "modrinth.json"), "w") as fh:
    json.dump({k: [{"version_number": v["version_number"], "id": v["id"], "files": v["files"],
                    "dependencies": v.get("dependencies", [])} for v in vs[:10]] for k, vs in modrinth.items()}, fh, indent=1)


def pick(slug, want=None):
    for v in modrinth.get(slug) or []:
        if want is None or want in v["version_number"]:
            return v
    return None


def download(slug, name, want=None):
    v = pick(slug, want)
    if not v:
        lines.append(f"!! pas de version pour {slug}")
        return None
    f = [f for f in v["files"] if f["primary"]] or v["files"]
    dst = os.path.join(JARS, name)
    open(dst, "wb").write(get(f[0]["url"]))
    lines.append(f"jar {slug} {v['version_number']} -> {name}")
    return dst


for slug, name, want in (("cobbledollars", "cobbledollars.jar", None), ("impactor", "impactor.jar", None),
                         ("cobblemon-mega-showdown", "megashowdown.jar", "1.8.4")):
    try:
        p = download(slug, name, want)
        if p is None and want:
            download(slug, name, None)
    except Exception as e:
        lines.append(f"!! {slug} {e}")

# Mods CobblePulse (branche ci-output de chaque depot)
for repo, jar in (("cobblepulse-battletower", "cobblepulse-battletower-1.0.0.jar"), ("cobblepulse-crates", "cobblepulse-crates-1.0.0.jar"),
                  ("cobblepulse-battlepass", "cobblepulse-battlepass-1.0.0.jar"), ("cobblepulse", "cobblepulse-1.0.0.jar")):
    try:
        data = get(f"https://raw.githubusercontent.com/Spacer3154/{repo}/ci-output/{jar}")
        open(os.path.join(JARS, repo + ".jar"), "wb").write(data)
        lines.append(f"jar {repo} {len(data)} octets")
    except Exception as e:
        lines.append(f"!! {repo} {e}")

# Liste des classes des mods optionnels (aide a l'integration par reflexion)
for name in ("cobbledollars.jar", "impactor.jar", "megashowdown.jar"):
    p = os.path.join(JARS, name)
    if not os.path.exists(p):
        continue
    try:
        with zipfile.ZipFile(p) as z:
            names = z.namelist()
            inner = [n for n in names if n.startswith("META-INF/jars/") and n.endswith(".jar")]
            with open(os.path.join(OUT, name + ".classes.txt"), "w") as fh:
                fh.write("\n".join(n for n in names if n.endswith(".class") or n.endswith(".json") and "/" not in n[:-5]) + "\n")
                fh.write("\n# inner jars\n" + "\n".join(inner) + "\n")
            for n in names:
                if n == "fabric.mod.json":
                    open(os.path.join(OUT, name + ".fabric.mod.json"), "w").write(z.read(n).decode("utf-8", "replace"))
            for ij in inner:
                if "api" in ij.lower() or "impactor" in ij.lower():
                    open(os.path.join(JARS, os.path.basename(ij)), "wb").write(z.read(ij))
                    lines.append(f"inner jar {ij}")
    except Exception as e:
        lines.append(f"!! listing {name} {e}")

with open(os.path.join(OUT, "versions.txt"), "w") as fh:
    fh.write("\n".join(lines) + "\n")
print("\n".join(lines))
print("discover done")
