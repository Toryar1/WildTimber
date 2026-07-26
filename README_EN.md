# 🌲 WildTimber

[![Français](https://img.shields.io/badge/Langue-Français-blue)](README.md)
[![English](https://img.shields.io/badge/Language-English-red)](README_EN.md)

**WildTimber** is a modern, feature-packed, realistic tree-felling Spigot / Paper / Purpur plugin for Minecraft (supporting Minecraft 1.21 & 26.2+).

---

## ✨ Key Features

* 🪓 **Realistic & Dynamic Tree Felling**:
  * Automatic tree detection using a 26-way BFS scanning algorithm identifying logs, leaves, roots, and attached blocks.
  * **Root Contact Check**: Trees must touch solid ground to be felled (globally or per-biome configurable).
  * **Dynamic Right-Click Break Speed**: Right-click felling speed is dynamically synchronized with the player's vanilla mining speed (`breakSpeed`), perfectly balancing right-click and left-click mining rates.

* 🖥️ **Complete In-Game GUI Editor (`/wt gui`)**:
  * Full chest-based GUI editor to manage all settings in-game without touching YAML files.
  * Multi-level management for global configuration and per-biome overrides (`biomes.yml`).
  * One-click custom biome creation based on your current player location.
  * Real-time material list editing (logs, leaves, attachments) using held items or interactive chat inputs.

* 🩺 **Tree HP System & BossBar**:
  * Dynamic visual progress bar (BossBar) displaying tree species name, current percentage, and remaining HP.
  * Automatic auto-hiding when players step away (> 5 blocks) or remain inactive (> 5 seconds).
  * Automatic health regeneration over time if a tree is abandoned mid-felling.
  * Visual crack stage progress rendered directly on the targeted log block (`crack stage`).

* ⚡ **Performance & Security**:
  * **Staged Cut Scheduler**: Slice-by-slice progressive felling for mega-trees to prevent server tick spikes.
  * **Built-in Anti-Cheat**: Protection against rapid autoclickers without false positives on legitimate right-click holds.
  * **Land Claim Compatibility**: Full hook support for land protection plugins.
  * **Undo Command (`/wt undo`)**: Instantly restores the last tree felled by a player.

---

## 📜 Commands & Permissions

All commands are available under the primary `/wildtimber` command (or alias `/wt`).

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/wt` or `/wt gui` | Opens the full in-game configuration GUI editor | `wildtimber.admin.gui` |
| `/wt reload` | Reloads all configuration files (`config.yml`, `biomes.yml`, `blocks.yml`, `lang.yml`) | `wildtimber.admin.reload` |
| `/wt debug` | Toggles console debug logging mode | `wildtimber.admin.debug` |
| `/wt blacklist` | Toggles protection against felling trees connected to building blocks | `wildtimber.admin.blacklist` |
| `/wt treecontact` | Toggles the ground contact requirement | `wildtimber.admin.treecontact` |
| `/wt godmode [player]` | Toggles one-hit instant tree felling | `wildtimber.admin.godmode` |
| `/wt undo [player]` | Undoes the last tree felling and restores all blocks | `wildtimber.admin.undo` |
| `/wt toggle [player]` | Toggles WildTimber felling for oneself | `wildtimber.toggle` |

### 🛡️ Special Permissions
* `wildtimber.use` *(default: true)*: Allows using the tree felling feature.
* `wildtimber.bypass.protection` *(default: op)*: Bypasses land protection restrictions.
* `wildtimber.bypass.cooldown` *(default: op)*: Ignores click cooldowns.

---

## 🛠️ Build & Installation

### Requirements
* Java 21+
* Maven 3.8+

### Compilation
To build the `.jar` artifact optimized for Paper / Purpur 26.2:

```bash
mvn clean package -P paper-26.2
```

The compiled `WildTimber-paper-1.0.0.jar` will be placed in the `target/` directory.

## 📄 License & Credits

© 2026 **Toryar1**. All Rights Reserved.
This project is licensed under a **Proprietary / Confidential License (All Rights Reserved)**. Any unauthorized copying, distribution, modification, or commercial exploitation without prior written authorization and financial compensation is strictly prohibited. See the [LICENSE](LICENSE) file for more details.
