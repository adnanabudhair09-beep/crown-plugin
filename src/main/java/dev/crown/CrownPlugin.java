package dev.crown;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CrownPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private NamespacedKey crownKey;
    private NamespacedKey recipeKey;
    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        crownKey = new NamespacedKey(this, "crown");
        recipeKey = new NamespacedKey(this, "crown_recipe");
        loadData();
        registerRecipe();
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("crown").setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickEffects, 20L, 10L);
    }

    // ---------------------------------------------------------------- data

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = data.getConfigurationSection("pending");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                List<ItemStack> items = new ArrayList<>();
                for (Object o : sec.getList(key, new ArrayList<>())) {
                    if (o instanceof ItemStack s) items.add(s);
                }
                try {
                    pending.put(UUID.fromString(key), items);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveData() {
        data.set("pending", null);
        for (Map.Entry<UUID, List<ItemStack>> e : pending.entrySet()) {
            data.set("pending." + e.getKey(), e.getValue());
        }
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- item

    private ItemStack createCrown() {
        ItemStack item = new ItemStack(Material.GOLDEN_HELMET);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Royal Crown", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Worn on the head:", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("+" + getConfig().getInt("extra-hearts", 5) + " hearts, Speed, Strength,",
                        NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.text("Fire Resistance, Regeneration", NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Never lost on death.", NamedTextColor.LIGHT_PURPLE)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.setUnbreakable(true);
        meta.setItemModel(new NamespacedKey("crown", "crown"));

        double extraHealth = getConfig().getInt("extra-hearts", 5) * 2.0;
        meta.addAttributeModifier(Attribute.MAX_HEALTH, new AttributeModifier(
                new NamespacedKey(this, "crown_health"), extraHealth,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
                new NamespacedKey(this, "crown_armor"), 2.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));

        meta.getPersistentDataContainer().set(crownKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isCrown(ItemStack s) {
        return s != null && s.hasItemMeta()
                && s.getItemMeta().getPersistentDataContainer().has(crownKey, PersistentDataType.BYTE);
    }

    private void registerRecipe() {
        ShapedRecipe r = new ShapedRecipe(recipeKey, createCrown());
        r.shape("NTN", "ESE", "NTN");
        r.setIngredient('N', Material.NETHERITE_BLOCK);
        r.setIngredient('T', Material.TOTEM_OF_UNDYING);
        r.setIngredient('E', Material.ELYTRA);
        r.setIngredient('S', Material.NETHER_STAR);
        Bukkit.addRecipe(r);
    }

    // ---------------------------------------------------------------- craft limit

    private boolean limitReached(Player p) {
        return switch (getConfig().getString("craft-limit", "SERVER").toUpperCase()) {
            case "SERVER" -> data.getBoolean("crafted-server");
            case "PLAYER" -> data.getStringList("crafted-players").contains(p.getUniqueId().toString());
            default -> false;
        };
    }

    private void markCrafted(Player p) {
        switch (getConfig().getString("craft-limit", "SERVER").toUpperCase()) {
            case "SERVER" -> data.set("crafted-server", true);
            case "PLAYER" -> {
                List<String> list = new ArrayList<>(data.getStringList("crafted-players"));
                list.add(p.getUniqueId().toString());
                data.set("crafted-players", list);
            }
            default -> {
            }
        }
        saveData();
    }

    private boolean isCrownRecipe(Recipe r) {
        return r instanceof Keyed k && k.getKey().equals(recipeKey);
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent e) {
        if (!isCrownRecipe(e.getRecipe())) return;
        if (e.getView().getPlayer() instanceof Player p && limitReached(p)) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (!isCrownRecipe(e.getRecipe())) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.isShiftClick()) {
            e.setCancelled(true);
            p.sendMessage(Component.text("Click normally to craft the crown (no shift-click).", NamedTextColor.RED));
            return;
        }
        if (limitReached(p)) {
            e.setCancelled(true);
            p.sendMessage(Component.text("The crown can't be crafted again.", NamedTextColor.RED));
            return;
        }
        markCrafted(p);
        Bukkit.broadcast(Component.text(p.getName() + " has crafted the Royal Crown!", NamedTextColor.GOLD));
    }

    // ---------------------------------------------------------------- effects

    private void tickEffects() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean wearing = isCrown(p.getInventory().getHelmet());
            handle(p, PotionEffectType.SPEED, "speed", wearing);
            handle(p, PotionEffectType.STRENGTH, "strength", wearing);
            handle(p, PotionEffectType.FIRE_RESISTANCE, "fire-resistance", wearing);
            handle(p, PotionEffectType.REGENERATION, "regeneration", wearing);
        }
    }

    private void handle(Player p, PotionEffectType type, String key, boolean wearing) {
        int lvl = getConfig().getInt("effects." + key, -1);
        PotionEffect cur = p.getPotionEffect(type);
        if (wearing && lvl >= 0) {
            boolean needs = cur == null
                    || (cur.isInfinite() && cur.getAmplifier() != lvl)
                    || (!cur.isInfinite() && cur.getAmplifier() <= lvl);
            if (needs) {
                p.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, lvl, true, false, true));
            }
        } else if (cur != null && cur.isInfinite() && cur.isAmbient()) {
            p.removePotionEffect(type);
        }
    }

    // ---------------------------------------------------------------- death protection

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        if (e.getKeepInventory()) return;
        List<ItemStack> saved = new ArrayList<>();
        Iterator<ItemStack> it = e.getDrops().iterator();
        while (it.hasNext()) {
            ItemStack s = it.next();
            if (isCrown(s)) {
                saved.add(s);
                it.remove();
            }
        }
        if (!saved.isEmpty()) {
            pending.computeIfAbsent(e.getEntity().getUniqueId(), k -> new ArrayList<>()).addAll(saved);
            saveData();
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        List<ItemStack> items = pending.remove(p.getUniqueId());
        if (items == null) return;
        saveData();
        Bukkit.getScheduler().runTaskLater(this, () -> giveBack(p, items), 2L);
    }

    private void giveBack(Player p, List<ItemStack> items) {
        for (ItemStack s : items) {
            ItemStack helmet = p.getInventory().getHelmet();
            if (helmet == null || helmet.getType() == Material.AIR) {
                p.getInventory().setHelmet(s);
            } else {
                Map<Integer, ItemStack> left = p.getInventory().addItem(s);
                for (ItemStack l : left.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), l);
                }
            }
        }
        p.sendMessage(Component.text("Your crown returned to you.", NamedTextColor.GOLD));
    }

    // ---------------------------------------------------------------- dropped item protection

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        Item i = e.getEntity();
        if (isCrown(i.getItemStack())) {
            i.setInvulnerable(true);
            i.setUnlimitedLifetime(true);
        }
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent e) {
        if (isCrown(e.getEntity().getItemStack())) e.setCancelled(true);
    }

    @EventHandler
    public void onItemDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item i && isCrown(i.getItemStack())) {
            e.setCancelled(true);
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID) {
                i.teleport(i.getWorld().getSpawnLocation().add(0.5, 1, 0.5));
                i.setVelocity(new Vector(0, 0, 0));
            }
        }
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("crown.admin")) {
            s.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("give")) {
            Player t = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player p ? p : null);
            if (t == null) {
                s.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
            t.getInventory().addItem(createCrown());
            s.sendMessage(Component.text("Crown given to " + t.getName() + ".", NamedTextColor.GREEN));
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("reset")) {
            data.set("crafted-server", null);
            data.set("crafted-players", null);
            saveData();
            s.sendMessage(Component.text("Craft limit reset. The crown can be crafted again.", NamedTextColor.GREEN));
            return true;
        }
        s.sendMessage(Component.text("Usage: /crown give [player] | /crown reset", NamedTextColor.YELLOW));
        return true;
    }
}
