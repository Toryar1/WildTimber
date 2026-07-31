<!-- WildTimber - README_FR.md -->
<!-- Part of Wild Series by Toryar1 -->

<div align="center">

# 🌲 WildTimber

[![Licence](https://img.shields.io/badge/Licence-Propri%C3%A9taire-red?style=for-the-badge)](LICENSE)
[![SpigotMC](https://img.shields.io/badge/SpigotMC-Ressource-orange?style=for-the-badge)](https://www.spigotmc.org/resources/wildtimber.137546/)
[![Changelog](https://img.shields.io/badge/Changelog-v1.0.0-blueviolet?style=for-the-badge)](CHANGELOG.md)
[![Paper](https://img.shields.io/badge/Paper-1.19%20%E2%86%92%2026.2-orange?style=for-the-badge)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21+-blue?style=for-the-badge)](https://adoptium.net)

[![English](https://img.shields.io/badge/🇬🇧-English-red?style=flat-square)](README.md)
[![Français](https://img.shields.io/badge/🇫🇷-Français-blue?style=flat-square)](README_FR.md)

**Plugin d'abattage d'arbres réaliste, configurable par biome et prêt pour les serveurs Paper/Purpur.**

*Partie de la Wild Series — plugins à haute performance partageant une esthétique et une philosophie communes.*

</div>

---

## 📸 Galerie & Démonstrations

Ajoutez vos captures d'écran et vidéos de démonstration dans cette section :

### 🎬 Démo Vidéo
<!-- Insérez votre lien vidéo ou démonstration GIF ici -->
[![Regarder la Vidéo Démo](https://img.shields.io/badge/YouTube-Regarder%20la%20D%C3%A9mo-red?style=for-the-badge&logo=youtube)](https://youtube.com)
<!-- Exemple d'intégration : [![Démo WildTimber](https://img.youtube.com/vi/VOTRE_ID_VIDEO/0.jpg)](https://www.youtube.com/watch?v=VOTRE_ID_VIDEO) -->

### 🖼️ Captures d'écran

<div align="center">

| BossBar & Dégâts en Jeu | Menu Principal GUI (`/wt gui`) |
|:---:|:---:|
| <!-- Remplacez par le chemin de votre image --> ![BossBar Abattage](docs/images/tree_felling.png) | <!-- Remplacez par le chemin de votre image --> ![Menu GUI](docs/images/gui_menu.png) |

| Éditeur de Configuration des Biomes | Raccourcis Rapides |
|:---:|:---:|
| <!-- Remplacez par le chemin de votre image --> ![Éditeur Biome](docs/images/biome_editor.png) | <!-- Remplacez par le chemin de votre image --> ![Raccourcis Rapides](docs/images/quick_toggles.png) |

</div>

---

## 🎯 Présentation

**WildTimber** repense entièrement la coupe d'arbres dans Minecraft. Chaque arbre dispose de ses propres **Points de Vie**, d'une **BossBar** progressive et d'une **régénération automatique** s'il est abandonné. Tout le comportement est **configurable par biome**, modifiable en jeu via un **GUI complet**, et respecte à 100% les **systèmes de protection** déjà présents sur votre serveur.

---

## ✨ Fonctionnalités

<details>
<summary><b>🪓 Abattage Réaliste & Dynamique</b></summary>

- Détection automatique des arbres via un algorithme BFS 26-way (branches diagonales incluses)
- **Vérification d'enracinement** : le tronc doit toucher le sol pour être abattu (configurable)
- **Vitesse dynamique** au clic droit synchronisée avec la vitesse de casse vanilla (`breakSpeed`)
- Protection contre les blocs de construction adjacents (planches, dalles, etc.)
- Algorithme de séparation des arbres fusionnés via goulots

</details>

<details>
<summary><b>🩺 Système de PV & BossBar</b></summary>

- Barre de progression visuelle affichant le nom de l'arbre, ses PV et son pourcentage
- Masquage automatique si le joueur s'éloigne (>5 blocs) ou reste inactif (>5s)
- **Régénération automatique** des PV si un arbre est abandonné
- Fissures visuelles progressives sur la bûche ciblée (`crack stage`)

</details>

<details>
<summary><b>🖥️ Interface GUI Complète (`/wt gui`)</b></summary>

- Éditeur graphique en jeu sans modifier les fichiers YAML
- Gestion des configurations **globales** et **par biome** (`biomes.yml`)
- Création de biomes personnalisés depuis votre position actuelle
- Édition des listes de matériaux avec les items en main ou via le chat

</details>

<details>
<summary><b>⚡ Performance & Sécurité</b></summary>

- **Staged Cut Scheduler** : découpe progressive pour les grands arbres (zéro lag)
- **Anti-Cheat intégré** : protection contre les autoclickers sans faux positifs
- **Compatibilité protection universelle** : WorldGuard, GriefPrevention, Lands, Towny, Factions, Residence…
- **Annulation** (`/wt undo`) : restauration instantanée du dernier abattage

</details>

<details>
<summary><b>🌐 Multilingue</b></summary>

Langues disponibles dès l'installation, téléchargeables automatiquement :

| Code | Langue |
|:---:|---|
| `fr` | 🇫🇷 Français |
| `en` | 🇬🇧 English |
| `de` | 🇩🇪 Deutsch |
| `es` | 🇪🇸 Español |
| `pt_BR` | 🇧🇷 Português (Brasil) |
| `nl` | 🇳🇱 Nederlands |
| `pl` | 🇵🇱 Polski |
| `ru` | 🇷🇺 Русский |
| `it` | 🇮🇹 Italiano |
| `zh_CN` | 🇨🇳 中文 (简体) |

Changement à chaud en jeu : `/wt setlang <code>`

</details>

---

## 📜 Commandes & Permissions

| Commande | Description | Permission |
| :--- | :--- | :--- |
| `/wt` ou `/wt gui` | Interface graphique de configuration | `wildtimber.admin.gui` |
| `/wt reload` | Recharge tous les fichiers de config | `wildtimber.admin.reload` |
| `/wt setlang <code>` | Change la langue du plugin | `wildtimber.admin.reload` |
| `/wt debug` | Active/désactive le mode debug console | `wildtimber.admin.debug` |
| `/wt blacklist` | Toggle protection blocs de construction | `wildtimber.admin.blacklist` |
| `/wt treecontact` | Toggle exigence du contact au sol | `wildtimber.admin.treecontact` |
| `/wt godmode [joueur]` | Abattage instantané en un coup | `wildtimber.admin.godmode` |
| `/wt undo [joueur]` | Annule le dernier abattage | `wildtimber.admin.undo` |
| `/wt toggle [joueur]` | Active/désactive WildTimber pour soi | `wildtimber.toggle` |
| `/wt info` | Informations sur le plugin | `wildtimber.admin` |
| `/wt help` | Liste des commandes disponibles | *(tous)* |

### 🛡️ Permissions Spéciales

| Permission | Défaut | Description |
|---|:---:|---|
| `wildtimber.use` | ✅ tous | Accès à l'abattage |
| `wildtimber.bypass.protection` | OP | Contourne les protections de zones |
| `wildtimber.bypass.cooldown` | OP | Ignore les cooldowns de clic |

---

## 🏷️ Placeholders (PlaceholderAPI)

Si PlaceholderAPI est installé, les placeholders suivants sont disponibles :

| Placeholder | Description |
|---|---|
| `%wildtimber_status%` | Statut du plugin (`ACTIVE` / `DISABLED`) |
| `%wildtimber_godmode%` | Statut Godmode du joueur (`ENABLED` / `DISABLED`) |
| `%wildtimber_active_trees%` | Nombre d'arbres actifs |
| `%wildtimber_version%` | Version du plugin |
| `%wildtimber_disabled%` | Vrai si WildTimber est désactivé pour ce joueur |

---

## 🛠️ Installation & Compilation

### Installation rapide
1. Télécharger le `.jar` correspondant à votre version de serveur
2. Placer le fichier dans votre dossier `plugins/`
3. Redémarrer le serveur
4. La langue sera automatiquement configurée selon `plugin.language` dans `config.yml`

### Versions supportées

| Profil | Commande Maven |
|---|---|
| Paper 1.19 | `mvn clean package -P paper-1.19` |
| Paper 1.20 | `mvn clean package -P paper-1.20` |
| Paper 1.20.6 | `mvn clean package -P paper-1.20.6` |
| Paper 1.21 | `mvn clean package -P paper-1.21` |
| Paper 26.1 | `mvn clean package -P paper-26.1` |
| Paper 26.2 | `mvn clean package -P paper-26.2` |

### Compilation depuis les sources
```bash
# Prérequis : Java 21+ & Maven 3.8+
mvn clean package -P paper-26.2
# Le JAR sera dans target/
```

---

## 🐛 Signaler un Bug

Vous avez rencontré un problème ? Voici comment le signaler :

1. **Vérifiez** qu'il ne s'agit pas d'un problème de configuration ou d'incompatibilité
2. **Consultez** les [Issues GitHub](https://github.com/Toryar1/WildTimber/issues) pour voir si le bug est déjà signalé
3. **Ouvrez une Issue** avec :
   - La version du plugin et du serveur
   - Les étapes pour reproduire le bug
   - Le message d'erreur complet (extrait de la console)
4. **Contactez Discord** : **Toryar** pour une réponse rapide

---

## 📄 Licence

© 2026 **Toryar1**. Tous droits réservés.

Ce projet est sous **licence propriétaire**. Toute copie, redistribution ou exploitation commerciale sans autorisation écrite préalable et rétribution financière est strictement interdite. Voir le fichier [LICENSE](LICENSE) pour plus de détails.

---

<div align="center">

*🌿 Wild Series — Des plugins pensés pour les serveurs modernes*

**[WildTimber](https://github.com/Toryar1/WildTimber)** • Made with ❤️ by [Toryar1](https://github.com/Toryar1)

</div>
