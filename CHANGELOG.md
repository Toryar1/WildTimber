# 📜 Changelog — WildTimber

All notable changes to the **WildTimber** plugin will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## 🔗 Official Links
- **SpigotMC Resource**: [WildTimber on SpigotMC](https://www.spigotmc.org/resources/wildtimber.137546/)
- **GitHub Repository**: [Toryar1/WildTimber](https://github.com/Toryar1/WildTimber)

---

## [Unreleased]

### Added
- Dynamic changelog tracking system for streamlined SpigotMC release notes.

---

## [1.0.0] - 2026-07-30

🎉 **Initial Official Release on SpigotMC!**

### Added
- **Dynamic Tree Felling (26-way BFS)**: Realistic tree detection supporting custom trees with diagonal branches.
- **Tree Hit Points (HP) & BossBar System**:
  - Progressive BossBar displaying tree name, HP, and damage percentage.
  - Automatic BossBar hiding on distance (>5 blocks) or inactivity (>5s).
  - Progressive visual crack stages rendered directly on the targeted log block.
  - Automatic HP regeneration when a tree is left abandoned.
- **In-Game Admin Graphical GUI (`/wt gui`)**:
  - Global configuration editor (Click Cooldown, Scan Limits, Leaf Decay, Roots, Cylindrical Fallback).
  - Biome configuration editor (`biomes.yml`) with in-situ custom biome creation.
  - Quick Toggles menu for Debug, Blacklist, Ground Contact, Godmode, and Cooldown Bypass.
  - Material list editor supporting main hand item insertion or chat input.
  - Multi-language switcher GUI with emerald indicators.
- **Native Multi-Language System**:
  - Full support for 10 languages (`fr`, `en`, `de`, `es`, `pt_BR`, `nl`, `pl`, `ru`, `it`, `zh_CN`).
  - Automatic `config_<lang>.yml` comment rewriting with 100% native inline & header comments.
  - Formatted `/wt help` command using `WildDifficulty` box-drawing style.
- **Advanced Performance & Safety**:
  - **Staged Cut Scheduler**: Multi-pass progressive cutting for giant trees to ensure zero server lag.
  - **Built-in Anti-Cheat**: Click interval & view-angle change tracking.
  - **Universal Protection Compatibility**: Works with WorldGuard, GriefPrevention, Lands, Towny, Factions, Residence, etc.
  - **Instant Undo (`/wt undo`)**: Instant restoration of the last felled tree.
- **PlaceholderAPI Integration**: Custom placeholders (`%wildtimber_status%`, `%wildtimber_godmode%`, `%wildtimber_active_trees%`, `%wildtimber_version%`).

---

<div align="center">

*Wild Series by [Toryar1](https://github.com/Toryar1)*

</div>
