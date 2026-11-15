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

Un joueur dont le client n'a pas le mod reçoit le Hub sous forme de menu coffre. Le Wiki, lui, demande le mod côté client.

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

## Modules

**Téléportation** : compte à rebours de 3 s, annulé si le joueur bouge. Interdit pendant un combat Pokémon.
`/back` revient à la position avant la téléportation ou au lieu de la mort. `/rtp` cherche un endroit sûr (sol solide,
sans liquide), dans les mondes listés. Les warps sont multi-mondes, avec un emblème et une case au choix.
Un warp privé n'est visible qu'avec `cobblepulse.warp.<nom>`.

**Wiki** : recherche par nom ou numéro, modèle 3D (glisser pour tourner, normal / chromatique, formes), statistiques,
talents (dont le talent caché), évolutions avec leurs conditions, capacités (niveau, CT, œuf, tuteur).
L'onglet Spawns liste les biomes groupés par mod, avec les noms traduits par le client et les pourcentages calculés
depuis le réservoir de spawn du serveur, datapacks compris. Il affiche aussi les conditions : moment, météo, altitude,
ciel, blocs, structures. L'onglet Boss montre les tables de récompenses.

**Alertes** : chromatiques, paradoxes, Ultra-Chimères, légendaires et fabuleux. L'annonce donne le biome traduit et le
joueur le plus proche ; le staff voit les coordonnées au survol. L'historique garde les 10 derniers spawns par catégorie
(Actif / Capturé par X / Disparu). Les Boss ne sont jamais annoncés.

**Boss** : un spawn naturel a 1 % de chances (réglable) d'être promu Boss, sur 6 paliers (Commun → Ultime, niveaux 25 à 140).
Un Boss reçoit des IV parfaits, une taille augmentée, une aura et l'IA de combat forte de Cobblemon. Il est invulnérable
aux dégâts physiques et incapturable. Le butin est éditable : objets, argent, BP de la Tour, commandes.
Avec Mega Showdown, un Boss peut apparaître Méga / Primo / Origine. Sa Méga-Gemme, son Orbe ou son Globe tombe à 100 %
à la première victoire du joueur, puis à 10 %. `/cp boss spawn|clear|toggle|editor`.

**Shop** : catégories avec emblème, ouverture et fermeture (globale ou par catégorie), promotions (globale, par catégorie,
par article). La réduction de grade `cobblepulse.shop.discount.N` s'y ajoute. Achat et vente groupés, `/sell hand [all]`.
Articles : objets (NBT complet), commandes, Pokémon personnalisés (IV, EV, nature, talent, capacités, objet tenu,
chromatique, forme, Ball, Téra).

**GTS / STS / Échange Miracle** : prix planchers par catégorie (objets, Pokémon, chromatiques, légendaires…).
Les annonces expirées reviennent dans la boîte de réception. Le panneau Staff écrit directement dans LuckPerms,
grade par grade, les taxes et quotas GTS/STS (`cobblepulse.gts.tax.N`, etc.). Le réservoir miracle est rempli
automatiquement (72 Pokémon par défaut), avec filtres (niveau, chromatiques, légendaires, liste noire) et statistiques.

**Kits** : contenu édité en jeu en déposant les objets dans un coffre. Délai, usage unique, accès libre ou
`cobblepulse.kit.<nom>`, commandes bonus.

**Enderchest** : 1 à 6 lignes et 1 à 5 pages selon le grade. Le clic sur un coffre de l'Ender l'ouvre, et le contenu
vanilla est importé une fois. `/ec <joueur>` et `/endersee` pour le staff.

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
| `cobblepulse.admin.<nœud>` | staff : `menu`, `reload`, `setspawn`, `warps`, `shop`, `kits`, `boss`, `boss.toggle`, `boss.spawn`, `boss.clear`, `gts`, `gts.toggle`, `sts.toggle`, `shop.toggle`, `dexrewards`, `invsee`, `endersee`, `tp`, `tphere`, `tpall`, `vanish`, `vanish.see`, `god`, `gamemode`, `speed`, `broadcast`, `sudo`, `mute`, `mute.bypass`, `sanctions` |
| `cobblepulse.warp.<nom>` / `cobblepulse.warp.*` | warps privés |
| `cobblepulse.home.limit.<n>` | nombre de homes |
| `cobblepulse.home.multidimension` | homes hors Overworld |
| `cobblepulse.teleport.bypassdelay` / `.bypasscooldown` | pas de compte à rebours / délai |
| `cobblepulse.back.ondeath` | `/back` vers le lieu de mort |
| `cobblepulse.ec.rows.<1-6>` / `cobblepulse.ec.pages.<1-5>` | taille de l'Enderchest |
| `cobblepulse.kit.<nom>` / `cobblepulse.kit.bypasscooldown` | kits |
| `cobblepulse.gts.tax.<%>` / `.gts.limit.<n>` / `.gts.duration.<h>` | GTS par grade (la plus petite taxe et les plus grands quotas s'appliquent) |
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
la persistance. Un vrai client se connecte ensuite et ouvre le Hub, le Wiki (chaque onglet) et les menus. Les journaux
et les captures sont sur la branche `ci-output`.
