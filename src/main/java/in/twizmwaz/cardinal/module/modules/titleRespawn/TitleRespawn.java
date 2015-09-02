package in.twizmwaz.cardinal.module.modules.titleRespawn;

import in.twizmwaz.cardinal.GameHandler;
import in.twizmwaz.cardinal.chat.ChatConstant;
import in.twizmwaz.cardinal.chat.LocalizedChatMessage;
import in.twizmwaz.cardinal.event.MatchStartEvent;
import in.twizmwaz.cardinal.module.TaskedModule;
import in.twizmwaz.cardinal.util.PlayerUtils;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * This module adds a new death and respawn system from the Overcast Network.
 */
public class TitleRespawn implements TaskedModule {
    // Module's global settings
    public static final PotionEffect[] HORSE_POTIONS = {
        // Make the horse invisible for players, so they can't see them
        new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1),
        // Prevert the horse from moving
        new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 10)
    };

    // Module's settings
    private final int delay;
    private final boolean auto;
    private final boolean blackout;
    private final boolean spectate;
    private final boolean bed;

    // Local variables
    private final Map<UUID, Long> deadPlayers = new HashMap<>();

    public TitleRespawn(int delay, boolean auto, boolean blackout, boolean spectate, boolean bed) {
        this.delay = delay;
        this.auto = auto;
        this.blackout = blackout;
        this.spectate = spectate;
        this.bed = bed;
    }

    @Override
    public void unload() {
        HandlerList.unregisterAll(this);
    }

    /**
     * This method is invoked 10 times in one second to update players titles 
     * and respawn them too. The BukkitScheduler is running with the 2 ticks 
     * delay because 20 (ticks) / 10 (times) = 2
     */
    @Override
    public void run() {
        // Loop all dead players and check them
        for (UUID id : this.deadPlayers.keySet()) {
            Player player = GameHandler.getGameHandler().getPlugin().getServer().getPlayer(id);
            if (player == null) {
                // Player has logged out
                this.deadPlayers.remove(id);
            } else if (this.canRespawn(id)) {
                // Player can respawn - let him do it
                if (this.auto) {
                    this.respawnPlayer(player);
                } else {
                    player.setSubtitle(TextComponent.fromLegacyText(new LocalizedChatMessage(ChatConstant.UI_RESPAWN_CLICK).getMessage(player.getLocale())));
                }
            } else {
                // Player is waiting to the respawn
                String respawn = Double.toString((this.deadPlayers.get(id) - System.currentTimeMillis()) / 1000.0);

                if (this.auto) {
                    player.setSubtitle(TextComponent.fromLegacyText(new LocalizedChatMessage(ChatConstant.UI_RESPAWN_AUTO, respawn).getMessage(player.getLocale())));
                } else {
                    player.setSubtitle(TextComponent.fromLegacyText(new LocalizedChatMessage(ChatConstant.UI_RESPAWN_SCHEDULE, respawn).getMessage(player.getLocale())));
                }
            }
        }
    }

    /**
     * Checks if the specifited player can respawn by their UUID
     * @param player to check
     * @return <code>true</code> if the player can respawn, otherwise 
     * <code>false</code>.
     */
    public boolean canRespawn(UUID player) {
        Long result = this.deadPlayers.get(player);
        if (result != null) {
            result = Long.MIN_VALUE;
        }

        return result <= System.currentTimeMillis();
    }

    /**
     * Checks if the specifited player is dead
     * @param player to check
     * @return <code>true</code> if the player is dead, otherwise 
     * <code>false</code>
     */
    public boolean isDead(UUID player) {
        return this.deadPlayers.containsKey(player);
    }

    /**
     * Respawn a player, clear the title and remove him from the death players.
     * @param player to be respawned
     */
    public void respawnPlayer(Player player) {
        boolean bedLocation = false;
        Location location = player.getWorld().getSpawnLocation();

        // Set the location to the bed location if it's enabled and player has a
        // bed spawn location
        if (this.bed && player.getBedSpawnLocation() != null) {
            bedLocation = true;
            location = player.getBedSpawnLocation();
        }

        // Call PlayerRespawnEvent to invoke all listener, and get the new spawn
        // location from other Cardinal's modules.
        PlayerRespawnEvent respawnEvent = new PlayerRespawnEvent(player, location, bedLocation);
        GameHandler.getGameHandler().getPlugin().getServer().getPluginManager().callEvent(respawnEvent);

        // Player is already reset to the default clear state in the death
        // event. We need only to set his gamemode to the survival mode nad
        // remove potion effects.
        player.removePotionEffect(PotionEffectType.BLINDNESS); // blackout
        player.setGameMode(GameMode.SURVIVAL);

        player.hideTitle();
        player.resetTitle();

        this.deadPlayers.remove(player.getUniqueId());
        // There is no respawn cause, so we use UNKNOWN. PLUGIN is a plugin, not
        // this fake respawn!
        player.teleport(respawnEvent.getRespawnLocation(), PlayerTeleportEvent.TeleportCause.UNKNOWN);

        // Show this player to other online players
        for (Player online : GameHandler.getGameHandler().getPlugin().getServer().getOnlinePlayers()) {
            online.showPlayer(player);
        }
    }

    /**
     * The main event listener of this module that listen the deaths
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();

            // Stop the code if the player don't dies.
            double finalHealth = player.getHealth() - event.getDamage();
            if (finalHealth > 0.0) {
                return;
            }

            // We can change player's health by the x method, but only on the
            // server. We need cancel this event. Otherwise the damaged client
            // shows the default Minecraft death screen.
            event.setCancelled(true);

            this.deadPlayers.put(player.getUniqueId(), System.currentTimeMillis() + this.delay * 1000);

            // We need to handle PlayerDeathEvent because this module will break
            // many of Bukkit plugins.
            PlayerDeathEvent deathEvent = new PlayerDeathEvent(player, Arrays.asList(player.getInventory().getContents()), player.getExpToLevel(), 0, 0, 0, "%s died because of respawn");
            GameHandler.getGameHandler().getPlugin().getServer().getPluginManager().callEvent(deathEvent);

            // We need to broadcast this death-message too, because NMS server
            // is not invoked by the event.
            if (deathEvent.getDeathMessage() != null) {
                GameHandler.getGameHandler().getPlugin().getServer().broadcastMessage(String.format(deathEvent.getDeathMessage(), player.getDisplayName() + ChatColor.RESET));
            }

            // Hide this player from other online players
            for (Player online : GameHandler.getGameHandler().getPlugin().getServer().getOnlinePlayers()) {
                online.hidePlayer(player);
            }

            player.setGameMode(GameMode.CREATIVE);
            PlayerUtils.resetPlayer(player);

            if (this.blackout) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1));
            }

            if (!this.spectate) {
                Horse fakeHorse = GameHandler.getGameHandler().getMatchWorld().spawn(player.getLocation(), Horse.class);
                fakeHorse.setVariant(Horse.Variant.HORSE);
                for (PotionEffect potion : HORSE_POTIONS) fakeHorse.addPotionEffect(potion);
                fakeHorse.setPassenger(player);
            }

            // Send only a title (not sub-title) "You died!" to the player.
            // Subtitle should be sent by the run() method
            player.showTitle(TextComponent.fromLegacyText(ChatColor.RED + new LocalizedChatMessage(ChatConstant.UI_RESPAWN_DEAD).getMessage(player.getLocale())));
        }
    }

    /**
     * Event listener that listen to left mouse click to handle the respawn
     * It must be LOW because it MUST be invoked before the observers module
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
            // Handle only if the auto mode is not enabled. Otherwise they
            // should be auto respawned
            Player player = event.getPlayer();
            if (!this.auto && this.deadPlayers.containsKey(player.getUniqueId())) {
                if (this.canRespawn(player.getUniqueId())) {
                    this.respawnPlayer(player);
                }
            }
        }
    }

    /**
     * Cancel dismounting the horse when player is dead
     */
    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player && event.getVehicle().getType() == EntityType.HORSE && this.isDead(((Player) event.getExited()).getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Listen to match start to begin allow respawning players
     */
    @EventHandler
    public void onMatchStart(MatchStartEvent event) {
        GameHandler.getGameHandler().getPlugin().getServer().getScheduler().scheduleSyncRepeatingTask(GameHandler.getGameHandler().getPlugin(), this, 0L, 2L);
    }

}
