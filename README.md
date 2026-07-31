<!-- WildTimber - README.md -->
<!-- Part of Wild Series by Toryar1 -->

<div align="center">

# 🌲 WildTimber

[![Licence](https://img.shields.io/badge/License-Proprietary-red?style=for-the-badge)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.0-brightgreen?style=for-the-badge)]()
[![Paper](https://img.shields.io/badge/Paper-1.19%20%E2%86%92%2026.2-orange?style=for-the-badge)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21+-blue?style=for-the-badge)](https://adoptium.net)

[![English](https://img.shields.io/badge/🇬🇧-English-blue?style=flat-square)](README.md)
[![Français](https://img.shields.io/badge/🇫🇷-Français-red?style=flat-square)](README_FR.md)

**Realistic, biome-configurable, high-performance tree felling plugin built for modern Paper/Purpur Minecraft servers.**

*Part of the Wild Series — high-performance plugins sharing a unified visual identity and design philosophy.*

</div>

---

## 📸 Media & Showcase

Add your screenshots and demonstration videos in this section:

### 🎬 Video Demos
<!-- Place your video link or GIF demo here -->
[![Watch Demo Video](https://img.shields.io/badge/YouTube-Watch%20Demo%20Video-red?style=for-the-badge&logo=youtube)](https://youtube.com)
<!-- Example embed: [![WildTimber Gameplay Demo](https://img.youtube.com/vi/YOUR_VIDEO_ID/0.jpg)](https://www.youtube.com/watch?v=YOUR_VIDEO_ID) -->

### 🖼️ Screenshots

<div align="center">

| In-Game BossBar & Damage | In-Game Admin GUI (`/wt gui`) |
|:---:|:---:|
| <!-- Replace with your screenshot path or URL --> ![Tree Felling & BossBar](docs/images/tree_felling.png) | <!-- Replace with your screenshot path or URL --> ![Admin GUI Menu](docs/images/gui_menu.png) |

| Biome Configuration Editor | Quick Toggles & Shortcuts |
|:---:|:---:|
| <!-- Replace with your screenshot path or URL --> ![Biome Editor](docs/images/biome_editor.png) | <!-- Replace with your screenshot path or URL --> ![Quick Toggles](docs/images/quick_toggles.png) |

</div>

---

## 🎯 Overview

**WildTimber** completely reimagines tree felling in Minecraft. Every tree features custom **Hit Points (HP)**, a progressive **BossBar**, and **automatic HP regeneration** if abandoned mid-cut. All behavior is **configurable per biome**, editable in-game via a full **graphical GUI (`/wt gui`)**, and strictly respects 100% of **land protection plugins** on your server.

---

## ✨ Features

<details>
<summary><b>🪓 Realistic & Dynamic Felling</b></summary>

- Automatic tree detection using a 26-way BFS algorithm (diagonal branches included)
- **Rooting Verification**: trunk must touch ground to be felled (configurable)
- **Dynamic Click Speed**: right-click attack rate synchronized with vanilla `breakSpeed`
- Construction Protection: prevents accidental destruction of player structures (planks, slabs, etc.)
- Bottleneck partitioning algorithm to cleanly separate fused adjacent trees

</details>

<details>
<summary><b>🩺 Tree HP & BossBar System</b></summary>

- Visual BossBar progress showing tree type, current HP, max HP and percentage
- Auto-hides when player moves away (>5 blocks) or remains inactive (>5s)
- **Automatic HP Regeneration** when a tree is abandoned mid-cut
- Progressive visual crack stages rendered directly on targeted log blocks (`crack stage`)

</details>

<details>
<summary><b>🖥️ Full Graphical GUI (`/wt gui`)</b></summary>

- Complete in-game graphical editor without manually editing YAML files
- Manage **global** settings and **per-biome** rules (`biomes.yml`)
- Create custom biome configurations directly from your current position
- Edit log/leaf material lists using held items or chat input

</details>

<details>
<summary><b>⚡ Performance & Anti-Cheat</b></summary>

- **Staged Cut Scheduler**: progressive multi-tick slice cutting for giant trees (zero server lag)
- **Built-in Anti-Cheat**: click cooldown & view angle change tracking without false positives
- **Universal Land Protection Compatibility**: WorldGuard, GriefPrevention, Lands, Towny, Factions, Residence…
- **Undo System** (`/wt undo`): instant restoration of the last felled tree

</details>

<details>
<summary><b>🌐 Multi-Language Support</b></summary>

Supported languages out of the box with auto-extraction:

| Code | Language |
|:---:|---|
| `en` | 🇬🇧 English |
| `fr` | 🇫🇷 Français |
| `de` | 🇩🇪 Deutsch |
| `es` | 🇪🇸 Español |
| `pt_BR` | 🇧🇷 Português (Brasil) |
| `nl` | 🇳🇱 Nederlands |
| `pl` | 🇵🇱 Polski |
| `ru` | 🇷🇺 Русский |
| `it` | 🇮🇹 Italiano |
| `zh_CN` | 🇨🇳 中文 (简体) |

Hot-swap language instantly in-game: `/wt setlang <code>`

</details>

---

## 📜 Commands & Permissions

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/wt` or `/wt gui` | Open graphical configuration menu | `wildtimber.admin.gui` |
| `/wt reload` | Reload all configuration & language files | `wildtimber.admin.reload` |
| `/wt setlang <code>` | Change active plugin language | `wildtimber.admin.reload` |
| `/wt debug` | Toggle console debug mode | `wildtimber.admin.debug` |
| `/wt blacklist` | Toggle construction block protection | `wildtimber.admin.blacklist` |
| `/wt treecontact` | Toggle trunk ground contact requirement | `wildtimber.admin.treecontact` |
| `/wt godmode [player]` | Toggle instant one-hit tree felling | `wildtimber.admin.godmode` |
| `/wt undo [player]` | Undo last tree felling operation | `wildtimber.admin.undo` |
| `/wt toggle [player]` | Toggle WildTimber felling for self/player | `wildtimber.toggle` |
| `/wt info` | Display plugin info & active status | `wildtimber.admin` |
| `/wt help` | Display formatted help menu | *(everyone)* |

### 🛡️ Special Permissions

| Permission | Default | Description |
|---|:---:|---|
| `wildtimber.use` | ✅ everyone | Access to tree felling |
| `wildtimber.bypass.protection` | OP | Bypass land protection checks |
| `wildtimber.bypass.cooldown` | OP | Bypass click cooldowns |

---

## 🏷️ PlaceholderAPI Support

When PlaceholderAPI is installed, the following placeholders are available:

| Placeholder | Description |
|---|---|
| `%wildtimber_status%` | Plugin status (`ACTIVE` / `DISABLED`) |
| `%wildtimber_godmode%` | Player godmode status (`ENABLED` / `DISABLED`) |
| `%wildtimber_active_trees%` | Count of currently active trees |
| `%wildtimber_version%` | Plugin version |
| `%wildtimber_disabled%` | True if WildTimber is toggled off for player |

---

## 🛠️ Installation & Build

### Quick Installation
1. Download the `.jar` file for your server version
2. Place the file inside your `plugins/` directory
3. Restart your server
4. Configure language in `config.yml` or in-game via `/wt gui`

### Supported Build Profiles

| Profile | Maven Command |
|---|---|
| Paper 1.19 | `mvn clean package -P paper-1.19` |
| Paper 1.20 | `mvn clean package -P paper-1.20` |
| Paper 1.20.6 | `mvn clean package -P paper-1.20.6` |
| Paper 1.21 | `mvn clean package -P paper-1.21` |
| Paper 26.1 | `mvn clean package -P paper-26.1` |
| Paper 26.2 | `mvn clean package -P paper-26.2` |

### Building from Source
```bash
# Requirements: Java 21+ & Maven 3.8+
mvn clean package -P paper-26.2
# Compiled JAR will be located in target/
```

---

## 🐛 Bug Reports & Support

Found an issue or have a question?

1. Check that it is not a configuration issue
2. Search existing [GitHub Issues](https://github.com/Toryar1/WildTimber/issues)
3. Open a new issue with server log output and steps to reproduce
4. **Contact Discord**: **Toryar** for quick support

---

## 📄 License

© 2026 **Toryar1**. All rights reserved.

This project is released under a **Proprietary License**. Any copying, redistribution, or commercial exploitation without prior written consent is strictly prohibited. See [LICENSE](LICENSE) for details.

---

<div align="center">

*🌿 Wild Series — Modern plugins engineered for high-performance servers*

**[WildTimber](https://github.com/Toryar1/WildTimber)** • Made with ❤️ by [Toryar1](https://github.com/Toryar1)

</div>
