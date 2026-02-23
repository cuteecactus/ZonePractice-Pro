package dev.nandi0813.practice;

import com.github.juliarn.npclib.api.AgentAction;
import com.github.juliarn.npclib.api.Npc;
import com.github.juliarn.npclib.api.NpcActionController;
import com.github.juliarn.npclib.api.Platform;
import com.github.juliarn.npclib.api.profile.Profile;
import com.github.juliarn.npclib.api.protocol.enums.ItemSlot;
import com.github.juliarn.npclib.api.protocol.meta.EntityMetadataFactory;
import com.github.juliarn.npclib.bukkit.BukkitPlatform;
import com.github.juliarn.npclib.bukkit.util.BukkitPlatformUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import static org.bukkit.Bukkit.getServer;

public class Test {

    // Bukkit example — called from a repeating BukkitTask (every tick = 50 ms)
    private final @NotNull Platform<World, Player, ItemStack, Plugin> platform = BukkitPlatform
        .bukkitNpcPlatformBuilder()
        .extension(ZonePractice.getInstance())
        .actionController(builder -> builder.flag(NpcActionController.SPAWN_DISTANCE, 64))
        .build();

      public void startWalking(Npc<World, Player, ItemStack, Plugin> npc) {
        // Walk the NPC forward along the X axis at half-speed (0.13 blocks/tick ≈ sprinting speed)
        getServer().getScheduler().runTaskTimer(ZonePractice.getInstance(), () -> {
          float yaw = npc.position().yaw();
          double dx = Math.cos(Math.toRadians(yaw + 90)) * 0.13;
          double dz = Math.sin(Math.toRadians(yaw + 90)) * 0.13;

          // Move + rotate — sent to every player currently watching the NPC
          npc.moveRelative(dx, 0, dz, yaw, 0f).scheduleForTracked();

          // Keep the sprinting animation playing so legs move at the right speed
          npc.changeMetadata(EntityMetadataFactory.sprintingMetaFactory(), true).scheduleForTracked();
        }, 0L, 1L);
      }

      public void jumpNpc(Npc<World, Player, ItemStack, Plugin> npc) {
        // 0.42 blocks/tick upward — the standard Minecraft jump velocity
        npc.applyVelocity(0, 0.42, 0).scheduleForTracked();
      }

      public void knockback(Npc<World, Player, ItemStack, Plugin> npc, double directionYaw) {
        double vx = -Math.cos(Math.toRadians(directionYaw + 90)) * 0.4;
        double vz = -Math.sin(Math.toRadians(directionYaw + 90)) * 0.4;
        npc.applyVelocity(vx, 0.35, vz).scheduleForTracked();
      }

      public void attackTarget(Npc<World, Player, ItemStack, Plugin> npc) {
        // Swing main arm (left-click animation)
        npc.attack().scheduleForTracked();
      }

      public void startBlocking(Npc<World, Player, ItemStack, Plugin> npc) {
        // Raise shield / begin eating — sets the hand-active metadata flag
        npc.changeMetadata(EntityMetadataFactory.blockingMetaFactory(), true).scheduleForTracked();
      }

      public void stopBlocking(Npc<World, Player, ItemStack, Plugin> npc) {
        npc.changeMetadata(EntityMetadataFactory.blockingMetaFactory(), false).scheduleForTracked();
      }

      public void switchToSword(Npc<World, Player, ItemStack, Plugin> npc, ItemStack sword) {
        npc.selectHotbarSlot(0).scheduleForTracked();                         // record slot 0
        npc.changeItem(ItemSlot.MAIN_HAND, sword).scheduleForTracked();       // show the sword
      }

      public void startBot(Location spawnLoc) {
        // 1. Spawn the NPC
        this.platform.newNpcBuilder()
          .position(BukkitPlatformUtil.positionFromBukkitLegacy(spawnLoc))
          .profile(Profile.unresolved("PvpBot"))
          .thenAccept(builder -> {
            Npc<World, Player, ItemStack, Plugin> npc = builder.buildAndTrack();

            // Equip with a sword on spawn
            getServer().getScheduler().runTask(ZonePractice.getInstance(), () -> {
              npc.changeItem(ItemSlot.MAIN_HAND, new ItemStack(Material.DIAMOND_SWORD))
                .scheduleForTracked();
            });

            // 2. Run the bot loop every tick
            getServer().getScheduler().runTaskTimer(ZonePractice.getInstance(), () -> {

              // 3. Build an AgentAction (replace with actual AI model output)
              AgentAction action = AgentAction.builder()
                .deltaYaw(0f)
                .deltaPitch(0f)
                .velX(0.0)
                .velZ(0.0)
                .sprint(false)
                .attack(false)
                .selectedSlot(0)
                .build();

              // 4. Apply all packets — move, rotate, attack, sprint, block, velocity
              action.applyTo(npc);

            }, 0L, 1L /* every tick */);
          });
      }

}
