package jfbdev.jeffects;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JEffects extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final Map<UUID, Map<PotionEffectType, EffectData>> playerEffects = new HashMap<>();
    private File dataFile;
    private org.bukkit.configuration.file.FileConfiguration dataConfig;
    private String prefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfigCustom();

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("je")).setExecutor(this);
        Objects.requireNonNull(getCommand("je")).setTabCompleter(this);

        dataFile = new File(getDataFolder(), "effects.yml");

        if (!getDataFolder().mkdirs() && !getDataFolder().exists()) {
            getLogger().severe("Не удалось создать папку плагина: " + getDataFolder().getPath());
        }

        if (!dataFile.exists()) {
            try {
                if (!dataFile.createNewFile()) {
                    getLogger().severe("Не удалось создать effects.yml!");
                }
            } catch (IOException e) {
                getLogger().severe("Не удалось создать effects.yml!");
                getLogger().severe(e.getMessage());
            }
        }
        dataConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);

        loadEffects();

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    applyEffectsToPlayer(player);
                }
                saveEffectsAsync();
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    @Override
    public void onDisable() {
        saveEffectsAsync();
    }

    private void reloadConfigCustom() {
        reloadConfig();
        prefix = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.prefix", "&7[&aJEffects&7] "));
    }

    private String msg(String path) {
        String message = getConfig().getString("messages." + path, "&cСообщение не найдено: " + path);
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private String msg(String path, String... placeholders) {
        String message = msg(path);
        for (int i = 0; i < placeholders.length - 1; i += 2) {
            message = message.replace(placeholders[i], placeholders[i + 1]);
        }
        return prefix + message;
    }

    private void loadEffects() {
        playerEffects.clear();
        if (!dataFile.exists()) return;

        for (String uuidStr : dataConfig.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                Map<PotionEffectType, EffectData> effects = new HashMap<>();
                org.bukkit.configuration.ConfigurationSection section = dataConfig.getConfigurationSection(uuidStr);
                if (section == null) continue;

                for (String effectName : section.getKeys(false)) {
                    PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
                    if (type == null) continue;

                    EffectData data = new EffectData();
                    data.level = dataConfig.getInt(uuidStr + "." + effectName + ".level", 1);
                    data.remainingTicks = dataConfig.getLong(uuidStr + "." + effectName + ".remainingTicks", 0);
                    data.infinite = dataConfig.getBoolean(uuidStr + "." + effectName + ".infinite", false);
                    effects.put(type, data);
                }
                if (!effects.isEmpty()) {
                    playerEffects.put(uuid, effects);
                }
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void saveEffectsAsync() {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                saveEffects();
            }
        }.runTaskAsynchronously(this);
    }

    private void saveEffects() {
        org.bukkit.configuration.file.YamlConfiguration newConfig = new org.bukkit.configuration.file.YamlConfiguration();

        for (Map.Entry<UUID, Map<PotionEffectType, EffectData>> entry : playerEffects.entrySet()) {
            UUID uuid = entry.getKey();
            for (Map.Entry<PotionEffectType, EffectData> effectEntry : entry.getValue().entrySet()) {
                String path = uuid + "." + effectEntry.getKey().getName();
                newConfig.set(path + ".level", effectEntry.getValue().level);
                newConfig.set(path + ".remainingTicks", effectEntry.getValue().remainingTicks);
                newConfig.set(path + ".infinite", effectEntry.getValue().infinite);
            }
        }

        try {
            newConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Не удалось сохранить effects.yml!");
            getLogger().severe(e.getMessage());
        }
    }

    private void applyEffectsToPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        Map<PotionEffectType, EffectData> effects = playerEffects.get(uuid);
        if (effects == null || effects.isEmpty()) return;

        Iterator<Map.Entry<PotionEffectType, EffectData>> iterator = effects.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PotionEffectType, EffectData> entry = iterator.next();
            PotionEffectType type = entry.getKey();
            EffectData data = entry.getValue();

            if (!data.infinite && data.remainingTicks > 0) {
                data.remainingTicks--;
                if (data.remainingTicks <= 0) {
                    iterator.remove();
                    player.removePotionEffect(type);
                    continue;
                }
            }

            player.addPotionEffect(new PotionEffect(type, Integer.MAX_VALUE, data.level - 1, true, false), true);
        }

        if (effects.isEmpty()) {
            playerEffects.remove(uuid);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyEffectsToPlayer(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        saveEffectsAsync();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
    }

    @EventHandler
    public void onMilk(PlayerInteractEvent e) {
        if (e.getItem() == null || e.getItem().getType() != org.bukkit.Material.MILK_BUCKET) return;
        if (!e.getAction().toString().contains("RIGHT_CLICK")) return;

        Player player = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> applyEffectsToPlayer(player), 2L);
    }

    @Override
    public boolean onCommand(CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (!sender.hasPermission("jeffects.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(msg("usage.main"));
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "give" -> {
                if (args.length < 5) {
                    sender.sendMessage(msg("usage.give"));
                    return true;
                }

                Player targetOnline = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = targetOnline != null ? targetOnline : Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null || target.getName() == null) {
                    sender.sendMessage(msg("player-not-found", "%player%", args[1]));
                    return true;
                }

                PotionEffectType type = PotionEffectType.getByName(args[2].toUpperCase());
                if (type == null) {
                    sender.sendMessage(msg("invalid-effect"));
                    return true;
                }

                long seconds = parseDuration(args[3]);
                if (seconds == -2) {
                    sender.sendMessage(msg("invalid-duration"));
                    return true;
                }

                int level;
                try {
                    level = Integer.parseInt(args[4]);
                    if (level < 1) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    sender.sendMessage(msg("invalid-level"));
                    return true;
                }

                UUID uuid = target.getUniqueId();
                playerEffects.computeIfAbsent(uuid, k -> new HashMap<>());

                EffectData data = new EffectData();
                data.level = level;
                data.infinite = seconds == -1;
                data.remainingTicks = data.infinite ? 0 : seconds * 20;

                playerEffects.get(uuid).put(type, data);

                if (targetOnline != null) {
                    applyEffectsToPlayer(targetOnline);
                }

                sender.sendMessage(msg("give-success",
                        "%player%", target.getName(),
                        "%effect%", type.getName(),
                        "%level%", String.valueOf(level),
                        "%duration%", data.infinite ? "∞" : formatTime(seconds)));
                saveEffectsAsync();
            }

            case "clear" -> {
                if (args.length < 3) {
                    sender.sendMessage(msg("usage.clear"));
                    return true;
                }

                Player targetOnline = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = targetOnline != null ? targetOnline : Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null || target.getName() == null) {
                    sender.sendMessage(msg("player-not-found", "%player%", args[1]));
                    return true;
                }

                UUID uuid = target.getUniqueId();
                Map<PotionEffectType, EffectData> effects = playerEffects.get(uuid);

                if (effects == null || effects.isEmpty()) {
                    sender.sendMessage(msg("no-effects", "%player%", target.getName()));
                    return true;
                }

                if (args[2].equalsIgnoreCase("all")) {
                    playerEffects.remove(uuid);
                    if (targetOnline != null) {
                        targetOnline.getActivePotionEffects().forEach(pe -> targetOnline.removePotionEffect(pe.getType()));
                    }
                    sender.sendMessage(msg("clear-all-success", "%player%", target.getName()));
                } else {
                    PotionEffectType type = PotionEffectType.getByName(args[2].toUpperCase());
                    if (type == null) {
                        sender.sendMessage(msg("invalid-effect"));
                        return true;
                    }
                    if (effects.remove(type) != null) {
                        if (targetOnline != null) {
                            targetOnline.removePotionEffect(type);
                        }
                        sender.sendMessage(msg("clear-success",
                                "%player%", target.getName(),
                                "%effect%", type.getName()));
                    } else {
                        sender.sendMessage(msg("no-such-effect", "%player%", target.getName()));
                    }
                }
                saveEffectsAsync();
            }

            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(msg("usage.info"));
                    return true;
                }

                Player targetOnline = Bukkit.getPlayer(args[1]);
                OfflinePlayer target = targetOnline != null ? targetOnline : Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null || target.getName() == null) {
                    sender.sendMessage(msg("player-not-found", "%player%", args[1]));
                    return true;
                }

                Map<PotionEffectType, EffectData> effects = playerEffects.get(target.getUniqueId());

                if (effects == null || effects.isEmpty()) {
                    sender.sendMessage(msg("no-effects", "%player%", target.getName()));
                    return true;
                }

                sender.sendMessage(msg("info-header", "%player%", target.getName()));
                for (Map.Entry<PotionEffectType, EffectData> entry : effects.entrySet()) {
                    EffectData data = entry.getValue();
                    String duration = data.infinite ? msg("infinite") : formatTime(data.remainingTicks / 20);
                    sender.sendMessage(msg("info-line",
                            "%effect%", entry.getKey().getName(),
                            "%level%", String.valueOf(data.level),
                            "%duration%", duration));
                }
            }

            case "reload" -> {
                reloadConfigCustom();
                loadEffects();
                sender.sendMessage(msg("reload-success"));
            }

            default -> sender.sendMessage(msg("unknown-command"));
        }
        return true;
    }

    private long parseDuration(String input) {
        if (input.equalsIgnoreCase("-1")) return -1;
        Pattern p = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(input.toLowerCase());
        if (!m.matches()) return -2;

        long value = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "s" -> value;
            case "m" -> value * 60;
            case "h" -> value * 3600;
            case "d" -> value * 86400;
            case "w" -> value * 604800;
            default -> -2;
        };
    }

    private String formatTime(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) return (seconds / 3600) + "h";
        if (seconds < 604800) return (seconds / 86400) + "d";
        return (seconds / 604800) + "w";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, @NotNull Command cmd, @NotNull String alias, String[] args) {
        if (!sender.hasPermission("jeffects.admin")) return null;

        if (args.length == 1) {
            return filterStartsWith(Arrays.asList("give", "clear", "info", "reload"), args[0]);
        }

        String sub = args[0].toLowerCase();
        if (args.length == 2 && (sub.equals("give") || sub.equals("clear") || sub.equals("info"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (sub.equals("give")) {
            if (args.length == 3) {
                return filterStartsWith(Arrays.stream(PotionEffectType.values())
                        .map(t -> t.getName().toLowerCase())
                        .collect(Collectors.toList()), args[2]);
            }
            if (args.length == 4) {
                return filterStartsWith(Arrays.asList("30s", "10m", "1h", "2d", "1w", "-1"), args[3]);
            }
            if (args.length == 5) {
                return filterStartsWith(Arrays.asList("1", "2", "3", "4", "5", "10"), args[4]);
            }
        }

        if (sub.equals("clear") && args.length == 3) {
            List<String> list = new ArrayList<>(filterStartsWith(
                    Arrays.stream(PotionEffectType.values())
                            .map(t -> t.getName().toLowerCase())
                            .collect(Collectors.toList()), args[2]));
            list.add("all");
            return list;
        }

        return null;
    }

    private List<String> filterStartsWith(List<String> list, String prefix) {
        return list.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .sorted()
                .collect(Collectors.toList());
    }

    private static class EffectData {
        int level = 1;
        long remainingTicks = 0;
        boolean infinite = false;
    }
}