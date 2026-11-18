#!/usr/bin/env python3
"""CI : demarre un vrai serveur Fabric 1.21.1 (Cobblemon 1.8.4 + LuckPerms + CobbleDollars + mods CobblePulse + Utils),
lance les auto-tests en SQLite puis MySQL, avec redemarrage pour verifier la persistance."""
import glob, json, os, queue, re, shutil, subprocess, sys, threading, time, urllib.request

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "out")
DISC = os.path.join(OUT, "discover")
SRV = os.path.abspath("run-server")
UA = {"User-Agent": "cobblepulse-utils-ci/1.0 (github actions)"}
props = dict(l.strip().split("=", 1) for l in open("gradle.properties") if "=" in l and not l.startswith("#"))


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def log(msg):
    print(msg, flush=True)
    with open(os.path.join(OUT, "smoke-steps.txt"), "a") as fh:
        fh.write(msg + "\n")


os.makedirs(os.path.join(SRV, "mods"), exist_ok=True)
modrinth = json.load(open(os.path.join(DISC, "modrinth.json")))
wanted = {"fabric-api": None, "fabric-language-kotlin": None, "luckperms": None, "cobblemon": "1.8.4", "cobbledollars": None}
if os.environ.get("CPU_EXTRA_MODS"):
    for s in os.environ["CPU_EXTRA_MODS"].split(","):
        wanted[s] = None
for slug, ver in wanted.items():
    pick = None
    for v in modrinth.get(slug) or []:
        if ver is None or ver in v["version_number"]:
            pick = v
            break
    if not pick:
        log(f"!! missing {slug}")
        continue
    f = [f for f in pick["files"] if f["primary"]] or pick["files"]
    open(os.path.join(SRV, "mods", f[0]["filename"]), "wb").write(get(f[0]["url"]))
    log(f"mod {slug} {pick['version_number']} -> {f[0]['filename']}")
# Mods CobblePulse voisins (passerelles, BP de la Tour, fusion de /cp avec le mod Clans)
for name in ("cobblepulse.jar", "cobblepulse-battletower.jar", "cobblepulse-crates.jar"):
    src = os.path.join(DISC, "jars", name)
    if os.path.exists(src) and os.path.getsize(src) > 100000:
        shutil.copy(src, os.path.join(SRV, "mods", name))
        log("mod " + name)
# autres mods du serveur (CobblePulse-Ecology : Fakemons et Mega-Gemmes declarees dans Mega Showdown)
for j in glob.glob("ci/mods/*.jar"):
    shutil.copy(j, os.path.join(SRV, "mods"))
    log("mod " + os.path.basename(j))
for j in [j for j in glob.glob("build/libs/*.jar") if not j.endswith("-sources.jar")]:
    shutil.copy(j, os.path.join(SRV, "mods"))
    log("mod utils " + j)

installer = json.loads(get("https://meta.fabricmc.net/v2/versions/installer"))[0]["version"]
loader = props.get("loader_version")
open(os.path.join(SRV, "server.jar"), "wb").write(get(f"https://meta.fabricmc.net/v2/versions/loader/1.21.1/{loader}/{installer}/server/jar"))
open(os.path.join(SRV, "eula.txt"), "w").write("eula=true\n")
open(os.path.join(SRV, "server.properties"), "w").write(
    "online-mode=false\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\nmax-tick-time=-1\nlevel-seed=cobblepulse\nsync-chunk-writes=false\n")


def run_server(label, commands, wait_token=None, wait_timeout=0):
    log("===== run %s =====" % label)
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import fakemon
    log("datapack Fakemon : " + fakemon.make(SRV))
    proc = subprocess.Popen(["java", "-Xmx5G", "-Dcobblepulse_utils.selftest=true", "-jar", "server.jar", "nogui"], cwd=SRV,
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()
    logf = open(os.path.join(OUT, "server-%s.log" % label), "w")

    def reader():
        for line in proc.stdout:
            logf.write(line)
            logf.flush()
            lines.put(line)
        lines.put(None)

    threading.Thread(target=reader, daemon=True).start()

    def wait_for(token, timeout):
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                return False
            if token in line:
                return True
        return False

    ok = wait_for("Done (", 1200)
    log("server ready: %s" % ok)
    if ok:
        for c in commands:
            if c.startswith("sleep "):
                time.sleep(float(c.split()[1]))
                continue
            if c.startswith("wait "):
                tok = c[5:]
                log("waiting for: " + tok + " -> " + str(wait_for(tok, 600)))
                continue
            log("> " + c)
            proc.stdin.write(c + "\n")
            proc.stdin.flush()
            time.sleep(2)
        time.sleep(5)
    try:
        proc.stdin.write("stop\n")
        proc.stdin.flush()
    except Exception:
        pass
    try:
        proc.wait(timeout=180)
    except subprocess.TimeoutExpired:
        proc.kill()
    logf.close()
    for l in open(os.path.join(OUT, "server-%s.log" % label), errors="replace"):
        if "SELFTEST SUMMARY" in l or "] FAIL " in l or "Exception" in l and "cobblepulse" in l.lower():
            log("[%s] %s" % (label, l.strip()[:400]))
    log("exit code %s" % proc.returncode)


FULL = ["sleep 5", "cp utilstest", "wait SELFTEST DONE", "cp utilstest report", "list", "cp reload"]
CHECK = ["sleep 5", "cp utilstest persisted", "wait SELFTEST DONE", "cp utilstest report"]

cfg_dir = os.path.join(SRV, "config", "cobblepulse")
run_server("sqlite-1", FULL)
run_server("sqlite-2", CHECK)
shutil.copytree(cfg_dir, os.path.join(OUT, "server-config-sqlite"), dirs_exist_ok=True,
                ignore=shutil.ignore_patterns("*.db", "*.db-wal", "*.db-shm"))

if os.environ.get("CPU_MYSQL") == "1" and not os.path.exists("ci/SQLITE_ONLY"):
    db = os.path.join(cfg_dir, "database.json")
    txt = open(db).read()
    txt = txt.replace('"storage_type": "SQLITE"', '"storage_type": "MYSQL"').replace('"host": "localhost"', '"host": "127.0.0.1"') \
        .replace('"password": "password"', '"password": "cputils"')
    open(db, "w").write(txt)
    # repartir d'un monde neuf pour que les donnees viennent bien de MySQL
    shutil.rmtree(os.path.join(SRV, "world"), ignore_errors=True)
    shutil.rmtree(os.path.join(cfg_dir, "utils", "data"), ignore_errors=True)
    run_server("mysql-1", FULL)
    run_server("mysql-2", CHECK)

for crash in glob.glob(os.path.join(SRV, "crash-reports", "*")):
    shutil.copy(crash, OUT)
