package com.moon.myplugin;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;

public class Myplugin extends JavaPlugin {

    // 持久化数据键
    public NamespacedKey key;
    public static Myplugin plugin;

    @Override
    public void onEnable() {
        plugin = this;
        // 初始化配置
        saveDefaultConfig();
        reloadConfigValues();

        // 初始化数据键
        key = new NamespacedKey(this, "Myplugin");

        HealthXp healthXp = new HealthXp();
        Menu menu = new Menu();
        // 注册事件和指令
        Bukkit.getPluginManager().registerEvents(healthXp, this);
        Bukkit.getPluginManager().registerEvents(menu, this);
        getCommand("hx").setExecutor(this);
        getCommand("menu").setExecutor(this);
        // 为在线玩家应用状态
        menu.loadMenus();
        Bukkit.getOnlinePlayers().forEach(p -> healthXp.applyHealthDisplay(p, healthXp.isEnabled(p)));
    }

    // ============== 配置文件管理 ==============
    public void reloadConfigValues() {
        reloadConfig();
        FileConfiguration config = getConfig();
        config.addDefault("default-enabled", true);
        config.addDefault("update-cooldown", 100);
        config.options().copyDefaults(true);
        saveConfig();

        HealthXp.defaultEnabled = config.getBoolean("default-enabled");
        HealthXp.updateCooldown = config.getLong("update-cooldown");
    }

}