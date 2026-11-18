"""Datapack de test « Fakemon » : une espece verifax (copie des statistiques de Magicarpe) et son fichier de langue,
dans un zip du dossier datapacks du monde, comme un pack de Fakemons installe seulement sur le serveur.
Le client de test ne l'a pas : il doit quand meme afficher « Vérifax » et non la cle de traduction."""
import glob, io, json, os, re, zipfile


def make(srv):
    dst = os.path.join(srv, "world", "datapacks", "cpu-fakemon-verifax.zip")
    if os.path.exists(dst):
        return "deja present"
    jars = [j for j in glob.glob(os.path.join(srv, "mods", "*.jar")) if re.match(r"cobblemon-fabric", os.path.basename(j), re.I)]
    if not jars:
        return "jar Cobblemon introuvable"
    with zipfile.ZipFile(jars[0]) as z:
        names = [n for n in z.namelist() if re.search(r"^data/cobblemon/species/.*/magikarp\.json$", n)]
        if not names:
            return "magikarp.json introuvable"
        sp = json.loads(z.read(names[0]).decode("utf-8"))
    sp["name"] = "Verifax"
    sp["nationalPokedexNumber"] = 9901
    sp["implemented"] = True
    for k in ("evolutions", "preEvolution", "forms"):
        sp.pop(k, None)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps({"pack": {"pack_format": 48, "description": "CobblePulse CI Fakemon"}}))
        z.writestr("data/cobblemon/species/custom/verifax.json", json.dumps(sp, indent=2))
        z.writestr("assets/cobblemon/lang/fr_fr.json", json.dumps({"cobblemon.species.verifax.name": "Vérifax"}, ensure_ascii=False))
        z.writestr("assets/cobblemon/lang/en_us.json", json.dumps({"cobblemon.species.verifax.name": "Verifax"}))
    open(dst, "wb").write(buf.getvalue())
    return "cree (%s)" % names[0]
