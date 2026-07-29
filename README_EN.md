<!-- WildTimber - README_EN.md -->
<!-- Wild Series signature by Toryar1 -->

<div align="center">

# 🌲 WildTimber

[![License](https://img.shields.io/badge/License-Proprietary-red?style=for-the-badge)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.0.0-brightgreen?style=for-the-badge)]()
[![Paper](https://img.shields.io/badge/Paper-1.19%20→%2026.2-orange?style=for-the-badge)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21+-blue?style=for-the-badge)](https://adoptium.net)

[![Français](https://img.shields.io/badge/🇫🇷-Français-blue?style=flat-square)](README.md)
[![English](https://img.shields.io/badge/🇬🇧-English-red?style=flat-square)](README_EN.md)

**Realistic, biome-configurable tree felling plugin ready for Paper/Purpur servers.**

*Part of the Wild Series — high-performance plugins sharing a common aesthetic and philosophy.*

</div>

---

## 🎯 Overview

**WildTimber** completely reimagines tree chopping in Minecraft. Every tree has its own **Health Points**, a dynamic **BossBar**, and **automatic regeneration** if abandoned mid-felling. All behavior is **configurable per biome**, editable in-game via a **full GUI editor**, and fully respects **100% of existing land protection** plugins on your server.

---

## ✨ Features

<details>
<summary><b>🪓 Realistic & Dynamic Tree Felling</b></summary>

- Automatic tree detection using a 26-way BFS algorithm (diagonal branches included)
- **Root contact check**: trunk must touch the ground to be felled (configurable)
- **Dynamic right-click speed** synchronized with vanilla mining speed (`breakSpeed`)
- Protection against adjacent building blocks (planks, slabs, etc.)
- Fused-tree separation algorithm using bottleneck detection

</details>

<details>
<summary><b>🩺 HP System & BossBar</b></summary>

- Dynamic progress bar showing tree species name, HP, and percentage
- Auto-hides when player walks away (>5 blocks) or stays inactive (>5s)
- **Automatic health regeneration** if tree is abandoned mid-felling
- Progressive visual crack stages rendered on the targeted log

</details>

<details>
<summary><b>🖥️ Full In-Game GUI Editor (`/wt gui`)</b></summary>

- Chest-based GUI — manage all settings without touching YAML files
- **Global** and **per-biome** configuration management (`biomes.yml`)
- Create custom biomes from your current player location in one click
- Edit material lists using held items or interactive chat inputs

</details>

<details>
<summary><b>⚡ Performance & Security</b></summary>

- **Staged Cut Scheduler**: slice-by-slice felling for mega-trees (zero lag)
- **Built-in Anti-Cheat**: autoclicker protection without false positives
- **Universal protection compatibility**: WorldGuard, GriefPrevention, Lands, Towny, Factions, Residence…
- **Undo command** (`/wt undo`): instantly restores the last felled tree

</details>

<details>
<summary><b>🌐 Multilingual Support</b></summary>

Available languages, automatically extracted on first launch:

| Code | Language |
|:---:|---|
| `fr` | 🇫🇷 Français |
| `en` | 🇬🇧 English |
| `es` | 🇪🇸 Español |
| `de` | 🇩🇪 Deutsch |
| `zh` | 🇨🇳 中文 (Simplified) |

Switch language live: `/wt language <code>`

</details>

---

## 📜 Commands & Permissions

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/wt` or `/wt gui` | Open the in-game configuration GUI | `wildtimber.admin.gui` |
| `/wt reload` | Reload all configuration files | `wildtimber.admin.reload` |
| `/wt language <code>` | Switch plugin language live | `wildtimber.admin.reload` |
| `/wt debug` | Toggle console debug mode | `wildtimber.admin.debug` |
| `/wt blacklist` | Toggle building block protection | `wildtimber.admin.blacklist` |
| `/wt treecontact` | Toggle ground contact requirement | `wildtimber.admin.treecontact` |
| `/wt godmode [player]` | Toggle one-hit instant tree felling | `wildtimber.admin.godmode` |
| `/wt undo [player]` | Undo last tree felling | `wildtimber.admin.undo` |
| `/wt toggle [player]` | Toggle WildTimber for yourself | `wildtimber.toggle` |
| `/wt info` | Display plugin information | `wildtimber.admin` |
| `/wt help` | Show available commands | *(all)* |

### 🛡️ Special Permissions

| Permission | Default | Description |
|---|:---:|---|
| `wildtimber.use` | ✅ everyone | Access to tree felling |
| `wildtimber.bypass.protection` | OP | Bypass land protection checks |
| `wildtimber.bypass.cooldown` | OP | Bypass click cooldowns |

---

## 🏷️ Placeholders (PlaceholderAPI)

If PlaceholderAPI is installed, the following placeholders are available:

| Placeholder | Description |
|---|---|
| `%wildtimber_status%` | Plugin status (ENABLED/DISABLED) |
| `%wildtimber_godmode%` | Player godmode state |
| `%wildtimber_active_trees%` | Number of currently active trees |
| `%wildtimber_version%` | Plugin version |
| `%wildtimber_disabled%` | WildTimber disabled for this player |

---

## 🛠️ Installation & Build

### Quick Install
1. Download the `.jar` matching your server version
2. Drop it in your `plugins/` folder
3. Restart your server
4. Language is automatically set from `plugin.language` in `config.yml`

### Supported Versions

| Profile | Maven Command |
|---|---|
| Paper 1.19 | `mvn clean package -P paper-1.19` |
| Paper 1.20 | `mvn clean package -P paper-1.20` |
| Paper 1.20.6 | `mvn clean package -P paper-1.20.6` |
| Paper 1.21 | `mvn clean package -P paper-1.21` |
| Paper 26.1 | `mvn clean package -P paper-26.1` |
| Paper 26.2 | `mvn clean package -P paper-26.2` |

### Build from Source
```bash
# Requirements: Java 21+ & Maven 3.8+
mvn clean package -P paper-26.2
# Output JAR will be in target/
```

---

## 🐛 Bug Reports

Encountered an issue? Here's how to report it:

1. **Check** it's not a configuration issue or plugin conflict
2. **Browse** [GitHub Issues](https://github.com/Toryar1/WildTimber/issues) to see if it's already reported
3. **Open an Issue** with:
   - Plugin version and server version
   - Steps to reproduce the bug
   - Full error output from console
4. **Contact me on Discord**: **Toryar** for a faster response

> ⚠️ Please do not use Issues for feature requests or general questions.

---

## 📄 License

© 2026 **Toryar1**. All Rights Reserved.

This project is under a **Proprietary License**. Any unauthorized copying, distribution, or commercial exploitation without prior written consent and financial compensation is strictly prohibited. See the [LICENSE](LICENSE) file for details.

---

<div align="center">

*🌿 Wild Series — Plugins crafted for modern Minecraft servers*

**[WildTimber](https://github.com/Toryar1/WildTimber)** • Made with ❤️ by [Toryar1](https://github.com/Toryar1)

</div>
