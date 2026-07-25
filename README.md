# 🌲 WildTimber

**WildTimber** est un plugin Spigot / Paper / Purpur moderne et ultra-complet d'abattage d'arbres réaliste pour Minecraft (support Minecraft 1.21 & 26.2+).

---

## ✨ Fonctionnalités Principales

* 🪓 **Abattage Réaliste & Dynamique** :
  * Détection automatique des arbres via un algorithme BFS (26 directions) identifiant bûches, feuilles, racines et blocs rattachés.
  * **Vérification d'Enracinement** : L'arbre doit toucher le sol pour être abattu (configurable globalement ou par biome).
  * **Durée Dynamique de Clic Droit** : La vitesse d'abattage au clic droit est synchronisée avec la vitesse de casse vanilla du joueur (`breakSpeed`), équilibrant ainsi le clic droit et le clic gauche.

* 🖥️ **Interface GUI Complète en Jeu (`/wt gui`)** :
  * Editeur graphique complet en coffre permettant de tout configurer sans toucher aux fichiers YAML.
  * Gestion des configurations globales et spécifiques par biome (`biomes.yml`).
  * Création de biomes personnalisés en un clic à partir de votre position actuelle.
  * Édition dynamique des listes de matériaux (bûches, feuilles, attachments) avec prise en charge des items en main ou saisie dans le chat.

* 🩺 **Système de PV d'Arbre & BossBar** :
  * Barre de progression visuelle (BossBar) affichant le nom de l'arbre, son pourcentage et ses PV restants.
  * Masquage automatique en cas d'éloignement (> 5 blocs) ou d'inactivité (> 5 secondes).
  * Régénération automatique des PV au fil du temps en cas d'abandon de la coupe.
  * Progression visuelle des fissures sur la bûche ciblée (`crack stage`).

* ⚡ **Performance & Sécurité** :
  * **Staged Cut Scheduler** : Découpage progressif par tranches (slices) pour les très grands arbres afin d'éviter tout lag serveur.
  * **Anti-Cheat Intégré** : Protection contre les autoclickers sans faux positifs lors des clics droits légitimes.
  * **Compatibilité Protections** : Intégration avec les plugins de claims / protection de zones.
  * **Commande Annulation (`/wt undo`)** : Restaure instantanément le dernier arbre abattu par un joueur.

---

## 📜 Commandes & Permissions

Toutes les commandes sont accessibles sous le préfixe principal `/wildtimber` (ou `/wt`).

| Commande | Description | Permission |
| :--- | :--- | :--- |
| `/wt` ou `/wt gui` | Ouvre l'interface graphique de configuration complète | `wildtimber.admin.gui` |
| `/wt reload` | Recharge l'ensemble des fichiers de configuration (`config.yml`, `biomes.yml`, `blocks.yml`, `lang.yml`) | `wildtimber.admin.reload` |
| `/wt debug` | Active ou désactive le mode de journalisation debug en console | `wildtimber.admin.debug` |
| `/wt blacklist` | Bascule la protection contre la casse des blocs de construction collés | `wildtimber.admin.blacklist` |
| `/wt treecontact` | Active ou désactive l'exigence du contact au sol | `wildtimber.admin.treecontact` |
| `/wt godmode [joueur]` | Active le mode d'abattage instantané en un seul coup | `wildtimber.admin.godmode` |
| `/wt undo [joueur]` | Annule le dernier abattage et restaure les blocs | `wildtimber.admin.undo` |
| `/wt toggle [joueur]` | Active ou désactive l'abattage WildTimber pour soi-même | `wildtimber.toggle` |

### 🛡️ Permissions Spéciales
* `wildtimber.use` *(défaut: tous)* : Permet d'utiliser le système d'abattage d'arbres.
* `wildtimber.bypass.protection` *(défaut: op)* : Contourne les protections de zones.
* `wildtimber.bypass.cooldown` *(défaut: op)* : Ignore les cooldowns de clic.

---

## 🛠️ Compilation & Installation

### Prérequis
* Java 21+
* Maven 3.8+

### Compilation
Pour compiler le fichier `.jar` optimisé pour Paper / Purpur 26.2 :

```bash
mvn clean package -P paper-26.2
```

Le fichier compilé `WildTimber-paper-1.0.0.jar` se trouvera dans le dossier `target/`.

---

## 📄 Licence & Crédits
Développé avec ❤️ pour des serveurs Minecraft modernes et performants.
