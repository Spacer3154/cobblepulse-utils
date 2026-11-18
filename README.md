# CobblePulse Utils

Mod central (« Core ») de l'écosystème CobblePulse pour **Minecraft 1.21.1 / Fabric / Cobblemon 1.8.4**.

Hub, Wiki Cobblemon 3D, téléportation (warps, homes, TPA, RTP), Shop Admin, GTS, STS, Échange Miracle, kits,
Enderchest évolutif, Dex Rewards, alertes de spawns rares, Boss PVE et plus de 80 commandes utilitaires et de modération.
Stockage hybride SQLite (par défaut) / MySQL avec transactions atomiques.

## Installation

| Où | Fichier |
|---|---|
| Serveur | `cobblepulse-utils-1.0.0.jar` dans `mods/` |
| Modpack (clients) | le même jar : la touche du Hub et l'écran du Wiki sont côté client |

Dépendances : Fabric Loader ≥ 0.16, Fabric API, Fabric Language Kotlin, Cobblemon ≥ 1.8.0 (testé 1.8.4).
SQLite, MySQL et HikariCP sont inclus dans le jar.

Recommandés : **LuckPerms** (grades, quotas, taxes), **CobbleDollars** (économie par défaut) ou **Impactor**.
Optionnels : CobblePulse (Clans), Battletower, Crates, Battlepass (passerelles du Hub), Mega Showdown (Boss Méga / Primo).

## Menus

Avec le mod côté client, **tous les menus sont des écrans CobblePulse** : Hub, Wiki, Boutique, GTS, STS, Échange Miracle,
warps, homes, kits, Dex Rewards, radar des spawns, panneau Staff et tous les éditeurs.
- Chaque entrée est une carte : icône, nom et deux lignes de description, avec le détail complet au survol.
  Les actions (retour, pages, solde, créer…) sont dans la barre du bas.
- Les listes défilent sans pagination et ont une barre de recherche (Ctrl+F) dès 13 entrées.
- Les saisies (prix, nom de home, délai, commande…) se font dans une fenêtre par-dessus le menu, pas dans le chat.
  Annuler revient au menu.
- Les messages du serveur (achat réussi, fonds insuffisants…) s'affichent dans l'écran ouvert.
- Clic gauche, clic droit, Maj + clic et clic molette gardent leur rôle. Échap ou la touche d'inventaire ferme l'écran.

Restent de vrais inventaires, parce qu'on y dépose des objets : l'Enderchest, la poubelle, `/invsee` et `/endersee`,
le contenu d'un kit dans l'éditeur, l'établi et l'enclume.

Un joueur sans le mod côté client reçoit les mêmes menus sous forme de coffres, avec la saisie dans le chat.
Le Wiki, lui, demande le mod. `general.json` → `client_screens: false` force les coffres pour tout le monde.

## Hub (touche H, `/menu`, `/cp`, `/cobblepulse`)

- **Pôle 1 – Outils** : Wiki, Téléportation (Spawn, warps, Mes Homes, RTP), Boutique, GTS, STS, Échange Miracle, Kits,
  Enderchest, Dex Rewards, Radar des spawns rares.
- **Pôle 2 – Écosystème** : Tour de Combat, Caisses & Clés (aperçu de chaque caisse), Passe de Combat, Vœux ADN,
  Clans, Ligue & Tournois. Une tuile n'apparaît que si le mod correspondant est installé (`general.json` → `gateways`).
- **Pôle 3 – Staff** : panneau des éditeurs (selon les nœuds `cobblepulse.admin.*`).

La touche se change dans Options → Commandes → CobblePulse. Une seconde touche (non assignée par défaut) ouvre directement le Wiki.

### Cohabitation avec le mod CobblePulse (Clans)

`/cp` et `/cobblepulse` ouvrent le Hub. Les sous-commandes du mod Clans (`season`, `payinfluence`…) restent disponibles,
`/cp reload` recharge les deux mods, et la tuile « Clans, Ligue & Tournois » ouvre son menu.

## Fichiers de configuration

```
config/cobblepulse/database.json        stockage (format du cahier des charges)
config/cobblepulse/utils/
  general.json      économie, téléportation, chat, enderchest, passerelles du Hub
  commands.json     activation, alias et accès par défaut de chaque commande
  messages.toml     tous les textes (couleurs &a, &#RRGGBB)
  warps.json        spawn + warps (aussi éditables en jeu)
  kits.json         kits (éditables en jeu, NBT complet conservé)
  dexrewards.json   paliers du Pokédex
  shop.json + shop/<catégorie>.json   Shop Admin (une catégorie par fichier)
  gts.json  sts.json  wondertrade.json  alerts.json  boss.json
  worlds.json            mondes et dimensions (noms, spawns, règles par monde)
  playtimerewards.json   récompenses de temps de jeu
  data/             base SQLite, historique des spawns
```

`/cp reload` recharge tout à chaud, sauf le type de stockage (changer SQLite ↔ MySQL demande un redémarrage).

### Base de données

`storage_type` : `SQLITE` (par défaut, aucune configuration) ou `MYSQL`. Dans `modules_using_database`,
un module à `false` reste en SQLite local même en mode MySQL. Ça permet de partager le GTS et l'Échange Miracle
entre plusieurs serveurs, et de garder le reste local. Modules : `gts`, `sts_logs`, `wondertrade_pool`, `enderchests`,
`boss_first_victories` et `player_data` (homes, surnoms, délais).

Opérations atomiques :
- **GTS** : l'achat passe par une seule transaction. Elle marque l'annonce vendue (une seule vente possible, même entre
  serveurs), paie le vendeur et dépose l'article dans la boîte de réception de l'acheteur. L'article est ensuite remis.
- **Échange Miracle** : le tirage, le retrait du Pokémon tiré, le dépôt et les statistiques sont faits dans la même transaction.
- **STS, Enderchest, premières victoires de Boss** : transactions également.
- L'objet ou le Pokémon est toujours retiré au joueur, et sa sauvegarde forcée, avant l'écriture en base. La remise se fait
  après validation. Un arrêt brutal ne peut donc pas dupliquer d'objet.

## Économie

`general.json` → `economy` : `COBBLEDOLLARS` (par défaut), `IMPACTOR` ou `AUTO`. Les deux mods sont appelés directement
par leur API, sans commande intermédiaire. Les montants sont entiers.

## Noms des Pokémon et Fakémons

Les noms d'espèces envoyés par le mod (alertes, Boss, GTS, boutique, Wiki…) sont traduits par le jeu de chaque joueur,
avec un nom de secours quand il n'a pas la traduction. C'est le cas des Fakémons d'un datapack installé seulement sur
le serveur : sans ce secours, le joueur voyait la clé brute (`cobblemon.species.etreroch.name`).

Le nom de secours est pris, dans l'ordre :
1. dans les fichiers de langue du datapack (`assets/<ns>/lang/fr_fr.json` dans le zip), s'il en contient ;
2. dans ceux des mods (Cobblemon, Mega Showdown…) ;
3. à défaut, dans le champ `name` du fichier de l'espèce.

La langue est `general.json` → `language` (`fr_fr` par défaut, puis `en_us`). Les noms sont relus après `/reload` et
envoyés aux joueurs qui ont le mod, pour le Wiki et les écrans. Les commandes acceptent aussi le nom affiché, accents
compris (`/cp alertes test shiny Étreroch`).

Pour que l'interface de Cobblemon elle-même (combat, PC, équipe) affiche ces noms et les modèles 3D des Fakémons,
le même zip doit aussi être chargé côté client, comme pack de ressources du modpack.

## Modules

**Mondes et dimensions** : chaque monde chargé par le serveur est ajouté automatiquement à `worlds.json`. C'est le cas
des dimensions vanilla, de celles des mods et des mondes créés à chaud par un mod multimonde. Pour chaque monde :
- nom et icône affichés partout (homes, warps, alertes, Wiki) ;
- visibilité dans le menu **Mondes** (`/mondes`, bouton du menu Téléportation), éventuellement réservée par le nœud
  `cobblepulse.world.<namespace>.<monde>` ;
- homes autorisés pour tous, sur permission (`cobblepulse.home.multidimension`) ou interdits ;
- `/rtp` autorisé ou non, avec son propre rayon et son propre centre ;
- arrivées par `/tpa` autorisées ou non ;
- Boss naturels et alertes de spawns rares activés ou non ;
- point d'apparition propre : `/setspawn` dans ce monde (dans le monde principal, c'est aussi le spawn global).

`/spawn` mène au monde principal, ou au spawn du monde actuel si l'option est activée. `/spawn <monde>` et
`/mondes <monde>` mènent au spawn d'un monde, calculé s'il n'est pas défini (plate-forme de l'End, sol sûr dans le
Nether). Tout se règle en jeu : panneau Staff → Mondes & dimensions.

**Téléportation** : compte à rebours de 3 s, annulé si le joueur bouge. Interdit pendant un combat Pokémon.
`/back` revient à la position avant la téléportation ou au lieu de la mort. `/rtp` cherche un endroit sûr (sol solide,
sans liquide), dans les mondes listés. Les warps sont multi-mondes, avec un emblème et une case au choix.
Un warp privé n'est visible qu'avec `cobblepulse.warp.<nom>`.

**Méga-Évolutions** : dans le Wiki, le bouton « M » ne garde que les espèces qui ont une Méga-Évolution, signalées
dans la liste. Les formes Méga, Primo et Originelle sont nommées comme dans les jeux (Méga-Dracaufeu X), avec la
Méga-Gemme, l'Orbe ou l'objet requis. L'onglet Évolutions a une section Méga-Évolution (types, total des statistiques,
objet ; clic pour afficher la forme). Les Méga-Gemmes sont lues dans le registre de Mega Showdown (`mega_showdown:mega`) :
celles des autres mods et des datapacks y apparaissent aussi (Glavenusite et Deviljhonite de CobblePulse-Ecology, par
exemple), et sont données par les Méga-Boss correspondants. Une gemme peut être imposée dans `boss.json` → `form_items`.
Les Méga-Boss apparaissent en Méga-Évolution ou en Primo-Résurgence (voir Boss).

**Wiki** : recherche par nom ou numéro, modèle 3D (glisser pour tourner, normal / chromatique, formes), statistiques,
talents (dont le talent caché), évolutions avec leurs conditions, capacités (niveau, CT, œuf, tuteur).
L'onglet Spawns liste les biomes groupés par mod, avec les noms traduits par le client et les pourcentages calculés
depuis le réservoir de spawn du serveur, datapacks compris. Il affiche aussi les conditions : moment, météo, altitude,
ciel, blocs, structures. L'onglet Boss montre les tables de récompenses.

**Alertes** : chromatiques, paradoxes, Ultra-Chimères, légendaires et fabuleux. L'annonce donne le biome traduit et le
joueur le plus proche ; le staff voit les coordonnées au survol. L'historique garde les 10 derniers spawns par catégorie
(Actif / Capturé par X / Vaincu par X / Tué par X / Disparu). Les Boss ne sont jamais annoncés.
Une seconde annonce part quand un joueur capture le Pokémon rare, le met K.O. en combat ou le tue hors combat
(`alerts.json` → `announce_capture`, `announce_defeat`, `announce_kill`, actifs par défaut).
Pour tester (`cobblepulse.admin.alerts`, ou bouton « Tester une alerte » du radar) :
`/cp alertes test <shiny|paradox|ultra_beast|legendary|mythical> [espèce|*] [moi]`. Un vrai Pokémon sauvage de la
catégorie apparaît devant soi et l'annonce part exactement comme pour un spawn naturel, à tous les joueurs ou à soi seul
avec `moi`. Il entre dans l'historique, et son statut suit la capture, la victoire, la mort ou la disparition ;
ces suites sont annoncées aux mêmes destinataires que l'alerte de test.

**Boss** : un spawn naturel a 1 % de chances (réglable) d'être promu Boss, sur 6 paliers (Commun → Ultime, niveaux 25 à 140).
Un Boss reçoit des IV parfaits, une taille augmentée, une aura et l'IA de combat forte de Cobblemon. Il est invulnérable
aux dégâts physiques et incapturable. Le butin est éditable : objets, argent, BP de la Tour, commandes.

**Méga-Boss** (avec Mega Showdown) : trois paliers à part, Méga Épique (niveaux 75-90), Méga Légendaire (90-100) et
Méga Ultime (120-150). Un Méga-Boss est toujours en Méga-Évolution ou en Primo-Résurgence. Quand un Pokémon promu a une
telle forme, il a 35 % de chances (`special_form_chance`) de devenir un Méga-Boss ; son palier est alors tiré parmi les
trois (poids 60 / 30 / 10). Les paliers ordinaires n'apparaissent plus en Méga ni en Primo ; ils gardent la forme
Originelle. La Méga-Gemme, l'Orbe ou le Globe tombe à 100 % à la première victoire du joueur, puis à 10 %.
Un palier devient un palier de Méga-Boss avec `"mega": true` dans `boss.json` (ou `mega=oui` dans ses réglages en jeu).

**Fenêtre de butin** : après la victoire, l'argent, les BP et les commandes sont remis tout de suite, et les objets
s'affichent dans une fenêtre une fois le combat terminé. Trois boutons : Tout récupérer, Tout lâcher (au sol) et
Tout jeter (détruit, un second clic confirme). Par objet : clic pour récupérer, clic droit pour lâcher, Maj + clic pour
jeter. Si l'inventaire est plein, le reste attend dans la fenêtre. Fermer la fenêtre (ou se déconnecter) place le reste
dans l'inventaire. `boss.json` → `loot_window: false` remet tout directement, sans fenêtre.

Les Boss au-delà du niveau 100 sont enregistrés normalement avec le monde. Si une autre version de Cobblemon refusait
ces niveaux, le mod le détecte au démarrage, l'écrit dans le journal et plafonne les Boss au niveau maximal.

Outils de test du staff (`cobblepulse.admin.boss.spawn`), aussi en boutons dans l'éditeur des Boss :
- `/cp boss spawn <palier> [espèce|*] [auto|base|mega|primal|origin]` fait apparaître un Boss devant soi. Les paliers
  de Méga-Boss sont `mega_epique`, `mega_legendaire` et `mega_ultime` ; demander `mega` ou `primal` sur un palier
  ordinaire fait apparaître le Méga-Boss du palier correspondant ;
- `/cp boss combat <palier> [espèce|*] [forme]` fait apparaître un Boss et lance aussitôt le combat avec son équipe ;
- `/cp boss tous` fait apparaître un Boss de chaque palier (Méga-Boss compris) en arc de cercle ;
- `/cp boss clear` retire tous les Boss, `/cp boss toggle` coupe ou rétablit les Boss naturels.

**Shop** : catégories avec emblème, ouverture et fermeture (globale ou par catégorie), promotions (globale, par catégorie,
par article). La réduction de grade `cobblepulse.shop.discount.N` s'y ajoute. Achat et vente groupés, `/sell hand [all]`.
Articles : objets (NBT complet), commandes, Pokémon personnalisés (IV, EV, nature, talent, capacités, objet tenu,
chromatique, forme, Ball, Téra).

**GTS / STS / Échange Miracle** : prix planchers par catégorie (objets, Pokémon, chromatiques, légendaires…).
Les annonces expirées reviennent dans la boîte de réception.
À la mise en vente, le joueur choisit la durée de l'annonce (24 h, 48 h, 3 j, 7 j, 14 j par défaut). Jusqu'à la durée
incluse dans son grade (`cobblepulse.gts.duration.N`), il paie la taxe normale. Au-delà, chaque durée ajoute sa taxe
(+2 %, +5 %, +10 % par défaut ; plafond `max_tax`). L'écran affiche la taxe et le montant net pour chaque durée.
En commande : `/gts sell <prix> [durée]` et `/gts sellpoke <case> <prix> [durée]` (ex. `7j`). Le panneau Staff écrit directement dans LuckPerms,
grade par grade, les taxes et quotas GTS/STS (`cobblepulse.gts.tax.N`, etc.). Le réservoir miracle est rempli
automatiquement (72 Pokémon par défaut), avec filtres (niveau, chromatiques, légendaires, liste noire) et statistiques.
Le délai avant le prochain échange s'affiche en direct dans l'écran (barre de progression), sur chaque Pokémon de
l'équipe et sur la tuile du Hub.

**Éditeur de boutique** : rien à avoir en main ni dans l'équipe.
- *Objet* : choisi dans un sélecteur qui liste tous les objets du jeu et des mods (recherche, filtre par mod), puis la
  quantité par lot et les prix. Le clic droit garde l'ancienne méthode : l'objet en main, avec tout son NBT.
- *Pokémon* : un créateur permet de choisir l'espèce (fakemons compris), la forme (Méga comprises), le niveau,
  chromatique ou non, les IV, la nature, le talent (caché compris), le genre, la Ball, l'objet tenu, le type Tera, le
  surnom et les capacités, avec un aperçu 3D. Le clic droit copie un Pokémon de l'équipe.
- *Commande* : le texte de la commande, puis une icône choisie dans le sélecteur, puis les prix.

Les mêmes sélecteurs servent pour les icônes (catégories, mondes, paliers) et les récompenses (temps de jeu).
Sans le mod côté client, l'identifiant ou la fiche se saisit dans le chat.

**Kits** : contenu édité en jeu en déposant les objets dans un coffre. Délai, usage unique, accès libre ou
`cobblepulse.kit.<nom>`, commandes bonus.

**Enderchest** : 1 à 6 lignes et 1 à 5 pages selon le grade. Le clic sur un coffre de l'Ender l'ouvre, et le contenu
vanilla est importé une fois. `/ec <joueur>` et `/endersee` pour le staff.

**Récompenses de temps de jeu** (`/ptr`, tuile du Hub) : paliers de temps de jeu actif cumulé (1 h, 5 h, 10 h, 24 h,
50 h, 100 h par défaut) et un bonus répétable (toutes les 60 min). Le temps passé AFK ne compte pas : un joueur qui ne
bouge pas, ne tourne pas la caméra et n'écrit pas pendant 5 min (réglable) est AFK. Le temps déjà joué avant
l'installation est repris de la statistique du jeu. Les paliers sont à réclamer dans le menu, ou remis automatiquement
selon l'option choisie. Un palier peut être réservé à un grade (nœud LuckPerms). Côté staff : éditeur en jeu,
`/ptr ajouter <joueur> <durée>` et `/ptr reinitialiser <joueur|tous> [paliers|tout]` (`cobblepulse.admin.playtime`).
`paliers` (par défaut) permet de réclamer à nouveau paliers et bonus en gardant le temps joué ; `tout` remet aussi le
temps actif à zéro. Joueurs hors ligne compris. Les mêmes actions existent en boutons dans l'éditeur des paliers. `/playtime` affiche
aussi le temps actif et le prochain palier.

**Dex Rewards** : paliers en pourcentage ou en nombre d'espèces capturées, récompenses éditables en jeu.

## Commandes

Toutes les commandes du cahier des charges (§4.1 à §4.5) sont présentes. Dans `commands.json`, chacune peut être
désactivée, ses alias changés, et son accès par défaut réglé (utilisé quand LuckPerms ne dit rien).

Quand une commande existait déjà dans un autre mod (`/pokedex`, `/pokeheal`, `/pokebox` de Cobblemon par exemple),
elle est **fusionnée** : la forme sans argument est celle de CobblePulse, et les sous-commandes d'origine restent utilisables.
`/msg`, `/tell`, `/w`, `/list` et `/pc` sont remplacées, pour gérer `/reply`, `/ignore`, la liste par grade et le PC à distance.
`/tp`, `/gamemode`, `/kick`, `/ban` et `/pardon` restent les commandes vanilla, accessibles en plus avec
`cobblepulse.admin.tp`, `.gamemode` et `.sanctions`. `/gm` est ajouté ; `/tempban` utilise la liste de bans vanilla, avec expiration.

## Permissions (LuckPerms)

| Nœud | Effet |
|---|---|
| `cobblepulse.command.<commande>` | commandes joueurs (`menu`, `wiki`, `warp`, `home`, `gts`, `fly`…) |
| `cobblepulse.command.gts.sell`, `.gts.sellpoke`, `.sts.sell` | sous-commandes de vente |
| `cobblepulse.admin.<nœud>` | staff : `menu`, `reload`, `setspawn`, `warps`, `shop`, `kits`, `boss`, `boss.toggle`, `boss.spawn`, `boss.clear`, `gts`, `gts.toggle`, `sts.toggle`, `shop.toggle`, `dexrewards`, `invsee`, `endersee`, `tp`, `tphere`, `tpall`, `vanish`, `vanish.see`, `god`, `gamemode`, `speed`, `broadcast`, `sudo`, `mute`, `mute.bypass`, `sanctions`, `worlds`, `playtime`, `alerts` |
| `cobblepulse.warp.<nom>` / `cobblepulse.warp.*` | warps privés |
| `cobblepulse.world.<namespace>.<monde>` / `cobblepulse.world.*` | mondes réservés (menu Mondes, `/mondes`, `/spawn <monde>`) |
| `cobblepulse.home.limit.<n>` | nombre de homes |
| `cobblepulse.home.multidimension` | homes hors Overworld |
| `cobblepulse.teleport.bypassdelay` / `.bypasscooldown` | pas de compte à rebours / délai |
| `cobblepulse.back.ondeath` | `/back` vers le lieu de mort |
| `cobblepulse.ec.rows.<1-6>` / `cobblepulse.ec.pages.<1-5>` | taille de l'Enderchest |
| `cobblepulse.kit.<nom>` / `cobblepulse.kit.bypasscooldown` | kits |
| `cobblepulse.gts.tax.<%>` / `.gts.limit.<n>` / `.gts.duration.<h>` | GTS par grade : taxe, annonces, durée incluse sans supplément (la plus petite taxe et les plus grands quotas s'appliquent) |
| `cobblepulse.sts.limit.<n>` | Pokémon revendables par 24 h |
| `cobblepulse.shop.discount.<%>` | réduction personnelle |
| `cobblepulse.wondertrade.bypasscooldown` | Échange Miracle sans délai |
| `cobblepulse.alerts.receive` / `.alerts.hovercoords` | alertes / coordonnées au survol |
| `cobblepulse.boss.bypasslimit` | pas de délai d'engagement des Boss Ultimes |
| `cobblepulse.chat.color` | couleurs dans `/nick` et `/msg` |

Sans LuckPerms, ou si un nœud n'est pas défini : les commandes joueurs suivent `commands.json` (accès par défaut),
les nœuds staff demandent d'être opérateur (niveau 2, réglable), et les avantages (bypass, VIP) sont refusés.

## Tests automatiques (GitHub Actions)

À chaque push, un vrai serveur Fabric 1.21.1 démarre avec Cobblemon 1.8.4, LuckPerms, CobbleDollars et les mods
CobblePulse (Clans, Tour, Caisses). Les auto-tests tournent en SQLite puis en MySQL, avec un redémarrage pour vérifier
la persistance. Les auto-tests vérifient aussi que chaque menu s'ouvre en écran : navigation par clic, clic périmé
ignoré, saisie validée ou annulée, liste complète, rafraîchissement, fermeture, coffre pour un client sans le mod.
Un vrai client se connecte ensuite et ouvre le Hub, le Wiki (chaque onglet) et tous les menus. Il navigue au clic
(catégorie, quantité, achat), crée un home par la fenêtre de saisie et filtre une longue liste avec la recherche.
Les journaux et les captures sont sur la branche `ci-output`.
