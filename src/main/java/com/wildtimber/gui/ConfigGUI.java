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
        MATERIAL_LIST_EDITOR
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
                inventory = Bukkit.createInventory(this, 45, "§8WildTimber - Menu Principal");
                fillBorders(inventory);
                inventory.setItem(11, createItem(Material.WRITABLE_BOOK, "§6§lConfiguration Globale",
                        "§7Permet de modifier les limites,", "§7les multiplicateurs de dégâts,", "§7et le decay des feuilles."));
                inventory.setItem(13, createItem(Material.OAK_SAPLING, "§a§lConfiguration des Biomes",
                        "§7Liste de tous les biomes.", "§7Ajouter, supprimer et configurer", "§7chaque biome individuellement."));
                inventory.setItem(15, createItem(Material.LEVER, "§e§lToggles Rapides",
                        "§7Activer/Désactiver à la volée", "§7le mode debug, la protection", "§7et l'exigence de contact au sol."));
                inventory.setItem(31, createItem(Material.REDSTONE_TORCH, "§b§lRecharger la config",
                        "§7Recharge tous les fichiers", "§7YAML depuis le disque."));
                inventory.setItem(40, createItem(Material.BARRIER, "§c§lFermer"));
                break;

            case QUICK_TOGGLES:
                inventory = Bukkit.createInventory(this, 45, "§8WildTimber - Toggles Rapides");
                fillBorders(inventory);
                inventory.setItem(11, createItem(Material.SPYGLASS, "§eMode Debug",
                        "§7Mode debug console.", "§7Statut: " + (cm.isDebug() ? cm.getMessage("state_enabled", false) : cm.getMessage("state_disabled", false)), "§e[Clic gauche pour inverser]"));
                inventory.setItem(13, createItem(Material.SHIELD, "§6Protection Blacklist",
                        "§7Empêche de couper les constructions.", "§7Statut: " + (cm.isBlacklistEnabled() ? cm.getMessage("state_enabled", false) : cm.getMessage("state_disabled", false)), "§e[Clic gauche pour inverser]"));
                inventory.setItem(15, createItem(Material.GRASS_BLOCK, "§eContact au sol requis",
                        "§7L'arbre doit toucher le sol.", "§7Statut: " + (cm.isTreeContactRequired() ? cm.getMessage("state_enabled", false) : cm.getMessage("state_disabled", false)), "§e[Clic gauche pour inverser]"));
                inventory.setItem(29, createItem(Material.GOLDEN_APPLE, "§dMon Godmode",
                        "§7Coupe les arbres en un coup.", "§7Statut: " + (plugin.getTreeManager().isPlayerGodMode(player.getUniqueId()) ? cm.getMessage("state_enabled", false) : cm.getMessage("state_disabled", false)), "§e[Clic gauche pour inverser]"));
                inventory.setItem(31, createItem(Material.FEATHER, "§cMon Bypass Cooldown",
                        "§7Bypass le cooldown de clic.", "§7Permission requise: wildtimber.bypass.cooldown", "§7Votre statut: " + (player.hasPermission("wildtimber.bypass.cooldown") ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false))));
                inventory.setItem(40, createItem(Material.ARROW, "§7Retour"));
                break;

            case GLOBAL_CONFIG_CATEGORIES:
                inventory = Bukkit.createInventory(this, 45, "§8WildTimber - Config Globale");
                fillBorders(inventory);
                inventory.setItem(10, createItem(Material.GOLDEN_AXE, "§e§lGénéral & Durabilité", "§7Cooldowns, messages d'aide et usure."));
                inventory.setItem(12, createItem(Material.IRON_BARS, "§e§lLimites de Scan", "§7Nombre max de logs, rayon, etc."));
                inventory.setItem(14, createItem(Material.OAK_LEAVES, "§e§lDecay & Canopée", "§7Rayon de decay, diagonales feuilles."));
                inventory.setItem(16, createItem(Material.DIRT, "§e§lRacines & Rebouchage", "§7Remplacement de racines et padding."));
                inventory.setItem(28, createItem(Material.COMPASS, "§e§lSecours / Fallback", "§7Rayon et densité du scan cylindrique."));
                inventory.setItem(40, createItem(Material.ARROW, "§7Retour"));
                break;

            case GLOBAL_CONFIG_GENERAL:
                inventory = Bukkit.createInventory(this, 45, "§8Config - Général & Outils");
                fillBorders(inventory);
                inventory.setItem(10, createItem(Material.CLOCK, "§eclick-cooldown-ms",
                        "§7Délai minimum entre deux clics.", "§7Valeur: §f" + cm.getClickCooldownMs() + " ms", "§e[Clic gauche pour modifier]"));
                inventory.setItem(11, createItem(Material.COMPASS, "§ehint-cooldown-seconds",
                        "§7Intervalle message d'aide.", "§7Valeur: §f" + cm.getHintCooldownSeconds() + " s", "§e[Clic gauche pour modifier]"));
                inventory.setItem(12, createItem(Material.PAPER, "§esend-hint-message",
                        "§7Envoyer message d'aide au clic.", "§7Statut: " + (cm.isSendHintMessage() ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)), "§e[Clic gauche pour inverser]"));
                inventory.setItem(13, createItem(Material.ANVIL, "§eextra-loss.enabled",
                        "§7Usure supplémentaire de l'outil.", "§7Statut: " + (cm.isExtraLossEnabled() ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)), "§e[Clic gauche pour inverser]"));
                inventory.setItem(14, createItem(Material.IRON_INGOT, "§eextra-loss.points-per-interval",
                        "§7Points de durabilité extra perdus.", "§7Valeur: §f" + cm.getExtraLossPoints(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(40, createItem(Material.ARROW, "§7Retour"));
                break;

            case GLOBAL_CONFIG_LIMITS:
                inventory = Bukkit.createInventory(this, 45, "§8Config - Limites de Scan");
                fillBorders(inventory);
                inventory.setItem(10, createItem(Material.OAK_LOG, "§emax-logs",
                        "§7Nombre max de bûches par arbre.", "§7Valeur: §f" + cm.getMaxLogs(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(11, createItem(Material.BRICKS, "§emax-blocks",
                        "§7Max blocs total (bûches+feuilles).", "§7Valeur: §f" + cm.getMaxBlocks(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(12, createItem(Material.MAP, "§emax-radius-xz",
                        "§7Rayon horizontal max du scan.", "§7Valeur: §f" + cm.getMaxRadiusXZ(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(13, createItem(Material.LADDER, "§emax-height-y",
                        "§7Hauteur verticale max du scan.", "§7Valeur: §f" + cm.getMaxHeightY(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(14, createItem(Material.WOODEN_AXE, "§e6way-max-logs",
                        "§7Budget max scan 6bis.", "§7Valeur: §f" + cm.getSixWayMaxLogs(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(40, createItem(Material.ARROW, "§7Retour"));
                break;

            case GLOBAL_CONFIG_DECAY:
                inventory = Bukkit.createInventory(this, 45, "§8Config - Decay & Canopée");
                fillBorders(inventory);
                inventory.setItem(10, createItem(Material.OAK_LEAVES, "§eleaf-decay-range-xz",
                        "§7Rayon horizontal decay feuilles.", "§7Valeur: §f" + cm.getLeafDecayRangeXZ(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(11, createItem(Material.JUNGLE_LEAVES, "§eleaf-decay-range-y",
                        "§7Rayon vertical decay feuilles.", "§7Valeur: §f" + cm.getLeafDecayRangeY(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(12, createItem(Material.SUGAR_CANE, "§eallow-diagonal-leaves",
                        "§7Autoriser propagation feuilles en diagonale.", "§7Statut: " + (cm.isAllowDiagonalLeaves() ? "§aOUI" : "§cNON"), "§e[Clic gauche pour inverser]"));
                inventory.setItem(13, createItem(Material.SHEARS, "§ecanopy-cleanup-enabled",
                        "§7Nettoyage canopée post-coupe.", "§7Statut: " + (cm.isCanopyCleanupEnabled() ? "§aOUI" : "§cNON"), "§e[Clic gauche pour inverser]"));
                inventory.setItem(14, createItem(Material.STRING, "§ecanopy-cleanup-padding",
                        "§7Padding scan canopée.", "§7Valeur: §f" + cm.getCanopyCleanupPadding(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(15, createItem(Material.AMETHYST_SHARD, "§eleaves-persistence-batch-size",
                        "§7Feuilles traitées par tick.", "§7Valeur: §f" + cm.getLeavesPersistenceBatchSize(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(40, createItem(Material.ARROW, "§7Retour"));
                break;

            case GLOBAL_CONFIG_ROOTS:
                inventory = Bukkit.createInventory(this, 45, "§8Config - Racines & Rebouchage");
                fillBorders(inventory);
                inventory.setItem(10, createItem(Material.DIRT, "§eroot-fill-padding",
                        "§7Marge horizontale pour reboucher.", "§7Valeur: §f" + cm.getRootFillPadding(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(11, createItem(Material.COARSE_DIRT, "§eroot-fill-depth-padding",
                        "§7Profondeur extra sous le log le plus bas.", "§7Valeur: §f" + cm.getRootFillDepthPadding(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(12, createItem(Material.ROOTED_DIRT, "§eroot-replacement.enabled",
                        "§7Remplacer les racines souterraines.", "§7Statut: " + (cm.isRootReplacementEnabled() ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)), "§e[Clic gauche pour inverser]"));
                inventory.setItem(13, createItem(
                        cm.getRootReplacementMaterial().isItem() ? cm.getRootReplacementMaterial() : Material.DIRT,
                        "§eroot-replacement.material",
                        "§7Matériau de remplacement.",
                        "§7Valeur: §f" + cm.getRootReplacementMaterial().name(),
                        "§e[Clic gauche pour modifier via chat]", "§6[Shift + Clic pour utiliser l'item en main]"
                ));
                inventory.setItem(40, createItem(Material.ARROW, "§7Retour"));
                break;

            case GLOBAL_CONFIG_FALLBACK:
                inventory = Bukkit.createInventory(this, 45, "§8Config - Secours / Fallback");
                fillBorders(inventory);
                inventory.setItem(10, createItem(Material.COMPASS, "§efallback.enabled",
                        "§7Activer le scan de secours cylindrique.", "§7Statut: " + (cm.isFallbackEnabled() ? cm.getMessage("state_on", false) : cm.getMessage("state_off", false)), "§e[Clic gauche pour inverser]"));
                inventory.setItem(11, createItem(Material.CLAY, "§efallback.max-blocks",
                        "§7Max blocs autorisés pour fallback.", "§7Valeur: §f" + cm.getFallbackMaxBlocks(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(12, createItem(Material.OAK_WOOD, "§efallback.trunk-core-radius",
                        "§7Rayon noyau pour chercher le tronc.", "§7Valeur: §f" + cm.getFallbackTrunkCoreRadius(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(13, createItem(Material.OAK_FENCE, "§efallback.trunk-min-height",
                        "§7Min bûches pour considérer une col comme tronc.", "§7Valeur: §f" + cm.getFallbackTrunkMinHeight(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(14, createItem(Material.SPYGLASS, "§efallback.max-radius",
                        "§7Rayon max du cylindre de secours.", "§7Valeur: §f" + cm.getFallbackMaxRadius(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(15, createItem(Material.SPONGE, "§efallback.min-density",
                        "§7Densité min pour continuer le cylindre.", "§7Valeur: §f" + cm.getFallbackMinDensity(), "§e[Clic gauche pour modifier]"));
                inventory.setItem(40, createItem(Material.ARROW, "§7Retour"));
                break;

            case BIOME_LIST:
                inventory = Bukkit.createInventory(this, 54, "§8WildTimber - Liste des Biomes");
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

                    inventory.setItem(slot, createItem(Material.MAP, "§e§lBiome : " + name,
                            "§7Statut: " + (isEnabled ? "§aACTIVE" : "§cDESACTIVE"),
                            "§7Bûches: §f" + logs.size() + " type(s)",
                            "§7Feuilles: §f" + leaves.size() + " type(s)",
                            "", "§6[Clic gauche pour configurer]", "§c[Shift + Clic droit pour supprimer]"
                    ));

                    slot++;
                    if (slot % 9 == 8) slot += 2; // Sauter les bordures
                }

                inventory.setItem(45, createItem(Material.ARROW, "§7Retour"));
                if (page > 0) {
                    inventory.setItem(48, createItem(Material.ARROW, "§a◀ Page Précédente"));
                }
                // Bouton Créer Biome
                Biome currentBiome = player.getLocation().getBlock().getBiome();
                String cbName = currentBiome.name().toUpperCase();
                if (cbName.contains(":")) cbName = cbName.substring(cbName.indexOf(':') + 1);
                inventory.setItem(49, createItem(Material.GRASS_BLOCK, "§a§l[+] Créer depuis biome actuel",
                        "§7Crée une entrée pour le biome où", "§7vous vous tenez :", "§b" + cbName, "", "§e[Clic gauche pour créer]"));

                if (startIdx + 28 < biomeKeys.size()) {
                    inventory.setItem(50, createItem(Material.ARROW, "§aPage Suivante ▶"));
                }
                break;

            case BIOME_EDITOR:
                inventory = Bukkit.createInventory(this, 45, "§8Biome : " + biomeName);
                fillBorders(inventory);

                boolean bEnabled = bcfg.getBoolean(biomeName + ".enabled", true);
                inventory.setItem(10, createItem(Material.LEVER, "§eActiver le biome",
                        "§7Statut: " + (bEnabled ? "§aOUI" : "§cNON"), "§e[Clic gauche pour inverser]"));

                inventory.setItem(11, createItem(Material.PAPER, "§emin-logs",
                        "§7Min logs pour un arbre.", "§7Valeur: §f" + bcfg.getInt(biomeName + ".min-logs", 2), "§e[Clic gauche pour modifier]"));
                inventory.setItem(12, createItem(Material.PAPER, "§emin-leaf-like",
                        "§7Min feuilles pour un arbre.", "§7Valeur: §f" + bcfg.getInt(biomeName + ".min-leaf-like", 3), "§e[Clic gauche pour modifier]"));
                inventory.setItem(13, createItem(Material.PAPER, "§emax-logs",
                        "§7Max logs (optionnel).", "§7Valeur: §f" + (bcfg.contains(biomeName + ".max-logs") ? bcfg.getInt(biomeName + ".max-logs") : "Hérité"), "§e[Clic gauche pour modifier]", "§6[Clic droit pour réinitialiser]"));
                inventory.setItem(14, createItem(Material.PAPER, "§emax-blocks",
                        "§7Max blocs (optionnel).", "§7Valeur: §f" + (bcfg.contains(biomeName + ".max-blocks") ? bcfg.getInt(biomeName + ".max-blocks") : "Hérité"), "§e[Clic gauche pour modifier]", "§6[Clic droit pour réinitialiser]"));
                inventory.setItem(15, createItem(Material.PAPER, "§emax-radius-xz",
                        "§7Rayon XZ max (optionnel).", "§7Valeur: §f" + (bcfg.contains(biomeName + ".max-radius-xz") ? bcfg.getInt(biomeName + ".max-radius-xz") : "Hérité"), "§e[Clic gauche pour modifier]", "§6[Clic droit pour réinitialiser]"));
                inventory.setItem(16, createItem(Material.PAPER, "§emax-height-y",
                        "§7Hauteur Y max (optionnel).", "§7Valeur: §f" + (bcfg.contains(biomeName + ".max-height-y") ? bcfg.getInt(biomeName + ".max-height-y") : "Hérité"), "§e[Clic gauche pour modifier]", "§6[Clic droit pour réinitialiser]"));

                inventory.setItem(19, createItem(Material.PAPER, "§eprotection-belt-radius",
                        "§7Rayon ceinture de protection.", "§7Valeur: §f" + bcfg.getInt(biomeName + ".protection-belt-radius", 4), "§e[Clic gauche pour modifier]"));
                inventory.setItem(20, createItem(Material.PAPER, "§ecanopy-cleanup-padding",
                        "§7Padding nettoyage canopée.", "§7Valeur: §f" + bcfg.getInt(biomeName + ".canopy-cleanup-padding", 12), "§e[Clic gauche pour modifier]"));

                boolean bRootRep = bcfg.getBoolean(biomeName + ".root-replacement-enabled", true);
                inventory.setItem(21, createItem(Material.ROOTED_DIRT, "§eroot-replacement-enabled",
                        "§7Remplacement racines.", "§7Statut: " + (bRootRep ? "§aOUI" : "§cNON"), "§e[Clic gauche pour inverser]"));

                String bRepMat = bcfg.getString(biomeName + ".root-replacement-material", "DIRT");
                Material repMat = Material.matchMaterial(bRepMat);
                inventory.setItem(22, createItem(repMat != null && repMat.isItem() ? repMat : Material.DIRT, "§eroot-replacement-material",
                        "§7Matériau de remplacement.", "§7Valeur: §f" + bRepMat, "§e[Clic gauche pour modifier via chat]", "§6[Shift + Clic pour utiliser l'item en main]"));

                inventory.setItem(24, createItem(Material.OAK_LOG, "§6§lListe des Bûches (Logs)", "§7Gérer les types de bûches.", "§e[Clic gauche pour éditer]"));
                inventory.setItem(25, createItem(Material.OAK_LEAVES, "§a§lListe des Feuilles (Leaves)", "§7Gérer les types de feuilles.", "§e[Clic gauche pour éditer]"));
                inventory.setItem(26, createItem(Material.VINE, "§b§lListe des Attachments", "§7Gérer les types d'attachments.", "§e[Clic gauche pour éditer]"));

                inventory.setItem(40, createItem(Material.ARROW, "§7Retour"));
                inventory.setItem(44, createItem(Material.TNT, "§c§lSupprimer le biome", "§7Supprime définitivement ce biome.", "", "§c[Shift + Clic gauche pour confirmer]"));
                break;

            case MATERIAL_LIST_EDITOR:
                inventory = Bukkit.createInventory(this, 54, "§8Éditeur: " + biomeName + " - " + listType);
                fillBorders(inventory);

                List<String> list = bcfg.getStringList(biomeName + "." + listType);
                int matStartIdx = page * 28;
                int matSlot = 10;
                for (int i = matStartIdx; i < Math.min(matStartIdx + 28, list.size()); i++) {
                    String matName = list.get(i);
                    Material mat = Material.matchMaterial(matName);
                    inventory.setItem(matSlot, createItem(mat != null && mat.isItem() ? mat : Material.STONE, "§f" + matName,
                            "§7Matériau enregistré.", "", "§c[Clic pour retirer]"));

                    matSlot++;
                    if (matSlot % 9 == 8) matSlot += 2;
                }

                inventory.setItem(45, createItem(Material.ARROW, "§7Retour"));
                if (page > 0) {
                    inventory.setItem(48, createItem(Material.ARROW, "§a◀ Page Précédente"));
                }
                inventory.setItem(49, createItem(Material.OAK_SIGN, "§a§l[+] Ajouter l'item en main",
                        "§7Ajoute le type de bloc que", "§7vous tenez dans votre main.", "", "§e[Clic gauche pour ajouter]"));
                inventory.setItem(50, createItem(Material.BOOK, "§b§l[+] Ajouter par nom",
                        "§7Saisir manuellement le nom", "§7du matériau dans le chat.", "", "§e[Clic gauche pour ajouter]"));

                if (matStartIdx + 28 < list.size()) {
                    inventory.setItem(51, createItem(Material.ARROW, "§aPage Suivante ▶"));
                }
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
}
