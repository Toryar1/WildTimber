package com.wildtimber;

import com.wildtimber.config.ConfigManager;
import com.wildtimber.detection.TreeDetector;
import com.wildtimber.felling.CutJobManager;
import com.wildtimber.felling.TreeFeller;
import com.wildtimber.hook.WildTimberExpansion;
import com.wildtimber.listener.BlockListener;
import com.wildtimber.manager.TreeManager;
import com.wildtimber.protection.SimulatedProtectionHook;
import com.wildtimber.protection.ProtectionHook;
import com.wildtimber.gui.ConfigGUI;
import com.wildtimber.update.UpdateChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Classe principale du plugin WildTimber.
 */
public final class WildTimber extends JavaPlugin implements CommandExecutor, TabCompleter {

    private ConfigManager configManager;
    private TreeManager treeManager;
    private TreeDetector treeDetector;
    private TreeFeller treeFeller;
    private CutJobManager cutJobManager;
    private ProtectionHook protectionHook;
    private UpdateChecker updateChecker;

    @Override
    public void onEnable() {
        // 1. Initialisation de la configuration
        configManager = new ConfigManager(this);
        configManager.load();

        // 2. Initialisation des managers et hooks
        treeManager = new TreeManager(this);
        treeDetector = new TreeDetector(this);
        treeFeller = new TreeFeller(this);
        cutJobManager = new CutJobManager(this);

        // Hook de protection universel (simule un BlockBreakEvent pour compatibilité
        // WorldGuard, GriefPrevention, Lands, Towny, Factions, Residence, etc.)
        protectionHook = new SimulatedProtectionHook();

        // 3. Enregistrement des événements
        getServer().getPluginManager().registerEvents(new BlockListener(this), this);

        // 4. Démarrage des tâches périodiques
        treeManager.startRegenTask();

        // 5. Enregistrement des commandes
        org.bukkit.command.PluginCommand cmd = getCommand("wildtimber");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // 6. Vérificateur de mise à jour SpigotMC (asynchrone)
        // Remplacez 0 par l'ID de votre ressource SpigotMC une fois publiée
        int spigotResourceId = 0;
        updateChecker = new UpdateChecker(this, spigotResourceId);
        if (spigotResourceId > 0) {
            updateChecker.checkUpdate(version ->
                getLogger().info("[UpdateChecker] Nouvelle version disponible sur SpigotMC : " + version)
            );
            getServer().getPluginManager().registerEvents(updateChecker, this);
        }

        // 7. Intégration PlaceholderAPI (optionnel)
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new WildTimberExpansion(this).register();
            getLogger().info("[WildTimber] PlaceholderAPI détecté — placeholders enregistrés.");
        }

        getLogger().info("[WildTimber] Langue active : " + configManager.getLanguage());
        getLogger().info("========================================");
        getLogger().info("   WildTimber v" + getDescription().getVersion() + " chargé avec succès !");
        getLogger().info("   Langue : " + configManager.getLanguage().toUpperCase());
        getLogger().info("========================================");
    }

    @Override
    public void onDisable() {
        // Annuler les coupes actives et en queue
        int active = 0, queued = 0;
        if (cutJobManager != null) {
            active = cutJobManager.hasActiveJob() ? 1 : 0;
            queued = cutJobManager.getQueueSize();
            cutJobManager.cancelAll();
        }
        // Nettoyage complet (BossBars, tâches, etc.)
        if (treeManager != null) {
            treeManager.cleanupAll();
        }
        getLogger().info("WildTimber desactive. Jobs annulés: " + active + " actif(s), " + queued + " en queue.");
    }

    // Getters

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public TreeManager getTreeManager() {
        return treeManager;
    }

    public TreeDetector getTreeDetector() {
        return treeDetector;
    }

    public TreeFeller getTreeFeller() {
        return treeFeller;
    }

    public CutJobManager getCutJobManager() {
        return cutJobManager;
    }

    public ProtectionHook getProtectionHook() {
        return protectionHook;
    }

    public void setProtectionHook(ProtectionHook protectionHook) {
        this.protectionHook = protectionHook;
    }

    // Gestion des Commandes

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // Par défaut, si c'est un joueur, on ouvre le GUI de configuration
            if (sender instanceof org.bukkit.entity.Player player) {
                if (!player.hasPermission("wildtimber.admin.gui")) {
                    player.sendMessage(configManager.getMessage("no_permission", true));
                    return true;
                }
                ConfigGUI.openMainMenu(this, player);
                return true;
            }
            sender.sendMessage(configManager.getMessage("prefix", false) + "§eUtilise §6/wt gui§e, §6/wt reload§e, §6/wt debug§e, etc.");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("gui")) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(configManager.getMessage("only_players", true));
                return true;
            }
            if (!player.hasPermission("wildtimber.admin.gui")) {
                player.sendMessage(configManager.getMessage("no_permission", true));
                return true;
            }
            ConfigGUI.openMainMenu(this, player);
            return true;
        }

        if (subCommand.equals("reload")) {
            if (!sender.hasPermission("wildtimber.admin.reload")) {
                sender.sendMessage(configManager.getMessage("no_permission", true));
                return true;
            }
            treeManager.cleanupAll();
            cutJobManager.cancelAll();
            configManager.load();
            treeManager.startRegenTask();
            sender.sendMessage(configManager.getMessage("reload_success", true));
            return true;
        }

        if (subCommand.equals("debug")) {
            if (!sender.hasPermission("wildtimber.admin.debug")) {
                sender.sendMessage(configManager.getMessage("no_permission", true));
                return true;
            }
            boolean newState = !configManager.isDebug();
            configManager.setDebug(newState);
            sender.sendMessage(configManager.getMessage("debug_mode_toggle", true)
                    .replace("{state}", newState ? configManager.getMessage("state_enabled", false) : configManager.getMessage("state_disabled", false)));
            return true;
        }

        if (subCommand.equals("blacklist") || subCommand.equals("protection")) {
            if (!sender.hasPermission("wildtimber.admin.blacklist")) {
                sender.sendMessage(configManager.getMessage("no_permission", true));
                return true;
            }
            boolean newState = !configManager.isBlacklistEnabled();
            configManager.setBlacklistEnabled(newState);
            configManager.getConfig().set("limits.blacklist-enabled", newState);
            configManager.saveConfig();
            sender.sendMessage(configManager.getMessage("blacklist_toggle", true)
                    .replace("{state}", newState ? configManager.getMessage("state_enabled", false) : configManager.getMessage("state_disabled", false)));
            return true;
        }

        if (subCommand.equals("treecontact") || subCommand.equals("groundcontact")) {
            if (!sender.hasPermission("wildtimber.admin.treecontact")) {
                sender.sendMessage(configManager.getMessage("no_permission", true));
                return true;
            }
            boolean newState = !configManager.isTreeContactRequired();
            configManager.setTreeContactRequired(newState);
            configManager.getConfig().set("limits.tree-contact-required", newState);
            configManager.saveConfig();
            sender.sendMessage(configManager.getMessage("treecontact_toggle", true)
                    .replace("{state}", newState ? configManager.getMessage("state_enabled", false) : configManager.getMessage("state_disabled", false)));
            return true;
        }

        if (subCommand.equals("godmode") || subCommand.equals("god")) {
            if (!sender.hasPermission("wildtimber.admin.godmode") && !sender.hasPermission("wildtimber.godmode")) {
                sender.sendMessage(configManager.getMessage("no_permission", true));
                return true;
            }
            org.bukkit.entity.Player target;
            if (args.length > 1) {
                target = org.bukkit.Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(configManager.getMessage("player_not_found", true));
                    return true;
                }
            } else {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(configManager.getMessage("specify_player", true));
                    return true;
                }
                target = player;
            }
            boolean active = treeManager.togglePlayerGodMode(target.getUniqueId());
            if (active) {
                target.sendMessage(configManager.getMessage("godmode_on", true));
                if (target != sender) sender.sendMessage(configManager.getMessage("godmode_target_on", true).replace("{player}", target.getName()));
            } else {
                target.sendMessage(configManager.getMessage("godmode_off", true));
                if (target != sender) sender.sendMessage(configManager.getMessage("godmode_target_off", true).replace("{player}", target.getName()));
            }
            return true;
        }

        if (subCommand.equals("undo")) {
            if (!sender.hasPermission("wildtimber.admin.undo")) {
                sender.sendMessage(configManager.getMessage("no_permission", true));
                return true;
            }
            org.bukkit.entity.Player target;
            if (args.length > 1) {
                target = org.bukkit.Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(configManager.getMessage("player_not_found", true));
                    return true;
                }
            } else {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(configManager.getMessage("specify_player", true));
                    return true;
                }
                target = player;
            }
            com.wildtimber.manager.UndoSnapshot snapshot = treeManager.popUndoSnapshot(target.getUniqueId());
            if (snapshot != null) {
                snapshot.restore();
                int size = snapshot.blocks().size();
                target.sendMessage(configManager.getMessage("undo_success", true)
                        .replace("{blocks}", String.valueOf(size)));
                if (target != sender) {
                    sender.sendMessage(configManager.getMessage("undo_target_success", true)
                            .replace("{player}", target.getName())
                            .replace("{blocks}", String.valueOf(size)));
                }
            } else {
                sender.sendMessage(configManager.getMessage("undo_nothing", true));
            }
            return true;
        }

        if (subCommand.equals("toggle")) {
            org.bukkit.entity.Player target;
            if (args.length > 1) {
                if (!sender.hasPermission("wildtimber.admin")) {
                    sender.sendMessage(configManager.getMessage("no_permission", true));
                    return true;
                }
                target = org.bukkit.Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(configManager.getMessage("player_not_found", true));
                    return true;
                }
            } else {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage(configManager.getMessage("specify_player", true));
                    return true;
                }
                if (!player.hasPermission("wildtimber.toggle")) {
                    player.sendMessage(configManager.getMessage("no_permission", true));
                    return true;
                }
                target = player;
            }
            boolean active = treeManager.togglePlayer(target.getUniqueId());
            if (active) {
                target.sendMessage(configManager.getMessage("toggle_on", true));
                if (target != sender) sender.sendMessage(configManager.getMessage("toggle_target_on", true).replace("{player}", target.getName()));
            } else {
                target.sendMessage(configManager.getMessage("toggle_off", true));
                if (target != sender) sender.sendMessage(configManager.getMessage("toggle_target_off", true).replace("{player}", target.getName()));
            }
            return true;
        }

        // ── Commande : language ──
        if (subCommand.equals("language") || subCommand.equals("lang")) {
            if (!sender.hasPermission("wildtimber.admin.reload")) {
                sender.sendMessage(configManager.getMessage("no_permission", true));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(configManager.getMessage("prefix", false) + "§eUsage : §6/wt language <fr|en|es|de|zh>");
                return true;
            }
            String lang = args[1].toLowerCase();
            configManager.setLanguage(lang);
            sender.sendMessage(configManager.getMessage("language_changed", true).replace("{lang}", lang.toUpperCase()));
            return true;
        }

        // ── Commande : info ──
        if (subCommand.equals("info")) {
            if (!sender.hasPermission("wildtimber.admin")) {
                sender.sendMessage(configManager.getMessage("no_permission", true));
                return true;
            }
            String papi = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") ? "§aOui" : "§cNon";
            sender.sendMessage("§2§l◈ WildTimber §r§7— Informations");
            sender.sendMessage("§7 Version     : §f" + getDescription().getVersion());
            sender.sendMessage("§7 Auteur      : §f" + getDescription().getAuthors());
            sender.sendMessage("§7 Langue      : §f" + configManager.getLanguage().toUpperCase());
            sender.sendMessage("§7 Arbres actifs : §f" + treeManager.getActiveTrees().size());
            sender.sendMessage("§7 PlaceholderAPI : " + papi);
            sender.sendMessage("§7 Statut      : " + (configManager.isPluginEnabled() ? "§aACTIF" : "§cDÉSACTIVÉ"));
            return true;
        }

        // ── Commande : help ──
        if (subCommand.equals("help")) {
            sender.sendMessage("§2§l╔══ WildTimber — Aide ══╗");
            if (sender.hasPermission("wildtimber.admin.gui"))     sender.sendMessage("§6 /wt gui              §7→ Ouvre l'interface de configuration");
            if (sender.hasPermission("wildtimber.admin.reload"))  sender.sendMessage("§6 /wt reload            §7→ Recharge la configuration");
            if (sender.hasPermission("wildtimber.admin.reload"))  sender.sendMessage("§6 /wt language <code>   §7→ Change la langue (fr, en, es, de, zh)");
            if (sender.hasPermission("wildtimber.admin.debug"))   sender.sendMessage("§6 /wt debug             §7→ Active/désactive le mode debug");
            if (sender.hasPermission("wildtimber.admin.blacklist")) sender.sendMessage("§6 /wt blacklist         §7→ Toggle protection blocs construction");
            if (sender.hasPermission("wildtimber.admin.treecontact")) sender.sendMessage("§6 /wt treecontact       §7→ Toggle exigence contact au sol");
            if (sender.hasPermission("wildtimber.admin.godmode")) sender.sendMessage("§6 /wt godmode [joueur]  §7→ Active/désactive le godmode");
            if (sender.hasPermission("wildtimber.admin.undo"))    sender.sendMessage("§6 /wt undo [joueur]     §7→ Annule le dernier abattage");
            if (sender.hasPermission("wildtimber.toggle"))        sender.sendMessage("§6 /wt toggle [joueur]   §7→ Active/désactive WildTimber pour soi");
            if (sender.hasPermission("wildtimber.admin"))         sender.sendMessage("§6 /wt info              §7→ Informations sur le plugin");
            sender.sendMessage("§2§l╚═══════════════════════╝");
            return true;
        }

        sender.sendMessage(configManager.getMessage("unknown_command", true));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add("help");
            if (sender.hasPermission("wildtimber.admin.gui"))      suggestions.add("gui");
            if (sender.hasPermission("wildtimber.admin.reload")) {
                suggestions.add("reload");
                suggestions.add("language");
            }
            if (sender.hasPermission("wildtimber.admin.debug"))    suggestions.add("debug");
            if (sender.hasPermission("wildtimber.admin.blacklist")) { suggestions.add("blacklist"); suggestions.add("protection"); }
            if (sender.hasPermission("wildtimber.admin.treecontact")) suggestions.add("treecontact");
            if (sender.hasPermission("wildtimber.admin.godmode") || sender.hasPermission("wildtimber.godmode")) suggestions.add("godmode");
            if (sender.hasPermission("wildtimber.admin.undo"))     suggestions.add("undo");
            if (sender.hasPermission("wildtimber.toggle"))         suggestions.add("toggle");
            if (sender.hasPermission("wildtimber.admin"))          suggestions.add("info");
            return suggestions;
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("language") || sub.equals("lang")) {
                return List.of("fr", "en", "es", "de", "zh");
            }
            if (sub.equals("godmode") || sub.equals("god") || sub.equals("undo") || sub.equals("toggle")) {
                if (sender.hasPermission("wildtimber.admin")) {
                    List<String> players = new ArrayList<>();
                    for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                        players.add(p.getName());
                    }
                    return players;
                }
            }
        }

        return Collections.emptyList();
    }
}
