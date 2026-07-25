package com.wildtimber.listener;

import com.wildtimber.WildTimber;
import com.wildtimber.ConsoleColor;
import com.wildtimber.config.BiomeConfig;
import com.wildtimber.config.ConfigManager;
import com.wildtimber.config.ExtraDropEntry;
import com.wildtimber.detection.BlockPos;
import com.wildtimber.detection.TreeDetector;
import com.wildtimber.felling.TreeFeller;
import com.wildtimber.manager.ActiveTree;
import com.wildtimber.manager.TreeManager;
import com.wildtimber.protection.ProtectionHook;
import com.wildtimber.gui.ConfigGUI;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.block.Biome;
import java.util.List;
import java.util.Arrays;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Écouteur d'événements pour le déclenchement de l'abattage, les dégâts et la chute.
 *
 * Flux de jeu :
 *  - Clic droit avec hache sur une bûche non engagée → scan async → engage l'arbre
 *  - Clic droit avec hache sur une bûche d'un arbre engagé → inflige des dégâts
 *  - Clic gauche (BlockBreakEvent) sur une bûche d'un arbre engagé → aussi des dégâts (annule la casse vanilla)
 */
public class BlockListener implements Listener {

    private final WildTimber plugin;
    private final ConfigManager configManager;
    private final TreeManager treeManager;
    private final TreeDetector treeDetector;
    private final TreeFeller treeFeller;
    private final ProtectionHook protectionHook;
    private final AntiCheatTracker antiCheatTracker;

    // Cooldown pour le message d'aide (UUID -> Timestamp)
    private final Map<UUID, Long> hintCooldowns = new ConcurrentHashMap<>();
    // Ensemble des joueurs dont un scan est déjà en cours (anti-spam de clic droit)
    private final Set<UUID> scanInProgress = ConcurrentHashMap.newKeySet();
    // Click cooldown par joueur (UUID -> dernier timestamp de clic)
    private final Map<UUID, Long> lastClickTime = new ConcurrentHashMap<>();
    // Dernier bloc avec crack visuel par joueur (pour reset)
    private final Map<UUID, BlockPos> lastCrackBlock = new ConcurrentHashMap<>();
    // Rate-limit des messages joueur (UUID -> dernier timestamp)


    public BlockListener(WildTimber plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.treeManager = plugin.getTreeManager();
        this.treeDetector = plugin.getTreeDetector();
        this.treeFeller = plugin.getTreeFeller();
        this.protectionHook = plugin.getProtectionHook();
        this.antiCheatTracker = new AntiCheatTracker();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CLIC DROIT : Engagement ou dégâts
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // ── LOG DE DIAGNOSTIC INITIAL : visible dès que l'event est reçu si debug=true ──
        if (configManager.isDebug()) {
            Block clk = event.getClickedBlock();
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "onPlayerInteract → joueur=" + event.getPlayer().getName()
                    + " | action=" + event.getAction()
                    + " | bloc=" + (clk == null ? "null" : clk.getType())
                    + " | pluginActif=" + configManager.isPluginEnabled());
        }

        if (!configManager.isPluginEnabled()) return;

        // On gère uniquement le clic droit sur un bloc depuis la main principale
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        Player player = event.getPlayer();

        // ── LOG DE DIAGNOSTIC DÉTAILLÉ : après validation du clic droit de bloc ──
        if (configManager.isDebug()) {
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "onPlayerInteract (Validé) → monde=" + block.getWorld().getName()
                    + " | mainHand=" + player.getInventory().getItemInMainHand().getType()
                    + " | disabled=" + treeManager.isPlayerDisabled(player.getUniqueId()));
        }

        if (treeManager.isPlayerDisabled(player.getUniqueId())) {
            return;
        }

        World world = block.getWorld();

        // Vérification du monde activé
        if (!configManager.getEnabledWorlds().contains(world.getName())) {
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Monde '" + world.getName()
                        + "' non listé dans worlds.enabled (liste : "
                        + configManager.getEnabledWorlds() + ") → ignoré");
            }
            return;
        }

        // Vérification de la permission
        if (!player.hasPermission("wildtimber.use")) {
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Permission wildtimber.use manquante pour " + player.getName());
            }
            return;
        }

        // Vérification de la hache en main (ou main vide)
        ItemStack item = player.getInventory().getItemInMainHand();
        Material toolType = item == null ? Material.AIR : item.getType();
        if (toolType != Material.AIR && !isAxe(toolType)) {
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Outil non reconnu: " + toolType + " → ignoré");
            }
            return;
        }

        // Vérification préliminaire : est-ce une bûche whitelistée ?
        if (!configManager.getLogWeights().containsKey(block.getType())) {
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Bloc " + block.getType() + " non listé dans logs → ignoré");
            }
            return;
        }

        // Silk Touch → on laisse le stripping vanilla se produire normalement
        if (item != null && item.containsEnchantment(Enchantment.SILK_TOUCH)) {
            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Silk Touch détecté → stripping vanilla autorisé");
            }
            return;
        }

        // À ce stade, on est sûr que c'est une bûche + hache (sans Silk Touch) → on annule
        // pour éviter le stripping vanilla du log
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);

        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());

        // ── CAS 1 : L'arbre est déjà engagé → on inflige des dégâts ──
        ActiveTree existingTree = treeManager.getTreeAt(world, pos);
        if (existingTree != null) {
            applyDamageToTree(player, item, block, existingTree, true);
            return;
        }

        // ── CAS 2 : Scan en cours pour ce joueur → anti-spam ──
        if (scanInProgress.contains(player.getUniqueId())) {
            player.sendMessage(configManager.getMessage("analysis_in_progress", true));
            return;
        }

        // ── CAS 3 : Protection de zone ──
        if (protectionHook.isProtected(player, block.getLocation())) {
            player.sendMessage(configManager.getMessage("tree_protected", true));
            return;
        }

        // ── CAS 4 : Lancement du scan async ──
        scanInProgress.add(player.getUniqueId());
        Map<Long, ChunkSnapshot> snapshots = fetchSnapshotsGrid(world, block.getX(), block.getZ());

        if (configManager.isDebug()) {
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Scan démarré → " + block.getType() + " @ " + pos
                    + " | monde=" + world.getName() + " | joueur=" + player.getName());
        }

        treeDetector.detectTree(world, pos, snapshots, result -> {
            scanInProgress.remove(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {

                if (!result.success()) {
                    if (configManager.isDebug()) {
                        org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Scan échoué → raison=" + result.cancellationReason()
                                + " | logs=" + result.logs().size() + " | feuilles=" + result.leaves().size());
                    }
                    handleScanFailure(player, result.cancellationReason());
                    return;
                }

                // Double-check : l'arbre n'a pas été enregistré entre-temps (race condition)
                if (treeManager.getTreeAt(world, pos) != null) {
                    if (configManager.isDebug()) {
                        org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Arbre déjà enregistré entre-temps @ " + pos);
                    }
                    return;
                }

                ActiveTree tree = new ActiveTree(world, result.logs(), result.leaves(),
                        result.maxHealth(), result.biomeConfig(), result.biomeName(), configManager);
                treeManager.registerTree(tree);

                // Cas Godmode : l'arbre tombe directement en un coup !
                if (treeManager.isPlayerGodMode(player.getUniqueId())) {
                    tree.damage(tree.getMaxHealth(), configManager);
                    for (Player p : tree.getBossBar().getPlayers()) {
                        p.sendMessage(configManager.getMessage("tree_falling", true));
                    }
                    world.playSound(block.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, 1.0f, 0.5f);
                    treeFeller.fell(tree, player);
                    treeManager.unregisterTree(tree);
                    if (configManager.isDebug()) {
                        org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Godmode : Arbre abattu instantanément @ " + pos);
                    }
                    return;
                }

                treeManager.showTreeToPlayer(tree, player);

                player.sendMessage(configManager.getMessage("tree_engaged", true)
                        .replace("{health}", String.format("%.0f", tree.getMaxHealth())));
                world.playSound(block.getLocation(), Sound.BLOCK_WOOD_PLACE, 1.0f, 0.8f);

                if (configManager.isDebug()) {
                    org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Arbre engagé ! logs=" + tree.getLogs().size()
                            + " feuilles=" + tree.getLeaves().size() + " PV=" + tree.getMaxHealth()
                            + " biome=" + result.biomeName());
                }
            });
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CLIC GAUCHE (BlockBreak) : Dégâts si arbre engagé, ou message d'aide
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!configManager.isPluginEnabled()) return;

        Block block = event.getBlock();
        World world = block.getWorld();
        if (!configManager.getEnabledWorlds().contains(world.getName())) return;

        Player player = event.getPlayer();
        if (treeManager.isPlayerDisabled(player.getUniqueId())) {
            removeBlockFromActiveTree(block);
            return;
        }

        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        ActiveTree tree = treeManager.getTreeAt(world, pos);

        if (tree != null) {
            // L'arbre est engagé → annuler la casse normale et infliger des dégâts
            event.setCancelled(true);

            if (!player.hasPermission("wildtimber.use")) {
                player.sendMessage(configManager.getMessage("no_permission", true));
                return;
            }

            ItemStack tool = player.getInventory().getItemInMainHand();

            // Cas spécial : feuille + cisaille → récolte normale
            boolean isLeaf = tree.getLeaves().contains(pos);
            if (isLeaf && (tool.getType() == Material.SHEARS || tool.containsEnchantment(Enchantment.SILK_TOUCH))) {
                event.setCancelled(false);
                tree.getLeaves().remove(pos);
                return;
            }

            // Dégâts via l'outil tenu (pas forcément une hache ici)
            applyDamageToTree(player, tool, block, tree, false);
        } else {
            // Arbre non engagé → message d'aide si c'est une bûche
            if (configManager.isSendHintMessage() && configManager.getLogWeights().containsKey(block.getType())) {
                UUID uuid = player.getUniqueId();
                long now = System.currentTimeMillis();
                long cooldown = configManager.getHintCooldownSeconds() * 1000L;

                if (!hintCooldowns.containsKey(uuid) || (now - hintCooldowns.get(uuid) >= cooldown)) {
                    hintCooldowns.put(uuid, now);
                    // Scan rapide pour vérifier si c'est bien un arbre avant d'envoyer le hint
                    Map<Long, ChunkSnapshot> snapshots = fetchSnapshotsGrid(world, block.getX(), block.getZ());
                    treeDetector.detectTree(world, pos, snapshots, result -> {
                        if (result.success()) {
                            player.sendMessage(configManager.getMessage("hint_right_click", true));
                        }
                    });
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Logique d'infliction de dégâts (partagée entre clic droit et gauche)
    // ─────────────────────────────────────────────────────────────────────────

    private void applyDamageToTree(Player player, ItemStack tool, Block block, ActiveTree tree, boolean isRightClick) {
        World world = block.getWorld();
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());

        // Vérifier que le bloc frappé est bien une bûche (pas une feuille)
        if (!tree.getLogs().contains(pos)) {
            // Peut-être une feuille ou un attachment
            treeManager.showTreeToPlayer(tree, player);
            player.sendMessage(configManager.getMessage("hint_already_engaged", true));
            return;
        }

        // Click cooldown configurable ou dynamique (vérifié en premier pour filtrer l'auto-click)
        if (!player.hasPermission("wildtimber.bypass.cooldown")) {
            long now = System.currentTimeMillis();
            long cooldown;
            if (isRightClick) {
                float breakSpeed = block.getBreakSpeed(player);
                if (breakSpeed <= 0.0f) {
                    cooldown = 10000L;
                } else {
                    cooldown = (long) (Math.ceil(1.0f / breakSpeed) * 50L);
                }
            } else {
                cooldown = configManager.getClickCooldownMs();
            }
            Long last = lastClickTime.get(player.getUniqueId());
            if (last != null && (now - last) < cooldown) {
                return; // ignorer silencieusement
            }
            lastClickTime.put(player.getUniqueId(), now);
        }

        // Vérification anti-triche
        if (!antiCheatTracker.isClickAllowed(player, configManager, isRightClick)) {
            plugin.getLogger().warning("[Anti-Cheat] Le joueur " + player.getName() + " clique beaucoup trop rapidement (autoclicker suspect) ! Clic ignoré.");
            return;
        }
        antiCheatTracker.recordClick(player);

        double damage;
        if (treeManager.isPlayerGodMode(player.getUniqueId())) {
            damage = tree.getMaxHealth();
        } else {
            Material toolType = tool == null ? Material.AIR : tool.getType();
            double toolMultiplier = configManager.getToolMultiplier(toolType);
            int effLevel = tool == null ? 0 : tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
            int sharpLevel = tool == null ? 0 : tool.getEnchantmentLevel(Enchantment.SHARPNESS);
            damage = toolMultiplier + (effLevel * configManager.getEfficiencyDamage()) + (sharpLevel * configManager.getSharpnessDamage());
        }

        tree.damage(damage, configManager);
        treeManager.showTreeToPlayer(tree, player);

        // Effets visuels et sonores
        world.playEffect(block.getLocation(), Effect.STEP_SOUND, block.getType());
        world.playSound(block.getLocation(), Sound.BLOCK_WOOD_BREAK, 0.8f, 0.9f + (float)(Math.random() * 0.2f));

        // Perte de durabilité de la hache
        applyDurabilityLoss(player, tool);

        if (configManager.isDebug()) {
            org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Dégâts=" + String.format("%.2f", damage)
                    + " | Vie=" + String.format("%.1f", tree.getHealth()) + "/" + tree.getMaxHealth()
                    + " | joueur=" + player.getName() + " | outil=" + tool.getType());
        }

        if (tree.getHealth() <= 0) {
            // Reset crack visuel avant la chute
            if (configManager.isVisualTreeHealthEnabled()) {
                BlockPos crackPos = lastCrackBlock.remove(player.getUniqueId());
                if (crackPos != null) {
                    player.sendBlockDamage(
                            new Location(world, crackPos.x(), crackPos.y(), crackPos.z()),
                            0.0f
                    );
                }
            }

            // L'arbre tombe !
            for (Player p : tree.getBossBar().getPlayers()) {
                p.sendMessage(configManager.getMessage("tree_falling", true));
            }
            world.playSound(block.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, 1.0f, 0.5f);
            treeFeller.fell(tree, player);
            treeManager.unregisterTree(tree);
            antiCheatTracker.cleanup(player.getUniqueId());
            lastClickTime.remove(player.getUniqueId());

            if (configManager.isDebug()) {
                org.bukkit.Bukkit.getLogger().info(ConsoleColor.DEBUG_PREFIX + "Arbre abattu ! Falling blocks lancés.");
            }
        } else {
            // Visual crack stage sur la bûche ciblée
            if (configManager.isVisualTreeHealthEnabled()) {
                BlockPos previousCrack = lastCrackBlock.get(player.getUniqueId());
                if (previousCrack != null && !previousCrack.equals(pos)) {
                    // Reset le crack sur le bloc précédent
                    player.sendBlockDamage(
                            new Location(world, previousCrack.x(), previousCrack.y(), previousCrack.z()),
                            0.0f
                    );
                }
                // Mapper HP% vers stage 0.0-1.0
                double pct = tree.getHealth() / tree.getMaxHealth();
                float crackProgress = (float) (1.0 - pct);
                crackProgress = Math.max(0.0f, Math.min(1.0f, crackProgress));
                player.sendBlockDamage(
                        new Location(world, pos.x(), pos.y(), pos.z()),
                        crackProgress
                );
                lastCrackBlock.put(player.getUniqueId(), pos);
            }
        }
    }

    private void handleScanFailure(Player player, String reason) {
        if (reason == null) return;
        switch (reason) {
            case "tree_too_large"      -> player.sendMessage(configManager.getMessage("tree_too_large", true));
            case "tree_too_large_6bis" -> player.sendMessage(configManager.getMessage("tree_too_large", true));
            case "has_blacklist"       -> player.sendMessage(configManager.getMessage("has_blacklist", true));
            case "not_rooted"          -> player.sendMessage(configManager.getMessage("not_rooted", true));
            case "min_limits_not_met"  -> player.sendMessage(configManager.getMessage("min_limits_not_met", true));
            case "biome_disabled"      -> player.sendMessage(configManager.getMessage("biome_disabled", true));
            case "tree_too_fused"      -> player.sendMessage(configManager.getMessage("tree_too_fused", true));
            case "not_a_tree"          -> player.sendMessage(configManager.getMessage("not_an_tree", true));
            // default : raison inconnue → pas de message (évite le spam)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Suppression des blocs lors de feu/explosions
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        removeBlockFromActiveTree(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) removeBlockFromActiveTree(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) removeBlockFromActiveTree(b);
    }

    private void removeBlockFromActiveTree(Block block) {
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        ActiveTree tree = treeManager.getTreeAt(block.getWorld(), pos);
        if (tree != null) {
            tree.getLogs().remove(pos);
            tree.getLeaves().remove(pos);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Utilitaires
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isAxe(Material mat) {
        return mat.name().endsWith("_AXE");
    }

    private void applyDurabilityLoss(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return;
        if (!configManager.isExtraLossEnabled()) return;
        if (!isAxe(item.getType())) return;
        if (!(item.getItemMeta() instanceof Damageable dmg)) return;

        int unbreakingLevel = item.getEnchantmentLevel(Enchantment.UNBREAKING);
        // Probabilité de perdre de la durabilité (avec Unbreaking)
        double chance = 1.0 / (unbreakingLevel + 1.0);

        if (Math.random() < chance) {
            int extraLoss = configManager.getExtraLossPoints();
            dmg.setDamage(dmg.getDamage() + extraLoss);
            item.setItemMeta((ItemMeta) dmg);

            if (dmg.getDamage() >= item.getType().getMaxDurability()) {
                player.getInventory().setItemInMainHand(null);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            }
        }
    }

    private Map<Long, ChunkSnapshot> fetchSnapshotsGrid(World world, int blockX, int blockZ) {
        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        int centerChunkX = blockX >> 4;
        int centerChunkZ = blockZ >> 4;

        // Grille de ±4 chunks pour couvrir le rayon élargi (pour les très grands arbres)
        for (int cx = centerChunkX - 4; cx <= centerChunkX + 4; cx++) {
            for (int cz = centerChunkZ - 4; cz <= centerChunkZ + 4; cz++) {
                if (world.isChunkLoaded(cx, cz)) {
                    ChunkSnapshot snapshot = world.getChunkAt(cx, cz).getChunkSnapshot(false, true, false);
                    long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                    snapshots.put(key, snapshot);
                }
            }
        }
        return snapshots;
    }

    @org.bukkit.event.EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        java.util.UUID uuid = event.getPlayer().getUniqueId();
        hintCooldowns.remove(uuid);
        lastClickTime.remove(uuid);
        lastCrackBlock.remove(uuid);
        antiCheatTracker.cleanup(uuid);
        ConfigGUI.chatSessions.remove(uuid);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConfigGUI gui)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        ItemStack clickedItem = event.getCurrentItem();
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;

        ConfigGUI.MenuType menuType = gui.getMenuType();
        ConfigManager cm = configManager;
        FileConfiguration cfg = cm.getConfig();
        FileConfiguration bcfg = cm.getBiomesConfig();
        String biomeName = gui.getBiomeName();
        String listType = gui.getListType();

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);

        switch (menuType) {
            case MAIN_MENU:
                if (slot == 11) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_CATEGORIES, null, null, 0).getInventory());
                } else if (slot == 13) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_LIST, null, null, 0).getInventory());
                } else if (slot == 15) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.QUICK_TOGGLES, null, null, 0).getInventory());
                } else if (slot == 31) {
                    player.closeInventory();
                    plugin.getServer().dispatchCommand(player, "wt reload");
                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.0f);
                } else if (slot == 40) {
                    player.closeInventory();
                }
                break;

            case QUICK_TOGGLES:
                if (slot == 11) {
                    cm.setDebug(!cm.isDebug());
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.QUICK_TOGGLES, null, null, 0).getInventory());
                } else if (slot == 13) {
                    boolean val = !cm.isBlacklistEnabled();
                    cm.setBlacklistEnabled(val);
                    cfg.set("limits.blacklist-enabled", val);
                    cm.saveConfig();
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.QUICK_TOGGLES, null, null, 0).getInventory());
                } else if (slot == 15) {
                    boolean val = !cm.isTreeContactRequired();
                    cm.setTreeContactRequired(val);
                    cfg.set("limits.tree-contact-required", val);
                    cm.saveConfig();
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.QUICK_TOGGLES, null, null, 0).getInventory());
                } else if (slot == 29) {
                    treeManager.togglePlayerGodMode(player.getUniqueId());
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.QUICK_TOGGLES, null, null, 0).getInventory());
                } else if (slot == 40) {
                    ConfigGUI.openMainMenu(plugin, player);
                }
                break;

            case GLOBAL_CONFIG_CATEGORIES:
                if (slot == 10) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_GENERAL, null, null, 0).getInventory());
                } else if (slot == 12) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_LIMITS, null, null, 0).getInventory());
                } else if (slot == 14) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_DECAY, null, null, 0).getInventory());
                } else if (slot == 16) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_ROOTS, null, null, 0).getInventory());
                } else if (slot == 28) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_FALLBACK, null, null, 0).getInventory());
                } else if (slot == 40) {
                    ConfigGUI.openMainMenu(plugin, player);
                }
                break;

            case GLOBAL_CONFIG_GENERAL:
                if (slot == 10) {
                    startChatSession(player, "config", "trigger.click-cooldown-ms", "click-cooldown-ms", ConfigGUI.MenuType.GLOBAL_CONFIG_GENERAL);
                } else if (slot == 11) {
                    startChatSession(player, "config", "trigger.hint-cooldown-seconds", "hint-cooldown-seconds", ConfigGUI.MenuType.GLOBAL_CONFIG_GENERAL);
                } else if (slot == 12) {
                    boolean val = !cfg.getBoolean("trigger.send-hint-message", true);
                    cfg.set("trigger.send-hint-message", val);
                    cm.saveConfig();
                    cm.load();
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_GENERAL, null, null, 0).getInventory());
                } else if (slot == 13) {
                    boolean val = !cfg.getBoolean("durability.extra-loss.enabled", true);
                    cfg.set("durability.extra-loss.enabled", val);
                    cm.saveConfig();
                    cm.load();
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_GENERAL, null, null, 0).getInventory());
                } else if (slot == 14) {
                    startChatSession(player, "config", "durability.extra-loss.points-per-interval", "extra-loss.points-per-interval", ConfigGUI.MenuType.GLOBAL_CONFIG_GENERAL);
                } else if (slot == 40) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_CATEGORIES, null, null, 0).getInventory());
                }
                break;

            case GLOBAL_CONFIG_LIMITS:
                if (slot == 10) {
                    startChatSession(player, "config", "limits.max-logs", "max-logs", ConfigGUI.MenuType.GLOBAL_CONFIG_LIMITS);
                } else if (slot == 11) {
                    startChatSession(player, "config", "limits.max-blocks", "max-blocks", ConfigGUI.MenuType.GLOBAL_CONFIG_LIMITS);
                } else if (slot == 12) {
                    startChatSession(player, "config", "limits.max-radius-xz", "max-radius-xz", ConfigGUI.MenuType.GLOBAL_CONFIG_LIMITS);
                } else if (slot == 13) {
                    startChatSession(player, "config", "limits.max-height-y", "max-height-y", ConfigGUI.MenuType.GLOBAL_CONFIG_LIMITS);
                } else if (slot == 14) {
                    startChatSession(player, "config", "limits.6way-max-logs", "6way-max-logs", ConfigGUI.MenuType.GLOBAL_CONFIG_LIMITS);
                } else if (slot == 40) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_CATEGORIES, null, null, 0).getInventory());
                }
                break;

            case GLOBAL_CONFIG_DECAY:
                if (slot == 10) {
                    startChatSession(player, "config", "limits.leaf-decay-range-xz", "leaf-decay-range-xz", ConfigGUI.MenuType.GLOBAL_CONFIG_DECAY);
                } else if (slot == 11) {
                    startChatSession(player, "config", "limits.leaf-decay-range-y", "leaf-decay-range-y", ConfigGUI.MenuType.GLOBAL_CONFIG_DECAY);
                } else if (slot == 12) {
                    boolean val = !cfg.getBoolean("limits.allow-diagonal-leaves", true);
                    cfg.set("limits.allow-diagonal-leaves", val);
                    cm.saveConfig();
                    cm.load();
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_DECAY, null, null, 0).getInventory());
                } else if (slot == 13) {
                    boolean val = !cfg.getBoolean("limits.canopy-cleanup-enabled", true);
                    cfg.set("limits.canopy-cleanup-enabled", val);
                    cm.saveConfig();
                    cm.load();
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_DECAY, null, null, 0).getInventory());
                } else if (slot == 14) {
                    startChatSession(player, "config", "limits.canopy-cleanup-padding", "canopy-cleanup-padding", ConfigGUI.MenuType.GLOBAL_CONFIG_DECAY);
                } else if (slot == 15) {
                    startChatSession(player, "config", "limits.leaves-persistence-batch-size", "leaves-persistence-batch-size", ConfigGUI.MenuType.GLOBAL_CONFIG_DECAY);
                } else if (slot == 40) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_CATEGORIES, null, null, 0).getInventory());
                }
                break;

            case GLOBAL_CONFIG_ROOTS:
                if (slot == 10) {
                    startChatSession(player, "config", "roots.root-fill-padding", "root-fill-padding", ConfigGUI.MenuType.GLOBAL_CONFIG_ROOTS);
                } else if (slot == 11) {
                    startChatSession(player, "config", "roots.root-fill-depth-padding", "root-fill-depth-padding", ConfigGUI.MenuType.GLOBAL_CONFIG_ROOTS);
                } else if (slot == 12) {
                    boolean val = !cfg.getBoolean("limits.root-replacement.enabled", true);
                    cfg.set("limits.root-replacement.enabled", val);
                    cm.saveConfig();
                    cm.load();
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_ROOTS, null, null, 0).getInventory());
                } else if (slot == 13) {
                    if (event.isShiftClick()) {
                        ItemStack hand = player.getInventory().getItemInMainHand();
                        if (hand != null && hand.getType().isBlock()) {
                            cfg.set("limits.root-replacement.material", hand.getType().name());
                            cm.saveConfig();
                            cm.load();
                            player.sendMessage("§aMatériau de remplacement mis à jour : " + hand.getType().name());
                            player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_ROOTS, null, null, 0).getInventory());
                        } else {
                            player.sendMessage("§cVous devez tenir un bloc dans votre main !");
                        }
                    } else {
                        startChatSession(player, "config", "limits.root-replacement.material", "root-replacement.material", ConfigGUI.MenuType.GLOBAL_CONFIG_ROOTS);
                    }
                } else if (slot == 40) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_CATEGORIES, null, null, 0).getInventory());
                }
                break;

            case GLOBAL_CONFIG_FALLBACK:
                if (slot == 10) {
                    boolean val = !cfg.getBoolean("fallback.enabled", true);
                    cfg.set("fallback.enabled", val);
                    cm.saveConfig();
                    cm.load();
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_FALLBACK, null, null, 0).getInventory());
                } else if (slot == 11) {
                    startChatSession(player, "config", "fallback.max-blocks", "fallback.max-blocks", ConfigGUI.MenuType.GLOBAL_CONFIG_FALLBACK);
                } else if (slot == 12) {
                    startChatSession(player, "config", "fallback.trunk-core-radius", "fallback.trunk-core-radius", ConfigGUI.MenuType.GLOBAL_CONFIG_FALLBACK);
                } else if (slot == 13) {
                    startChatSession(player, "config", "fallback.trunk-min-height", "fallback.trunk-min-height", ConfigGUI.MenuType.GLOBAL_CONFIG_FALLBACK);
                } else if (slot == 14) {
                    startChatSession(player, "config", "fallback.max-radius", "fallback.max-radius", ConfigGUI.MenuType.GLOBAL_CONFIG_FALLBACK);
                } else if (slot == 15) {
                    startChatSession(player, "config", "fallback.min-density", "fallback.min-density", ConfigGUI.MenuType.GLOBAL_CONFIG_FALLBACK);
                } else if (slot == 40) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.GLOBAL_CONFIG_CATEGORIES, null, null, 0).getInventory());
                }
                break;

            case BIOME_LIST:
                if (slot == 45) {
                    ConfigGUI.openMainMenu(plugin, player);
                } else if (slot == 48 && gui.getPage() > 0) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_LIST, null, null, gui.getPage() - 1).getInventory());
                } else if (slot == 50) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_LIST, null, null, gui.getPage() + 1).getInventory());
                } else if (slot == 49) {
                    // Création de biome
                    Biome currentBiome = player.getLocation().getBlock().getBiome();
                    String cbName = currentBiome.name().toUpperCase();
                    if (cbName.contains(":")) cbName = cbName.substring(cbName.indexOf(':') + 1);
                    if (bcfg.contains(cbName)) {
                        player.sendMessage(configManager.getMessage("gui_biome_already_exists", true));
                    } else {
                        ConfigurationSection defSection = bcfg.getConfigurationSection("DEFAULT");
                        if (defSection != null) {
                            for (String k : defSection.getKeys(true)) {
                                bcfg.set(cbName + "." + k, defSection.get(k));
                            }
                        } else {
                            bcfg.set(cbName + ".enabled", true);
                            bcfg.set(cbName + ".min-logs", 2);
                            bcfg.set(cbName + ".min-leaf-like", 3);
                            bcfg.set(cbName + ".log-blocks", Arrays.asList("OAK_LOG"));
                            bcfg.set(cbName + ".leaf-blocks", Arrays.asList("OAK_LEAVES"));
                        }
                        cm.saveBiomes();
                        cm.load();
                        player.sendMessage(configManager.getMessage("gui_biome_created", true).replace("{name}", cbName));
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f);
                        player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_LIST, null, null, 0).getInventory());
                    }
                } else {
                    if (clickedItem.getType() == Material.MAP) {
                        String bName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName()).replace("Biome : ", "").trim();
                        if (event.isShiftClick() && event.isRightClick()) {
                            // Supprimer biome
                            bcfg.set(bName, null);
                            cm.saveBiomes();
                            cm.load();
                            player.sendMessage(configManager.getMessage("gui_biome_deleted", true).replace("{name}", bName));
                            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_BURN, 0.8f, 1.0f);
                            player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_LIST, null, null, gui.getPage()).getInventory());
                        } else {
                            player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_EDITOR, bName, null, 0).getInventory());
                        }
                    }
                }
                break;

            case BIOME_EDITOR:
                if (slot == 10) {
                    boolean val = !bcfg.getBoolean(biomeName + ".enabled", true);
                    bcfg.set(biomeName + ".enabled", val);
                    cm.saveBiomes();
                    cm.load();
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null, 0).getInventory());
                } else if (slot == 11) {
                    startChatSession(player, "biomes", biomeName + ".min-logs", "min-logs", ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null);
                } else if (slot == 12) {
                    startChatSession(player, "biomes", biomeName + ".min-leaf-like", "min-leaf-like", ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null);
                } else if (slot == 13) {
                    if (event.isRightClick()) {
                        bcfg.set(biomeName + ".max-logs", null);
                        cm.saveBiomes();
                        cm.load();
                        player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null, 0).getInventory());
                    } else {
                        startChatSession(player, "biomes", biomeName + ".max-logs", "max-logs", ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null);
                    }
                } else if (slot == 14) {
                    if (event.isRightClick()) {
                        bcfg.set(biomeName + ".max-blocks", null);
                        cm.saveBiomes();
                        cm.load();
                        player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null, 0).getInventory());
                    } else {
                        startChatSession(player, "biomes", biomeName + ".max-blocks", "max-blocks", ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null);
                    }
                } else if (slot == 15) {
                    if (event.isRightClick()) {
                        bcfg.set(biomeName + ".max-radius-xz", null);
                        cm.saveBiomes();
                        cm.load();
                        player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null, 0).getInventory());
                    } else {
                        startChatSession(player, "biomes", biomeName + ".max-radius-xz", "max-radius-xz", ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null);
                    }
                } else if (slot == 16) {
                    if (event.isRightClick()) {
                        bcfg.set(biomeName + ".max-height-y", null);
                        cm.saveBiomes();
                        cm.load();
                        player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null, 0).getInventory());
                    } else {
                        startChatSession(player, "biomes", biomeName + ".max-height-y", "max-height-y", ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null);
                    }
                } else if (slot == 19) {
                    startChatSession(player, "biomes", biomeName + ".protection-belt-radius", "protection-belt-radius", ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null);
                } else if (slot == 20) {
                    startChatSession(player, "biomes", biomeName + ".canopy-cleanup-padding", "canopy-cleanup-padding", ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null);
                } else if (slot == 21) {
                    boolean val = !bcfg.getBoolean(biomeName + ".root-replacement-enabled", true);
                    bcfg.set(biomeName + ".root-replacement-enabled", val);
                    cm.saveBiomes();
                    cm.load();
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null, 0).getInventory());
                } else if (slot == 22) {
                    if (event.isShiftClick()) {
                        ItemStack hand = player.getInventory().getItemInMainHand();
                        if (hand != null && hand.getType().isBlock()) {
                            bcfg.set(biomeName + ".root-replacement-material", hand.getType().name());
                            cm.saveBiomes();
                            cm.load();
                            player.sendMessage("§aMatériau de remplacement mis à jour : " + hand.getType().name());
                            player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null, 0).getInventory());
                        } else {
                            player.sendMessage("§cVous devez tenir un bloc dans votre main !");
                        }
                    } else {
                        startChatSession(player, "biomes", biomeName + ".root-replacement-material", "root-replacement-material", ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null);
                    }
                } else if (slot == 24 || slot == 25 || slot == 26) {
                    String type = slot == 24 ? "log-blocks" : (slot == 25 ? "leaf-blocks" : "attachments");
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.MATERIAL_LIST_EDITOR, biomeName, type, 0).getInventory());
                } else if (slot == 40) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_LIST, null, null, 0).getInventory());
                } else if (slot == 44) {
                    if (event.isShiftClick()) {
                        bcfg.set(biomeName, null);
                        cm.saveBiomes();
                        cm.load();
                        player.sendMessage("§aBiome " + biomeName + " supprimé.");
                        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_BURN, 0.8f, 1.0f);
                        player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_LIST, null, null, 0).getInventory());
                    }
                }
                break;

            case MATERIAL_LIST_EDITOR:
                if (slot == 45) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.BIOME_EDITOR, biomeName, null, 0).getInventory());
                } else if (slot == 48 && gui.getPage() > 0) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.MATERIAL_LIST_EDITOR, biomeName, listType, gui.getPage() - 1).getInventory());
                } else if (slot == 51) {
                    player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.MATERIAL_LIST_EDITOR, biomeName, listType, gui.getPage() + 1).getInventory());
                } else if (slot == 49) {
                    // Ajouter l'item en main
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    if (hand != null && hand.getType().isBlock()) {
                        List<String> list = bcfg.getStringList(biomeName + "." + listType);
                        if (!list.contains(hand.getType().name())) {
                            list.add(hand.getType().name());
                            bcfg.set(biomeName + "." + listType, list);
                            cm.saveBiomes();
                            cm.load();
                            player.sendMessage("§aMatériau " + hand.getType().name() + " ajouté à la liste.");
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f);
                        } else {
                            player.sendMessage("§cCe matériau est déjà présent.");
                        }
                        player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.MATERIAL_LIST_EDITOR, biomeName, listType, gui.getPage()).getInventory());
                    } else {
                        player.sendMessage("§cVous devez tenir un bloc dans votre main !");
                    }
                } else if (slot == 50) {
                    // Ajouter par nom
                    startChatSession(player, "biomes", biomeName + "." + listType, listType, ConfigGUI.MenuType.MATERIAL_LIST_EDITOR, biomeName, listType);
                } else {
                    if (clickedItem.getType() != Material.GRAY_STAINED_GLASS_PANE && slot < 45) {
                        String matName = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName()).trim();
                        List<String> list = bcfg.getStringList(biomeName + "." + listType);
                        if (list.remove(matName)) {
                            bcfg.set(biomeName + "." + listType, list);
                            cm.saveBiomes();
                            cm.load();
                            player.sendMessage("§aMatériau " + matName + " retiré de la liste.");
                            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_BREAK, 0.8f, 1.0f);
                        }
                        player.openInventory(new ConfigGUI(plugin, player, ConfigGUI.MenuType.MATERIAL_LIST_EDITOR, biomeName, listType, gui.getPage()).getInventory());
                    }
                }
                break;
        }
    }

    private void startChatSession(Player player, String file, String key, String displayName, ConfigGUI.MenuType returnMenu) {
        startChatSession(player, file, key, displayName, returnMenu, null, null);
    }

    private void startChatSession(Player player, String file, String key, String displayName, ConfigGUI.MenuType returnMenu, String biomeName, String listType) {
        ConfigGUI.chatSessions.put(player.getUniqueId(), new ConfigGUI.ChatInputSession(file, key, displayName, returnMenu, biomeName, listType));
        player.closeInventory();
        player.sendMessage(configManager.getMessage("gui_prompt", false).replace("{name}", displayName));
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        ConfigGUI.ChatInputSession session = ConfigGUI.chatSessions.get(player.getUniqueId());
        if (session == null) return;

        event.setCancelled(true);
        String input = event.getMessage().trim();

        // Run synchronously to safely use Bukkit API and reopen inventory
        Bukkit.getScheduler().runTask(plugin, () -> {
            ConfigGUI.chatSessions.remove(player.getUniqueId());

            if (input.equalsIgnoreCase("cancel")) {
                player.sendMessage(configManager.getMessage("gui_cancelled", true));
                reopenPreviousMenu(player, session);
                return;
            }

            try {
                if (session.listType != null) {
                    // C'est un ajout de matériau dans une liste
                    Material mat = Material.matchMaterial(input.toUpperCase());
                    if (mat == null) {
                        player.sendMessage(configManager.getMessage("gui_unknown_material", true).replace("{material}", input));
                        reopenPreviousMenu(player, session);
                        return;
                    }
                    List<String> list = configManager.getBiomesConfig().getStringList(session.key);
                    if (!list.contains(mat.name())) {
                        list.add(mat.name());
                        configManager.getBiomesConfig().set(session.key, list);
                        configManager.saveBiomes();
                        configManager.load();
                        player.sendMessage(configManager.getMessage("gui_material_added", true).replace("{material}", mat.name()));
                    } else {
                        player.sendMessage(configManager.getMessage("gui_material_already_present", true));
                    }
                    reopenPreviousMenu(player, session);
                    return;
                }

                // C'est un paramètre classique (config globale ou biome)
                String keyLower = session.key.toLowerCase();
                Object val;
                if (keyLower.contains("material")) {
                    Material mat = Material.matchMaterial(input.toUpperCase());
                    if (mat == null) {
                        player.sendMessage(configManager.getMessage("gui_unknown_material", true).replace("{material}", input));
                        reopenPreviousMenu(player, session);
                        return;
                    }
                    val = mat.name();
                } else if (keyLower.contains("cooldown-ms")) {
                    val = Long.parseLong(input);
                } else if (keyLower.contains("coefficient") || keyLower.contains("percent")
                        || keyLower.contains("chance") || keyLower.contains("multiplier")
                        || keyLower.contains("density") || keyLower.contains("alpha")
                        || keyLower.contains("factor")) {
                    val = Double.parseDouble(input);
                } else {
                    val = Integer.parseInt(input);
                }

                if (session.file.equals("config")) {
                    configManager.getConfig().set(session.key, val);
                    configManager.saveConfig();
                } else {
                    configManager.getBiomesConfig().set(session.key, val);
                    configManager.saveBiomes();
                }

                configManager.load();
                player.sendMessage(configManager.getMessage("gui_value_updated", true)
                        .replace("{name}", session.displayName)
                        .replace("{value}", String.valueOf(val)));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.0f);

            } catch (NumberFormatException e) {
                player.sendMessage(configManager.getMessage("gui_invalid_number", true));
            }

            reopenPreviousMenu(player, session);
        });
    }

    private void reopenPreviousMenu(Player player, ConfigGUI.ChatInputSession session) {
        ConfigGUI gui = new ConfigGUI(plugin, player, session.returnMenu, session.biomeName, session.listType, 0);
        player.openInventory(gui.getInventory());
        player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1.0f);
    }
}
