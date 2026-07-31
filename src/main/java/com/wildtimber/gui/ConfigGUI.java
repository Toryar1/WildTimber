package com.wildtimber.gui;

import com.wildtimber.WildTimber;
import com.wildtimber.config.BiomeConfig;
import com.wildtimber.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interface GUI de configuration en jeu pour WildTimber.
 */
public class ConfigGUI implements InventoryHolder {

    public enum MenuType {
        MAIN_MENU,
        QUICK_TOGGLES,
        GLOBAL_CONFIG_CATEGORIES,
        GLOBAL_CONFIG_GENERAL,
        GLOBAL_CONFIG_LIMITS,
        GLOBAL_CONFIG_DECAY,
        GLOBAL_CONFIG_ROOTS,
        GLOBAL_CONFIG_FALLBACK,
        BIOME_LIST,
        BIOME_EDITOR,
        MATERIAL_LIST_EDITOR,
        LANGUAGE_SELECT
    }

    public static class ChatInputSession {
        public final String file; // "config" or "biomes"
        public final String key;
        public final String displayName;
        public final MenuType returnMenu;
        public final String biomeName;
        public final String listType;

        public ChatInputSession(String file, String key, String displayName, MenuType returnMenu, String biomeName, String listType) {
            this.file = file;
            this.key = key;
            this.displayName = displayName;
            this.returnMenu = returnMenu;
            this.biomeName = biomeName;
            this.listType = listType;
        }
    }

    public static final Map<UUID, ChatInputSession> chatSessions = new ConcurrentHashMap<>();

    private final WildTimber plugin;
    private final Player player;
    private final MenuType menuType;
    private final String biomeName;
    private final String listType;
    private final int page;
    private Inventory inventory;

    public ConfigGUI(WildTimber plugin, Player player, MenuType menuType, String biomeName, String listType, int page) {
        this.plugin = plugin;
        this.player = player;
        this.menuType = menuType;
        this.biomeName = biomeName;
        this.listType = listType;
        this.page = page;
        buildInventory();
    }

    public static void openMainMenu(WildTimber plugin, Player player) {
        ConfigGUI gui = new ConfigGUI(plugin, player, MenuType.MAIN_MENU, null, null, 0);
        player.openInventory(gui.getInventory());
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1.0f);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public MenuType getMenuType() {
        return menuType;
    }

    public String getBiomeName() {
        return biomeName;
    }

    public String getListType() {
        return listType;
    }

    public int getPage() {
        return page;
    }

    private void buildInventory() {
        ConfigManager cm = plugin.getConfigManager();
        FileConfiguration cfg = cm.getConfig();
        FileConfiguration bcfg = cm.getBiomesConfig();

        switch (menuType) {
            case MAIN_MENU:
                inventory = Bukkit.createInventory(this, 45, cm.getMessage("gui.title.main_menu", false));
                fillBorders(inventory);
                inventory.setItem(11, createItemFromLang(cm, Material.WRITABLE_BOOK,
                        "gui.main_menu.global_config.name", "gui.main_menu.global_config.lore"));
                inventory.setItem(13, createItemFromLang(cm, Material.OAK_SAPLING,
                        "gui.main_menu.biome_config.name", "gui.main_menu.biome_config.lore"));
                inventory.setItem(15, createItemFromLang(cm, Material.LEVER,
                        "gui.main_menu.quick_toggles.name", "gui.main_menu.quick_toggles.lore"));
                // Langue selector slot 33
                String currentLangDisplayName = ConfigManager.AVAILABLE_LANGUAGES.getOrDefault(cm.getLanguage(), cm.getLanguage().toUpperCase());
                List<String> langLore = new ArrayList<>(cm.getMessageList("gui.main_menu.language.lore"));
                langLore.replaceAll(l -> l.replace("{lang}", currentLangDisplayName + " (" + cm.getLanguage().toUpperCase() + ")"));
                inventory.setItem(33, createItemWithLore(Material.BOOK, cm.getMessage("gui.main_menu.language.name", false), langLore));
                inventory.setItem(31, createItemFromLang(cm, Material.REDSTONE_TORCH,
                        "gui.main_menu.reload.name", "gui.main_menu.reload.lore"));
                inventory.setItem(40, createItem(Material.BARRIER, cm.getMessage("gui.main_menu.close.name", false)));
                break;

            case QUICK_TOGGLES:
                inventory = Bukkit.createInventory(this, 45, cm.getMessage("gui.title.quick_toggles", false));
                fillBorders(inventory);
                inventory.setItem(11, createItemWithDynamicLore(cm, Material.SPYGLASS,
                        "gui.quick_toggles.debug.name", "gui.quick_toggles.debug.lore",
                        "{state}", cm.isDebug() ? cm.getMessage("state_enabled", false) : cm.getMessage("state_disabled", false)));
                inventory.setItem(13, createItemWithDynamicLore(cm, Material.SHIELD,
                        "gui.quick_toggles.blacklist.name", "gui.quick_toggles.blacklist.lore",
                        "{state}", cm.isBlacklistEnabled() ? cm.getMessage("state_enabled", false) : cm.getMessage("state_disabled", false)));
                inventory.setItem(15, createItemWithDynamicLore(cm, Material.GRASS_BLOCK,
                        "gui.quick_toggles.treecontact.name", "gui.quick_toggles.treecontact.lore",
                        "{state}", cm.isTreeContactRequired() ? cm.getMessage("state_enabled", false) : cm.getMessage("state_disabled", false)));
                inventory.setItem(29, createItemWithDynamicLore(cm, Material.GOLDEN_APPLE,
                        "gui.quick_toggles.godmode.name", "gui.quick_toggles.godmode.lore",
                        "{state}", plugin.getTreeManager().isPlayerGodMode(player.getUniqueId()) ? cm.getMessage("state_enabled", false) : cm.getMessage("state_disabled", false)));
                inventory.setItem(31, createItemWithDynamicLore(cm, Material.FEATHER,
                        "gui.quick_toggles.cooldown_bypass.name", "gui.quick_toggles.cooldown_bypass.lore",
                        "{state}", player.hasPermission("wildtimber.bypass.cooldown") ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)));
                inventory.setItem(40, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                break;

            case GLOBAL_CONFIG_CATEGORIES:
                inventory = Bukkit.createInventory(this, 45, cm.getMessage("gui.title.global_config", false));
                fillBorders(inventory);
                inventory.setItem(10, createItemFromLang(cm, Material.GOLDEN_AXE,
                        "gui.global_config.general.name", "gui.global_config.general.lore"));
                inventory.setItem(12, createItemFromLang(cm, Material.IRON_BARS,
                        "gui.global_config.limits.name", "gui.global_config.limits.lore"));
                inventory.setItem(14, createItemFromLang(cm, Material.OAK_LEAVES,
                        "gui.global_config.decay.name", "gui.global_config.decay.lore"));
                inventory.setItem(16, createItemFromLang(cm, Material.DIRT,
                        "gui.global_config.roots.name", "gui.global_config.roots.lore"));
                inventory.setItem(28, createItemFromLang(cm, Material.COMPASS,
                        "gui.global_config.fallback.name", "gui.global_config.fallback.lore"));
                inventory.setItem(40, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                break;

            case GLOBAL_CONFIG_GENERAL:
                inventory = Bukkit.createInventory(this, 45, cm.getMessage("gui.title.config_general", false));
                fillBorders(inventory);
                inventory.setItem(10, createItemWithDynamicLore(cm, Material.CLOCK,
                        "gui.general.click_cooldown.name", "gui.general.click_cooldown.lore",
                        "{value}", String.valueOf(cm.getClickCooldownMs())));
                inventory.setItem(11, createItemWithDynamicLore(cm, Material.COMPASS,
                        "gui.general.hint_cooldown.name", "gui.general.hint_cooldown.lore",
                        "{value}", String.valueOf(cm.getHintCooldownSeconds())));
                inventory.setItem(12, createItemWithDynamicLore(cm, Material.PAPER,
                        "gui.general.send_hint.name", "gui.general.send_hint.lore",
                        "{state}", cm.isSendHintMessage() ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)));
                inventory.setItem(13, createItemWithDynamicLore(cm, Material.ANVIL,
                        "gui.general.extra_loss_enabled.name", "gui.general.extra_loss_enabled.lore",
                        "{state}", cm.isExtraLossEnabled() ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)));
                inventory.setItem(14, createItemWithDynamicLore(cm, Material.IRON_INGOT,
                        "gui.general.extra_loss_points.name", "gui.general.extra_loss_points.lore",
                        "{value}", String.valueOf(cm.getExtraLossPoints())));
                inventory.setItem(40, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                break;

            case GLOBAL_CONFIG_LIMITS:
                inventory = Bukkit.createInventory(this, 45, cm.getMessage("gui.title.config_limits", false));
                fillBorders(inventory);
                inventory.setItem(10, createItemWithDynamicLore(cm, Material.OAK_LOG,
                        "gui.limits.max_logs.name", "gui.limits.max_logs.lore",
                        "{value}", String.valueOf(cm.getMaxLogs())));
                inventory.setItem(11, createItemWithDynamicLore(cm, Material.BRICKS,
                        "gui.limits.max_blocks.name", "gui.limits.max_blocks.lore",
                        "{value}", String.valueOf(cm.getMaxBlocks())));
                inventory.setItem(12, createItemWithDynamicLore(cm, Material.MAP,
                        "gui.limits.max_radius.name", "gui.limits.max_radius.lore",
                        "{value}", String.valueOf(cm.getMaxRadiusXZ())));
                inventory.setItem(13, createItemWithDynamicLore(cm, Material.LADDER,
                        "gui.limits.max_height.name", "gui.limits.max_height.lore",
                        "{value}", String.valueOf(cm.getMaxHeightY())));
                inventory.setItem(14, createItemWithDynamicLore(cm, Material.WOODEN_AXE,
                        "gui.limits.sixway_max_logs.name", "gui.limits.sixway_max_logs.lore",
                        "{value}", String.valueOf(cm.getSixWayMaxLogs())));
                inventory.setItem(40, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                break;

            case GLOBAL_CONFIG_DECAY:
                inventory = Bukkit.createInventory(this, 45, cm.getMessage("gui.title.config_decay", false));
                fillBorders(inventory);
                inventory.setItem(10, createItemWithDynamicLore(cm, Material.OAK_LEAVES,
                        "gui.decay.decay_range_xz.name", "gui.decay.decay_range_xz.lore",
                        "{value}", String.valueOf(cm.getLeafDecayRangeXZ())));
                inventory.setItem(11, createItemWithDynamicLore(cm, Material.JUNGLE_LEAVES,
                        "gui.decay.decay_range_y.name", "gui.decay.decay_range_y.lore",
                        "{value}", String.valueOf(cm.getLeafDecayRangeY())));
                inventory.setItem(12, createItemWithDynamicLore(cm, Material.SUGAR_CANE,
                        "gui.decay.allow_diagonal.name", "gui.decay.allow_diagonal.lore",
                        "{state}", cm.isAllowDiagonalLeaves() ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)));
                inventory.setItem(13, createItemWithDynamicLore(cm, Material.SHEARS,
                        "gui.decay.canopy_cleanup.name", "gui.decay.canopy_cleanup.lore",
                        "{state}", cm.isCanopyCleanupEnabled() ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)));
                inventory.setItem(14, createItemWithDynamicLore(cm, Material.STRING,
                        "gui.decay.canopy_padding.name", "gui.decay.canopy_padding.lore",
                        "{value}", String.valueOf(cm.getCanopyCleanupPadding())));
                inventory.setItem(15, createItemWithDynamicLore(cm, Material.AMETHYST_SHARD,
                        "gui.decay.leaves_batch.name", "gui.decay.leaves_batch.lore",
                        "{value}", String.valueOf(cm.getLeavesPersistenceBatchSize())));
                inventory.setItem(40, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                break;

            case GLOBAL_CONFIG_ROOTS:
                inventory = Bukkit.createInventory(this, 45, cm.getMessage("gui.title.config_roots", false));
                fillBorders(inventory);
                inventory.setItem(10, createItemWithDynamicLore(cm, Material.DIRT,
                        "gui.roots.fill_padding.name", "gui.roots.fill_padding.lore",
                        "{value}", String.valueOf(cm.getRootFillPadding())));
                inventory.setItem(11, createItemWithDynamicLore(cm, Material.COARSE_DIRT,
                        "gui.roots.depth_padding.name", "gui.roots.depth_padding.lore",
                        "{value}", String.valueOf(cm.getRootFillDepthPadding())));
                inventory.setItem(12, createItemWithDynamicLore(cm, Material.ROOTED_DIRT,
                        "gui.roots.root_rep_enabled.name", "gui.roots.root_rep_enabled.lore",
                        "{state}", cm.isRootReplacementEnabled() ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)));
                inventory.setItem(13, createItemWithDynamicLore(cm,
                        cm.getRootReplacementMaterial().isItem() ? cm.getRootReplacementMaterial() : Material.DIRT,
                        "gui.roots.root_rep_material.name", "gui.roots.root_rep_material.lore",
                        "{value}", cm.getRootReplacementMaterial().name()));
                inventory.setItem(40, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                break;

            case GLOBAL_CONFIG_FALLBACK:
                inventory = Bukkit.createInventory(this, 45, cm.getMessage("gui.title.config_fallback", false));
                fillBorders(inventory);
                inventory.setItem(10, createItemWithDynamicLore(cm, Material.COMPASS,
                        "gui.fallback.enabled.name", "gui.fallback.enabled.lore",
                        "{state}", cm.isFallbackEnabled() ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)));
                inventory.setItem(11, createItemWithDynamicLore(cm, Material.CLAY,
                        "gui.fallback.max_blocks.name", "gui.fallback.max_blocks.lore",
                        "{value}", String.valueOf(cm.getFallbackMaxBlocks())));
                inventory.setItem(12, createItemWithDynamicLore(cm, Material.OAK_WOOD,
                        "gui.fallback.trunk_core_radius.name", "gui.fallback.trunk_core_radius.lore",
                        "{value}", String.valueOf(cm.getFallbackTrunkCoreRadius())));
                inventory.setItem(13, createItemWithDynamicLore(cm, Material.OAK_FENCE,
                        "gui.fallback.trunk_min_height.name", "gui.fallback.trunk_min_height.lore",
                        "{value}", String.valueOf(cm.getFallbackTrunkMinHeight())));
                inventory.setItem(14, createItemWithDynamicLore(cm, Material.SPYGLASS,
                        "gui.fallback.max_radius.name", "gui.fallback.max_radius.lore",
                        "{value}", String.valueOf(cm.getFallbackMaxRadius())));
                inventory.setItem(15, createItemWithDynamicLore(cm, Material.SPONGE,
                        "gui.fallback.min_density.name", "gui.fallback.min_density.lore",
                        "{value}", String.valueOf(cm.getFallbackMinDensity())));
                inventory.setItem(40, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                break;

            case BIOME_LIST:
                inventory = Bukkit.createInventory(this, 54, cm.getMessage("gui.title.biome_list", false));
                fillBorders(inventory);

                List<String> biomeKeys = new ArrayList<>(bcfg.getKeys(false));
                Collections.sort(biomeKeys);

                int startIdx = page * 28;
                int slot = 10;
                for (int i = startIdx; i < Math.min(startIdx + 28, biomeKeys.size()); i++) {
                    String name = biomeKeys.get(i);
                    boolean isEnabled = bcfg.getBoolean(name + ".enabled", true);
                    List<String> logs = bcfg.getStringList(name + ".log-blocks");
                    List<String> leaves = bcfg.getStringList(name + ".leaf-blocks");
                    String stateStr = isEnabled ? cm.getMessage("state_enabled", false) : cm.getMessage("state_disabled", false);

                    List<String> entryLore = new ArrayList<>(cm.getMessageList("gui.biome_list.entry.lore"));
                    entryLore.replaceAll(l -> l
                            .replace("{state}", stateStr)
                            .replace("{logs}", String.valueOf(logs.size()))
                            .replace("{leaves}", String.valueOf(leaves.size())));

                    String itemTitle = cm.getMessage("gui.biome_list.entry.title", false).replace("{biome}", name);
                    inventory.setItem(slot, createItemWithLore(Material.MAP, itemTitle, entryLore));
                    slot++;
                    if (slot % 9 == 8) slot += 2;
                }

                inventory.setItem(45, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                if (page > 0) {
                    inventory.setItem(48, createItem(Material.ARROW, cm.getMessage("gui.biome_list.prev_page", false)));
                }
                Biome currentBiome = player.getLocation().getBlock().getBiome();
                String cbName = currentBiome.name().toUpperCase();
                if (cbName.contains(":")) cbName = cbName.substring(cbName.indexOf(':') + 1);
                {
                    final String finalCbName = cbName;
                    List<String> createLore = new ArrayList<>(cm.getMessageList("gui.biome_list.create_from_current.lore"));
                    createLore.replaceAll(l -> l.replace("{biome}", finalCbName));
                    inventory.setItem(49, createItemWithLore(Material.GRASS_BLOCK,
                            cm.getMessage("gui.biome_list.create_from_current.name", false), createLore));
                }

                if (startIdx + 28 < biomeKeys.size()) {
                    inventory.setItem(50, createItem(Material.ARROW, cm.getMessage("gui.biome_list.next_page", false)));
                }
                break;

            case BIOME_EDITOR:
                inventory = Bukkit.createInventory(this, 45,
                        cm.getMessage("gui.title.biome_editor", false).replace("{biome}", biomeName));
                fillBorders(inventory);

                boolean bEnabled = bcfg.getBoolean(biomeName + ".enabled", true);
                String inheritedStr = cm.getMessage("gui.biome_editor.inherited", false);

                inventory.setItem(10, createItemWithDynamicLore(cm, Material.LEVER,
                        cm.getMessage("gui.biome_editor.enabled.name", false),
                        "gui.biome_editor.enabled.lore",
                        "{state}", bEnabled ? cm.getMessage("state_enabled", false) : cm.getMessage("state_disabled", false)));
                inventory.setItem(11, createItemWithDynamicLore(cm, Material.PAPER,
                        "§emin-logs", "gui.biome_editor.min_logs.lore",
                        "{value}", String.valueOf(bcfg.getInt(biomeName + ".min-logs", 2))));
                inventory.setItem(12, createItemWithDynamicLore(cm, Material.PAPER,
                        "§emin-leaf-like", "gui.biome_editor.min_leaves.lore",
                        "{value}", String.valueOf(bcfg.getInt(biomeName + ".min-leaf-like", 3))));
                inventory.setItem(13, createItemWithDynamicLore(cm, Material.PAPER,
                        "§emax-logs", "gui.biome_editor.max_logs.lore",
                        "{value}", bcfg.contains(biomeName + ".max-logs") ? String.valueOf(bcfg.getInt(biomeName + ".max-logs")) : inheritedStr));
                inventory.setItem(14, createItemWithDynamicLore(cm, Material.PAPER,
                        "§emax-blocks", "gui.biome_editor.max_blocks.lore",
                        "{value}", bcfg.contains(biomeName + ".max-blocks") ? String.valueOf(bcfg.getInt(biomeName + ".max-blocks")) : inheritedStr));
                inventory.setItem(15, createItemWithDynamicLore(cm, Material.PAPER,
                        "§emax-radius-xz", "gui.biome_editor.max_radius_xz.lore",
                        "{value}", bcfg.contains(biomeName + ".max-radius-xz") ? String.valueOf(bcfg.getInt(biomeName + ".max-radius-xz")) : inheritedStr));
                inventory.setItem(16, createItemWithDynamicLore(cm, Material.PAPER,
                        "§emax-height-y", "gui.biome_editor.max_height_y.lore",
                        "{value}", bcfg.contains(biomeName + ".max-height-y") ? String.valueOf(bcfg.getInt(biomeName + ".max-height-y")) : inheritedStr));
                inventory.setItem(19, createItemWithDynamicLore(cm, Material.PAPER,
                        "§eprotection-belt-radius", "gui.biome_editor.belt_radius.lore",
                        "{value}", String.valueOf(bcfg.getInt(biomeName + ".protection-belt-radius", 4))));
                inventory.setItem(20, createItemWithDynamicLore(cm, Material.PAPER,
                        "§ecanopy-cleanup-padding", "gui.biome_editor.canopy_padding.lore",
                        "{value}", String.valueOf(bcfg.getInt(biomeName + ".canopy-cleanup-padding", 12))));

                boolean bRootRep = bcfg.getBoolean(biomeName + ".root-replacement-enabled", true);
                inventory.setItem(21, createItemWithDynamicLore(cm, Material.ROOTED_DIRT,
                        cm.getMessage("gui.biome_editor.root_rep_enabled.name", false),
                        "gui.biome_editor.root_rep_enabled.lore",
                        "{state}", bRootRep ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)));

                String bRepMat = bcfg.getString(biomeName + ".root-replacement-material", "DIRT");
                Material repMat = Material.matchMaterial(bRepMat);
                inventory.setItem(22, createItemWithDynamicLore(cm,
                        repMat != null && repMat.isItem() ? repMat : Material.DIRT,
                        cm.getMessage("gui.biome_editor.root_rep_material.name", false),
                        "gui.biome_editor.root_rep_material.lore",
                        "{value}", bRepMat));

                inventory.setItem(24, createItemFromLang(cm, Material.OAK_LOG,
                        "gui.biome_editor.logs_list.name", "gui.biome_editor.logs_list.lore"));
                inventory.setItem(25, createItemFromLang(cm, Material.OAK_LEAVES,
                        "gui.biome_editor.leaves_list.name", "gui.biome_editor.leaves_list.lore"));
                inventory.setItem(26, createItemFromLang(cm, Material.VINE,
                        "gui.biome_editor.attachments_list.name", "gui.biome_editor.attachments_list.lore"));

                inventory.setItem(40, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                inventory.setItem(44, createItemFromLang(cm, Material.TNT,
                        "gui.biome_editor.delete.name", "gui.biome_editor.delete.lore"));
                break;

            case MATERIAL_LIST_EDITOR:
                inventory = Bukkit.createInventory(this, 54,
                        cm.getMessage("gui.title.material_editor", false)
                                .replace("{biome}", biomeName)
                                .replace("{type}", listType));
                fillBorders(inventory);

                List<String> list = bcfg.getStringList(biomeName + "." + listType);
                int matStartIdx = page * 28;
                int matSlot = 10;
                for (int i = matStartIdx; i < Math.min(matStartIdx + 28, list.size()); i++) {
                    String matName = list.get(i);
                    Material mat = Material.matchMaterial(matName);
                    List<String> entryLore = new ArrayList<>(cm.getMessageList("gui.material_editor.entry.lore"));
                    inventory.setItem(matSlot, createItemWithLore(mat != null && mat.isItem() ? mat : Material.STONE,
                            "§f" + matName, entryLore));
                    matSlot++;
                    if (matSlot % 9 == 8) matSlot += 2;
                }

                inventory.setItem(45, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                if (page > 0) {
                    inventory.setItem(48, createItem(Material.ARROW, cm.getMessage("gui.biome_list.prev_page", false)));
                }
                inventory.setItem(49, createItemFromLang(cm, Material.OAK_SIGN,
                        "gui.material_editor.add_hand.name", "gui.material_editor.add_hand.lore"));
                inventory.setItem(50, createItemFromLang(cm, Material.BOOK,
                        "gui.material_editor.add_name.name", "gui.material_editor.add_name.lore"));

                if (matStartIdx + 28 < list.size()) {
                    inventory.setItem(51, createItem(Material.ARROW, cm.getMessage("gui.biome_list.next_page", false)));
                }
                break;

            case LANGUAGE_SELECT:
                inventory = Bukkit.createInventory(this, 45, cm.getMessage("gui.title.language_select", false));
                fillBorders(inventory);

                String curLang = cm.getLanguage();
                int[] langSlots = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
                int lIdx = 0;
                for (Map.Entry<String, String> entry : ConfigManager.AVAILABLE_LANGUAGES.entrySet()) {
                    if (lIdx >= langSlots.length) break;
                    String code = entry.getKey();
                    String name = entry.getValue();
                    boolean isCurrent = code.equalsIgnoreCase(curLang);

                    Material mat = isCurrent ? Material.EMERALD_BLOCK : Material.PAPER;
                    String stateStr = isCurrent ? cm.getMessage("gui.language_select.active", false) : cm.getMessage("gui.language_select.select", false);

                    List<String> itemLore = new ArrayList<>(cm.getMessageList("gui.language_select.entry.lore"));
                    itemLore.replaceAll(l -> l.replace("{code}", code.toUpperCase()).replace("{state}", stateStr));

                    ItemStack item = createItemWithLore(mat, "§e" + name + " §7(" + code.toUpperCase() + ")", itemLore);
                    if (isCurrent) {
                        applyGlint(item);
                    }
                    inventory.setItem(langSlots[lIdx], item);
                    lIdx++;
                }

                inventory.setItem(40, createItem(Material.ARROW, cm.getMessage("gui.button.back", false)));
                break;
        }
    }

    private void fillBorders(Inventory inv) {
        ItemStack border = createItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            if (i < 9 || i >= size - 9 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, border);
            }
        }
    }

    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createItemWithLore(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Creates an item using a lang key for name and a lore key for the lore list.
     */
    private ItemStack createItemFromLang(ConfigManager cm, Material mat, String nameKey, String loreKey) {
        String name = cm.getMessage(nameKey, false);
        List<String> lore = cm.getMessageList(loreKey);
        return createItemWithLore(mat, name, lore);
    }

    /**
     * Creates an item using a lang key for lore, replacing one placeholder token.
     * The name can be a literal string (§e...) OR a lang key if it doesn't start with §.
     */
    private ItemStack createItemWithDynamicLore(ConfigManager cm, Material mat, String nameOrKey, String loreKey, String placeholder, String value) {
        String name = (nameOrKey != null && !nameOrKey.startsWith("§")) ? cm.getMessage(nameOrKey, false) : nameOrKey;
        List<String> lore = new ArrayList<>(cm.getMessageList(loreKey));
        lore.replaceAll(l -> l.replace(placeholder, value));
        return createItemWithLore(mat, name, lore);
    }

    /**
     * Overload with resolved name from lang key.
     */
    private ItemStack createItemWithDynamicLore(ConfigManager cm, Material mat, String resolvedName, String loreKey, String placeholder, String value, boolean isLangKey) {
        String name = isLangKey ? cm.getMessage(resolvedName, false) : resolvedName;
        List<String> lore = new ArrayList<>(cm.getMessageList(loreKey));
        lore.replaceAll(l -> l.replace(placeholder, value));
        return createItemWithLore(mat, name, lore);
    }

    private static void applyGlint(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        try {
            java.lang.reflect.Method m = meta.getClass().getMethod("setEnchantmentGlintOverride", Boolean.class);
            m.invoke(meta, true);
            item.setItemMeta(meta);
            return;
        } catch (Throwable ignored) {}
        org.bukkit.enchantments.Enchantment unb = com.wildtimber.util.EnchantCompat.getEnchant("unbreaking", "DURABILITY");
        if (unb != null) {
            meta.addEnchant(unb, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
    }
}
