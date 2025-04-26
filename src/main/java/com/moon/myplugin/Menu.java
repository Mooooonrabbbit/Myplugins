package com.moon.myplugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;

import java.util.*;

import static com.moon.myplugin.Myplugin.plugin;

public class Menu implements Listener, CommandExecutor {
    private static final Map<String, MenuConfig> menus = new HashMap<>();

    // 加载菜单配置
    public void loadMenus() {
        File menuFolder = new File(plugin.getDataFolder(), "menus");
        if (!menuFolder.exists()) {
            menuFolder.mkdirs();
            plugin.saveResource("menus/main.yml", false);
            menuFolder = new File(plugin.getDataFolder(), "menus");
        }
        File[] files = menuFolder.listFiles();
        if (files == null || files.length == 0) {
            System.out.println("no found menu");
            return;
        }
        for (File file : files) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            String s = file.getName().replace(".yml", "");
            menus.put(s, new MenuConfig(config));
            System.out.println("Loaded menu:%s" + s);
        }
    }

    // 指令处理器
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (args.length == 0) {
            openMenu(player, "main");
            return true;
        }
        if (args.length == 1) {
            openMenu(player, args[0]);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            loadMenus();
            player.sendMessage(ChatColor.GREEN + "menu reload");
            return true;
        }

        return false;
    }

    // 打开菜单
    private void openMenu(Player player, String menuName) {
        MenuConfig config = menus.get(menuName);
        if (config == null) {
            System.out.printf("no find %s config\n", menuName);
            return;
        }

        Inventory menu = Bukkit.createInventory(new MenuHolder(menuName),
                config.getSize(),
                ChatColor.translateAlternateColorCodes('&', config.getTitle())
        );

        for (MenuItem item : config.getItems().values()) {
            ItemStack stack = createMenuItem(item);
            menu.setItem(item.getSlot(), stack);
        }

        player.openInventory(menu);
    }

    // 创建菜单项
    private ItemStack createMenuItem(MenuItem item) {
        ItemStack stack = new ItemStack(item.getMaterial());
        ItemMeta meta = stack.getItemMeta();

        // 设置持久化数据标记
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(plugin.key, PersistentDataType.STRING, item.getId());

        // 设置显示属性
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', item.getName()));
        List<String> lore = new ArrayList<>();
        for (String line : item.getLore()) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);

        return stack;
    }

    // 事件监听
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) return;
        if (event.getClickedInventory() == null) return;

        event.setCancelled(true); // 阻止物品移动

        ItemStack item = event.getCurrentItem();
        if (item == null) return;

        Player player = (Player) event.getWhoClicked();
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();

        if (data.has(plugin.key)) {
            String itemId = data.get(plugin.key, PersistentDataType.STRING);
            handleMenuClick(player, itemId);
        }
    }

    // 处理菜单点击
    private void handleMenuClick(Player player, String itemId) {
        for (MenuConfig config : menus.values()) {
            MenuItem item = config.getItem(itemId);
            if (item != null) {
                executeAction(player, item);
                break;
            }
        }
    }

    // 执行菜单动作
    private void executeAction(Player player, MenuItem item) {
        String action = item.getAction();
        if (action.startsWith("menu:")) {
            openMenu(player, action.split(":")[1]);
        } else if (action.startsWith("cmd:")) {
            player.performCommand(action.substring(4));
        } else if (action.startsWith("console:")) {
            plugin.getServer().dispatchCommand(plugin.getServer().getConsoleSender(), action.substring(8));
        }
    }

    // 自定义 InventoryHolder
    private class MenuHolder implements InventoryHolder {
        private final String menuName;

        public MenuHolder(String menuName) {
            this.menuName = menuName;
        }

        @Override
        public Inventory getInventory() {
            return null; // 实际库存由主类管理
        }
    }

    // 菜单配置类
    private class MenuConfig {
        private String title;
        private int size;
        private Map<String, MenuItem> items = new HashMap<>();

        public MenuConfig(YamlConfiguration config) {
            this.title = config.getString("title", "Menu");
            this.size = config.getInt("size", 27);

            for (String key : config.getConfigurationSection("items").getKeys(false)) {
                String path = "items." + key;
                MenuItem item = new MenuItem(
                        key,
                        config.getString(path + ".material", "STONE"),
                        config.getString(path + ".name", "Item"),
                        config.getStringList(path + ".lore"),
                        config.getInt(path + ".slot", 0),
                        config.getString(path + ".action", "")
                );
                items.put(key, item);
            }
        }

        public String getTitle() {
            return title;
        }

        public int getSize() {
            return size;
        }

        public Map<String, MenuItem> getItems() {
            return items;
        }

        public MenuItem getItem(String key) {
            return items.get(key);
        }

        // Getter方法...
    }

    // 菜单项类
    private class MenuItem {
        private String id;
        private Material material;
        private String name;
        private List<String> lore;
        private int slot;
        private String action;

        public MenuItem(String id, String material, String name,
                        List<String> lore, int slot, String action) {
            this.id = id;
            this.material = Material.matchMaterial(material);
            this.name = name;
            this.lore = lore;
            this.slot = slot;
            this.action = action;
        }

        public String getId() {
            return id;
        }

        public Material getMaterial() {
            return material;
        }

        public String getName() {
            return name;
        }

        public List<String> getLore() {
            return lore;
        }

        public int getSlot() {
            return slot;
        }

        public String getAction() {
            return action;
        }

        // Getter方法...
    }
}
