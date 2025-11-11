package com.moon.myplugin;

import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.entity.CraftPlayer; // 关键修改
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

import static com.moon.myplugin.Moon1.plugin;

public class HealthXp implements Listener, CommandExecutor {

    // 持久化数据键
    private NamespacedKey toggleKey;
    private NamespacedKey originalHealthKey;

    // 配置值
    public static boolean defaultEnabled;
    private int displayMaxHearts;
    public static long updateCooldown;

    // 性能优化
    private final Map<UUID, Long> lastUpdateMap = new HashMap<>();
    private final Map<UUID, Float> lastSentHealth = new HashMap<>();
    private final Map<UUID, Float> lastSentExp = new HashMap<>();

    @Override
    public void onEnable() {
        // 初始化配置
        saveDefaultConfig();
        reloadConfigValues();

        // 初始化数据键
        toggleKey = new NamespacedKey(this, "health-display-toggle");
        originalHealthKey = new NamespacedKey(this, "original-health");

        // 注册事件和指令
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("hx").setExecutor(this);

        // 为在线玩家应用状态
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyHealthDisplay(player, isEnabled(player));
        }

        getLogger().info("血量显示插件已启用");
    }

    // ============== 配置管理 ==============
    private void reloadConfigValues() {
        reloadConfig();
        FileConfiguration config = getConfig();

        // 设置默认值
        config.addDefault("default-enabled", true);
        config.addDefault("display-max-hearts", 10); // 显示的最大心数
        config.addDefault("update-cooldown", 100); // 更新间隔(ms)
        config.options().copyDefaults(true);
        saveConfig();

        // 读取配置
        defaultEnabled = config.getBoolean("default-enabled");
        displayMaxHearts = config.getInt("display-max-hearts");
        updateCooldown = config.getLong("update-cooldown");
    }

    // ============== 功能开关状态管理 ==============
    public boolean isEnabled(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        return data.getOrDefault(toggleKey, PersistentDataType.BOOLEAN, defaultEnabled);
    }

    private void setEnabled(Player player, boolean enabled) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        data.set(toggleKey, PersistentDataType.BOOLEAN, enabled);

        if (enabled) {
            // 保存原始血量数据
            saveOriginalHealth(player);
            updatePlayerDisplay(player);
            player.sendMessage(ChatColor.GREEN + "血量显示已启用");
        } else {
            // 恢复原始显示
            restoreOriginalDisplay(player);
            player.sendMessage(ChatColor.YELLOW + "血量显示已禁用");
        }
    }

    // ============== 原始数据保存与恢复 ==============
    private void saveOriginalHealth(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        // 保存当前真实血量（用于恢复时参考）
        data.set(originalHealthKey, PersistentDataType.FLOAT, (float) player.getHealth());
    }

    private void restoreOriginalDisplay(Player player) {
        // 发送真实经验数据包
        sendExperiencePacket(player, player.getExp(), player.getLevel(), player.getTotalExperience());

        // 发送真实血量数据包
        float health = (float) player.getHealth();
        int food = player.getFoodLevel();
        float saturation = player.getSaturation();
        sendHealthPacket(player, health, food, saturation);

        // 清除缓存
        lastSentHealth.remove(player.getUniqueId());
        lastSentExp.remove(player.getUniqueId());
    }

    // ============== 相对血量计算 ==============
    /**
     * 计算相对显示的血量值
     * @param actualHealth 实际血量
     * @param maxHealth 最大血量
     * @return 相对显示的血量（每颗心=2点血量）
     */
    private float calculateRelativeHealth(double actualHealth, double maxHealth) {
        // 计算比例：实际血量 / 最大血量
        double ratio = actualHealth / maxHealth;

        // 转换为显示的血量值（基于配置的最大心数）
        double displayHealth = ratio * (displayMaxHearts * 2);

        // 向上取整，确保至少有1点显示血量
        return (float) Math.max(1, Math.ceil(displayHealth));
    }

    /**
     * 计算经验条进度
     */
    private float calculateExperienceProgress(double actualHealth, double maxHealth) {
        return (float) Math.max(0, Math.min(actualHealth / maxHealth, 1.0));
    }

    /**
     * 计算显示的等级
     */
    private int calculateDisplayLevel(double actualHealth) {
        // 边界处理：血量在 (0,1] 时显示1级
        if (actualHealth > 0 && actualHealth <= 1) return 1;
        return (int) Math.floor(actualHealth);
    }

    // ============== 数据包发送方法 ==============
    /**
     * 发送经验数据包
     */
    private void sendExperiencePacket(Player player, float progress, int level, int totalExp) {
        UUID playerId = player.getUniqueId();
        Float lastExp = lastSentExp.get(playerId);

        // 检查是否需要更新（避免重复发送相同数据）
        if (lastExp != null && Math.abs(lastExp - progress) < 0.01f) {
            return;
        }

        try {
            ClientboundSetExperiencePacket packet = new ClientboundSetExperiencePacket(progress, totalExp, level);
            ((CraftPlayer) player).getHandle().connection.send(packet);
            lastSentExp.put(playerId, progress);
        } catch (Exception e) {
            getLogger().warning("发送经验数据包失败: " + e.getMessage());
        }
    }

    /**
     * 发送血量数据包
     */
    private void sendHealthPacket(Player player, float health, int food, float saturation) {
        UUID playerId = player.getUniqueId();
        Float lastHealth = lastSentHealth.get(playerId);

        // 检查是否需要更新
        if (lastHealth != null && Math.abs(lastHealth - health) < 0.1f) {
            return;
        }

        try {
            ClientboundSetHealthPacket packet = new ClientboundSetHealthPacket(health, food, saturation);
            ((CraftPlayer) player).getHandle().connection.send(packet);
            lastSentHealth.put(playerId, health);
        } catch (Exception e) {
            getLogger().warning("发送血量数据包失败: " + e.getMessage());
        }
    }

    // ============== 显示更新逻辑 ==============
    private void updatePlayerDisplay(Player player) {
        if (!isEnabled(player) || player.isDead()) return;

        // 防高频更新
        long now = System.currentTimeMillis();
        if (now - lastUpdateMap.getOrDefault(player.getUniqueId(), 0L) < updateCooldown) {
            return;
        }
        lastUpdateMap.put(player.getUniqueId(), now);

        try {
            // 获取玩家血量数据
            double actualHealth = player.getHealth();
            double maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();

            // 计算显示值
            float relativeHealth = calculateRelativeHealth(actualHealth, maxHealth);
            float expProgress = calculateExperienceProgress(actualHealth, maxHealth);
            int displayLevel = calculateDisplayLevel(actualHealth);

            // 发送数据包
            sendHealthPacket(player, relativeHealth, player.getFoodLevel(), player.getSaturation());
            sendExperiencePacket(player, expProgress, displayLevel, player.getTotalExperience());

        } catch (Exception e) {
            getLogger().warning("更新玩家显示失败: " + e.getMessage());
        }
    }

    // ============== 事件监听 ==============
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 延迟确保数据同步完成
        new BukkitRunnable() {
            @Override
            public void run() {
                applyHealthDisplay(player, isEnabled(player));
            }
        }.runTaskLater(this, 20);
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            scheduleUpdate((Player) event.getEntity());
        }
    }

    @EventHandler
    public void onPlayerHeal(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player) {
            scheduleUpdate((Player) event.getEntity());
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // 重生后延迟更新
        new BukkitRunnable() {
            @Override
            public void run() {
                applyHealthDisplay(player, isEnabled(player));
            }
        }.runTaskLater(this, 10);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 清理缓存
        UUID playerId = event.getPlayer().getUniqueId();
        lastUpdateMap.remove(playerId);
        lastSentHealth.remove(playerId);
        lastSentExp.remove(playerId);
    }



    // 延迟更新（确保血量已结算）
    private void scheduleUpdate(Player player) {
        List<MetadataValue> metadata = player.getMetadata("ExpChange");
        if(!metadata.isEmpty()) {
            ((BukkitTask)metadata.getFirst().value()).cancel();
        }
        BukkitTask bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) updatePlayerDisplay(player);
        }, updateCooldown);
        player.setMetadata("ExpChange",new FixedMetadataValue(plugin,bukkitTask));
    }
    // ============== 指令处理 ==============
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("hx")) return false;

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "on":
                if (sender instanceof Player) {
                    setEnabled((Player) sender, true);
                } else {
                    sender.sendMessage(ChatColor.RED + "只有玩家可以使用此指令");
                }
                break;

            case "off":
                if (sender instanceof Player) {
                    setEnabled((Player) sender, false);
                } else {
                    sender.sendMessage(ChatColor.RED + "只有玩家可以使用此指令");
                }
                break;

            case "reload":
                if (sender.hasPermission("hx.reload")) {
                    reloadConfigValues();
                    // 为所有在线玩家重新应用显示
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        applyHealthDisplay(player, isEnabled(player));
                    }
                    sender.sendMessage(ChatColor.GREEN + "配置已重载并应用到所有在线玩家");
                } else {
                    sender.sendMessage(ChatColor.RED + "你没有权限执行此操作");
                }
                break;

            case "status":
                if (sender instanceof Player) {
                    boolean enabled = isEnabled((Player) sender);
                    sender.sendMessage(ChatColor.YELLOW + "血量显示状态: " +
                            (enabled ? ChatColor.GREEN + "启用" : ChatColor.RED + "禁用"));
                }
                break;

            default:
                sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== 血量显示插件帮助 =====");
        sender.sendMessage(ChatColor.GREEN + "/hx on" + ChatColor.WHITE + " - 启用血量显示");
        sender.sendMessage(ChatColor.GREEN + "/hx off" + ChatColor.WHITE + " - 禁用血量显示");
        sender.sendMessage(ChatColor.GREEN + "/hx status" + ChatColor.WHITE + " - 查看当前状态");
        if (sender.hasPermission("hx.reload")) {
            sender.sendMessage(ChatColor.GREEN + "/hx reload" + ChatColor.WHITE + " - 重载配置");
        }
    }

    // 应用显示状态
    public void applyHealthDisplay(Player player, boolean enabled) {
        if (enabled) {
            saveOriginalHealth(player);
            updatePlayerDisplay(player);
        } else {
            restoreOriginalDisplay(player);
        }
    }
}