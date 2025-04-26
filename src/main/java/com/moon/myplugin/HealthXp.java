package com.moon.myplugin;

import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.moon.myplugin.Myplugin.plugin;

public class HealthXp  implements Listener, CommandExecutor {
    // 默认是否启用功能
    public static boolean defaultEnabled;
    // 更新间隔限制（毫秒）
    public static long updateCooldown = 100;


    private final Map<UUID, Long> lastUpdateMap = new HashMap<>();


    // ============== 功能开关状态管理 ==============
    public boolean isEnabled(Player player) {
        return player.getPersistentDataContainer()
                .getOrDefault(plugin.key, PersistentDataType.BOOLEAN, defaultEnabled);
    }

    public void setEnabled(Player player, boolean enabled) {
        player.getPersistentDataContainer().set(plugin.key, PersistentDataType.BOOLEAN, enabled);
        applyHealthDisplay(player, enabled);
        player.sendMessage(enabled ? "§a血量显示已启用" : "§c血量显示已禁用");
    }
    // ============== 血量显示逻辑 ==============
    public void updateHealthDisplay(Player player) {
        if (!isEnabled(player)) return;

        // 防高频更新
        long now = System.currentTimeMillis();
        if (now - lastUpdateMap.getOrDefault(player.getUniqueId(), 0L) < updateCooldown) return;
        lastUpdateMap.put(player.getUniqueId(), now);

        // 计算血量和进度
        double health = player.getHealth();
        double maxHealth = player.getAttribute(Attribute.MAX_HEALTH).getValue();
        float progress = (float) Math.max(0, Math.min(health / maxHealth, 1.0));
        int level = (health > 0 && health <= 1) ? 1 : (int) Math.floor(health);

        // 发送数据包（保留真实总经验）
        sendExperiencePacket(player, progress, level, player.getTotalExperience());
    }
    public static void sendExperiencePacket(Player player, float progress, int level, int totalExp) {
        ClientboundSetExperiencePacket packet = new ClientboundSetExperiencePacket(progress, totalExp, level);
        ((CraftPlayer) player).getHandle().connection.send(packet);
    }
    // ============== 事件监听 ==============
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            applyHealthDisplay(player, isEnabled(player));
        }, 20); // 延迟20 ticks确保数据同步
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            scheduleUpdate((Player) event.getEntity(),2);
        }
    }

    @EventHandler
    public void onPlayerHeal(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player) {
            scheduleUpdate((Player) event.getEntity(),2);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        scheduleUpdate(event.getPlayer(),2);
    }

    @EventHandler
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        scheduleUpdate(event.getPlayer(),20);
    }

    // 延迟更新
    private void scheduleUpdate(Player player,long updateCooldown) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) updateHealthDisplay(player);
        }, updateCooldown);
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
                if (sender instanceof Player) setEnabled((Player) sender, true);
                else sender.sendMessage("§c只有玩家可以使用此指令");
                break;
            case "off":
                if (sender instanceof Player) setEnabled((Player) sender, false);
                else sender.sendMessage("§c只有玩家可以使用此指令");
                break;
            case "reload":
                if (sender.hasPermission("hxp.reload")) {
                    plugin.reloadConfigValues();
                    sender.sendMessage("§aHealthXp5配置已重载");
                } else {
                    sender.sendMessage("§c你没有权限");
                }
                break;
            default:
                sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6===== HealthXp5 帮助 =====");
        sender.sendMessage("§a/hx on §7- 启用经验值×血量显示");
        sender.sendMessage("§a/hx off §7- 禁用经验值×血量显示");
        sender.sendMessage("§a/hx help §7- HealthXp5帮助");
        if (sender.hasPermission("hxp.reload")) {
            sender.sendMessage("§a/hx reload §7- 重载HealthXp5配置");
        }
    }

    // 应用显示状态
    public void applyHealthDisplay(Player player, boolean enabled) {
        if (enabled) {
            updateHealthDisplay(player);
        } else {
            // 恢复原版显示（发送真实经验）
            sendExperiencePacket(
                    player,
                    player.getExp(),
                    player.getLevel(),
                    player.getTotalExperience()
            );
        }
    }
}
