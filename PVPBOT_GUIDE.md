# NPC-Lib PvP Bot — Developer Guide

> **Target platform:** Bukkit / Paper 1.8 +  
> **Key classes:** `AgentAction` · `BotTickLoop`

This guide explains how to wire a neural-network (or any AI) model into
npc-lib to produce a bot that moves, jumps, sprints, attacks, blocks and
manages its hotbar like a real Minecraft player.

---

## Table of Contents

1. [Architecture overview](#1-architecture-overview)
2. [AgentAction — the discrete model contract](#2-agentaction--the-discrete-model-contract)
3. [Creating an NPC](#3-creating-an-npc)
4. [Implementing an action supplier](#4-implementing-an-action-supplier)
5. [Starting & stopping the tick loop](#5-starting--stopping-the-tick-loop)
6. [Movement & sprinting in depth](#6-movement--sprinting-in-depth)
7. [Gravity & jumping in depth](#7-gravity--jumping-in-depth)
8. [Attack, damage & knockback in depth](#8-attack-damage--knockback-in-depth)
9. [Blocking / using items](#9-blocking--using-items)
10. [Hotbar management](#10-hotbar-management)
11. [Full plugin example](#11-full-plugin-example)
12. [Constants reference](#12-constants-reference)

---

## 1. Architecture overview

```
  ┌──────────────────────────────────────────────────────────┐
  │  Your AI / model                                         │
  │   produces one AgentAction per tick                      │
  └──────────────────────┬───────────────────────────────────┘
                         │  Supplier<AgentAction>
                         ▼
  ┌──────────────────────────────────────────────────────────┐
  │  BotTickLoop   (BukkitRunnable, 1 tick period)           │
  │                                                          │
  │  ① Rotation          ④ Horizontal movement              │
  │  ② Jump impulse      ⑤ Sprint metadata packet           │
  │  ③ Gravity / ground  ⑥ Attack anim + damage + knockback │
  │                      ⑦ Blocking metadata packet          │
  │                      ⑧ Hotbar slot change               │
  └──────────────────────┬───────────────────────────────────┘
                         │  NPC packet API
                         ▼
  ┌──────────────────────────────────────────────────────────┐
  │  npc-lib Npc<World, Player, ItemStack, Plugin>           │
  │  (moveRelative · applyVelocity · changeMetadata …)       │
  └──────────────────────────────────────────────────────────┘
```

Your code only needs to:

1. Create and register an `Npc` with npc-lib as normal.
2. Provide a `Supplier<AgentAction>` that returns the model output every
   tick.
3. Construct a `BotTickLoop` and call `start()`.

---

## 2. AgentAction — the discrete model contract

`AgentAction` is an **immutable value object** that carries exactly one
tick of model output. Every field maps 1-to-1 to a real player key-press
or state bit.

| Field | Type | Description |
|---|---|---|
| `deltaYaw` | `float` | Rotation change in degrees (positive = turn right) |
| `deltaPitch` | `float` | Rotation change in degrees (positive = look down) |
| `moveForward` | `boolean` | W key |
| `moveBack` | `boolean` | S key |
| `moveLeft` | `boolean` | A key (strafe left) |
| `moveRight` | `boolean` | D key (strafe right) |
| `jump` | `boolean` | Space — only fires when NPC is on the ground |
| `sprint` | `boolean` | Sprint modifier (speed × 1.33) |
| `attack` | `boolean` | Left-click / arm swing |
| `useItem` | `boolean` | Right-click / raise shield |
| `hotbarSlot` | `int` | 0–8, sends `HeldItemChange` only when changed |

### Speed constants

| Constant | Value | Meaning |
|---|---|---|
| `AgentAction.WALK_SPEED` | `0.21` | blocks / tick while walking |
| `AgentAction.SPRINT_SPEED` | `0.28` | blocks / tick while sprinting |
| `AgentAction.JUMP_VELOCITY` | `0.42` | upward impulse in blocks / tick |

### Building an action with the fluent builder

```java
AgentAction action = AgentAction.builder()
    .deltaYaw(2.5f)          // turn 2.5° right this tick
    .moveForward(true)       // W pressed
    .sprint(true)            // holding sprint
    .attack(true)            // left-click
    .hotbarSlot(0)           // select slot 0 (sword)
    .build();
```

### Building an action from raw model output

```java
// Suppose your model returns a float[] of length 9:
// [forward, back, left, right, jump, sprint, attack, useItem, slot]
float[] raw = model.predict(observation);

AgentAction action = AgentAction.builder()
    .deltaYaw(computeDeltaYaw(raw))   // your own yaw logic
    .deltaPitch(computeDeltaPitch(raw))
    .moveForward(raw[0] > 0.5f)
    .moveBack   (raw[1] > 0.5f)
    .moveLeft   (raw[2] > 0.5f)
    .moveRight  (raw[3] > 0.5f)
    .jump       (raw[4] > 0.5f)
    .sprint     (raw[5] > 0.5f)
    .attack     (raw[6] > 0.5f)
    .useItem    (raw[7] > 0.5f)
    .hotbarSlot (Math.round(raw[8] * 8))
    .build();
```

---

## 3. Creating an NPC

Set up npc-lib's `Platform` and spawn an NPC as documented in the main
README. The only requirement for the bot is a
`Npc<World, Player, ItemStack, Plugin>` reference.

```java
import com.github.juliarn.npclib.api.Npc;
import com.github.juliarn.npclib.api.Platform;
import com.github.juliarn.npclib.api.Position;
import com.github.juliarn.npclib.api.profile.Profile;
import com.github.juliarn.npclib.bukkit.BukkitPlatform;

public class MyPlugin extends JavaPlugin {

    private Platform<World, Player, ItemStack, Plugin> platform;

    @Override
    public void onEnable() {
        // 1. Build the npc-lib platform
        this.platform = BukkitPlatform.bukkitNpcPlatformBuilder()
            .extension(this)
            .actionController(builder -> {}) // optional action controller
            .build();
    }

    /** Spawns a bot NPC at the given location for the given player. */
    public Npc<World, Player, ItemStack, Plugin> spawnBotNpc(
            Player viewer, Location spawnLoc) {

        // A pre-resolved skin profile (fetch from Mojang or reuse the
        // viewer's skin so it looks like a "mirror" opponent)
        Profile.Resolved botProfile = Profile.resolved(
            "BotPlayer",
            UUID.randomUUID(),
            viewer.getPlayerProfile().getProperties().stream()
                .map(p -> ProfileProperty.property(p.getName(),
                                                    p.getValue(),
                                                    p.getSignature()))
                .collect(java.util.stream.Collectors.toSet())
        );

        Position spawnPos = Position.position(
            spawnLoc.getX(), spawnLoc.getY(), spawnLoc.getZ(),
            spawnLoc.getYaw(), spawnLoc.getPitch(),
            spawnLoc.getWorld().getName()
        );

        return this.platform.newNpcBuilder()
            .position(spawnPos)
            .profile(botProfile)
            .buildAndTrack(); // registers and shows to nearby players
    }
}
```

---

## 4. Implementing an action supplier

`BotTickLoop` accepts any `Supplier<AgentAction>`.  Three common patterns:

### 4a. Random / scripted supplier (testing)

```java
import java.util.Random;
import com.github.juliarn.npclib.api.AgentAction;

public class RandomActionSupplier implements Supplier<AgentAction> {

    private final Random rng = new Random();

    @Override
    public AgentAction get() {
        return AgentAction.builder()
            .deltaYaw(rng.nextFloat() * 10 - 5)   // ±5° per tick
            .moveForward(rng.nextBoolean())
            .sprint(rng.nextFloat() > 0.7f)
            .attack(rng.nextFloat() > 0.8f)
            .hotbarSlot(0)
            .build();
    }
}
```

### 4b. Neural-network supplier (production)

```java
/**
 * Example: wraps a hypothetical Python-trained ONNX model loaded via
 * Microsoft's ONNX Runtime for Java.
 */
public class NeuralNetActionSupplier implements Supplier<AgentAction> {

    private final OrtSession session;   // ONNX Runtime session
    private final Player target;
    private final Npc<?, ?, ?, ?> npc;

    public NeuralNetActionSupplier(OrtSession session,
                                   Player target,
                                   Npc<?, ?, ?, ?> npc) {
        this.session = session;
        this.target  = target;
        this.npc     = npc;
    }

    @Override
    public AgentAction get() {
        // Build observation vector from current game state
        float[] obs = buildObservation();

        // Run model inference
        float[] output;
        try (OrtSession.Result result = session.run(
                Map.of("obs", OnnxTensor.createTensor(
                        OrtEnvironment.getEnvironment(), new float[][]{obs})))) {
            output = ((float[][]) result.get(0).getValue())[0];
        } catch (OrtException e) {
            throw new RuntimeException(e);
        }

        // output indices: 0=fwd 1=back 2=left 3=right 4=jump
        //                 5=sprint 6=attack 7=useItem 8=slot
        return AgentAction.builder()
            .deltaYaw (output[9])
            .deltaPitch(output[10])
            .moveForward(output[0] > 0.5f)
            .moveBack   (output[1] > 0.5f)
            .moveLeft   (output[2] > 0.5f)
            .moveRight  (output[3] > 0.5f)
            .jump       (output[4] > 0.5f)
            .sprint     (output[5] > 0.5f)
            .attack     (output[6] > 0.5f)
            .useItem    (output[7] > 0.5f)
            .hotbarSlot (Math.min(8, Math.max(0, Math.round(output[8] * 8))))
            .build();
    }

    private float[] buildObservation() {
        Position npcPos    = this.npc.position();
        Location targetLoc = this.target.getLocation();

        // Relative displacement (dx, dy, dz), NPC yaw/pitch, target health …
        return new float[]{
            (float)(targetLoc.getX() - npcPos.x()),
            (float)(targetLoc.getY() - npcPos.y()),
            (float)(targetLoc.getZ() - npcPos.z()),
            npcPos.yaw() / 180f,
            npcPos.pitch() / 90f,
            (float)(this.target.getHealth() / this.target.getMaxHealth())
            // … add more features as needed
        };
    }
}
```

---

## 5. Starting & stopping the tick loop

```java
import com.github.juliarn.npclib.bukkit.BotTickLoop;

// Start
BotTickLoop loop = new BotTickLoop(
    plugin,           // your JavaPlugin instance
    npc,              // the Npc<World, Player, ItemStack, Plugin>
    targetPlayer,     // the human opponent
    actionSupplier    // Supplier<AgentAction>
);
loop.start();   // schedules runTaskTimer(plugin, 1L, 1L)

// Stop (e.g. when the match ends or the player disconnects)
loop.stop();    // calls cancel() on the BukkitRunnable
```

> **Thread safety:** `start()` and `stop()` must be called from the main
> server thread (or a region thread on Folia). `stop()` is safe to call
> from any thread.

### Stopping the loop when the target dies or disconnects

```java
@EventHandler
public void onPlayerDeath(PlayerDeathEvent event) {
    BotTickLoop loop = activeLoops.get(event.getEntity().getUniqueId());
    if (loop != null) {
        loop.stop();
        activeLoops.remove(event.getEntity().getUniqueId());
    }
}

@EventHandler
public void onPlayerQuit(PlayerQuitEvent event) {
    BotTickLoop loop = activeLoops.get(event.getPlayer().getUniqueId());
    if (loop != null) {
        loop.stop();
        activeLoops.remove(event.getPlayer().getUniqueId());
    }
}
```

---

## 6. Movement & sprinting in depth

Every tick `BotTickLoop` calls `action.computeMovementVector(newYaw)` to
convert the four boolean keys into a world-space `[velX, velZ]` pair.

### What happens inside `computeMovementVector`

```
1.  localX = moveRight - moveLeft     (range −1..+1)
    localZ = moveForward - moveBack   (range −1..+1)

2.  Normalise to unit circle:
    len = sqrt(localX² + localZ²)
    if len > 0 → localX /= len, localZ /= len

3.  Apply speed:
    speed = sprint ? SPRINT_SPEED : WALK_SPEED
    localX *= speed,  localZ *= speed

4.  Rotate by NPC yaw (Minecraft convention: 0°=south, 90°=west):
    worldX = localZ × (−sin yaw) + localX × cos yaw
    worldZ = localZ × ( cos yaw) + localX × sin yaw
```

The resulting `worldX` / `worldZ` are passed to:

```java
npc.moveRelative(velX, dy, velZ, newYaw, newPitch).scheduleForTracked();
npc.applyVelocity(velX, velocityY, velZ).scheduleForTracked();
```

**Sprint metadata** is sent every tick regardless of whether the NPC is
actually moving:

```java
npc.changeMetadata(
    EntityMetadataFactory.sprintingMetaFactory(),
    action.sprint()
).scheduleForTracked();
```

---

## 7. Gravity & jumping in depth

`BotTickLoop` maintains a `velocityY` field (server-side only) and applies
vanilla-accurate gravity each tick:

```
if (!onGround):
    velocityY = max(−3.92, (velocityY − 0.08) × 0.98)
```

### Ground detection

```java
// Feet-level solid block test after applying dy
world.getBlockAt(floor(x), floor(y + dy), floor(z)).getType().isSolid()
```

When the NPC lands the loop snaps it to `floor(currentY)`, resets
`velocityY = 0` and sets `onGround = true`.

### Jump sequence

```
tick N:  action.jump() == true  &&  onGround == true
         → velocityY = +0.42
         → onGround = false
         → applyVelocity(0, 0.42, 0) sent to client

tick N+1..N+k:  gravity pulls velocityY down
                moveRelative includes dy each tick

tick N+k+1:  NPC lands → velocityY = 0, onGround = true
```

Jumping while already in the air (`onGround == false`) is silently
ignored, matching real player behaviour.

---

## 8. Attack, damage & knockback in depth

### Animation

When `action.attack()` is `true`, the arm-swing animation is sent every
tick the button is held. This is purely cosmetic:

```java
npc.attack().scheduleForTracked();   // → SWING_MAIN_ARM animation packet
```

### Hit validation (geometry)

Real damage is only applied when **both** conditions are met:

| Condition | Value |
|---|---|
| Distance to target | ≤ 3.0 blocks (3D Euclidean) |
| Target in front of NPC | dot(forward, toTarget) > 0 (within ±90° yaw) |

```java
// Distance check
double distSq = dx*dx + dy*dy + dz*dz;
if (distSq > 3.0 * 3.0) return false;

// Facing check (horizontal plane)
double forwardX = -Math.sin(Math.toRadians(npcYaw));
double forwardZ =  Math.cos(Math.toRadians(npcYaw));
double dot = forwardX * (dx / hDist) + forwardZ * (dz / hDist);
if (dot <= 0) return false;
```

### Hit cooldown

To prevent dealing damage every single tick a 20-tick (1 second) cooldown
is enforced:

```java
if (hitCooldownRemaining == 0 && isValidHit(current, newYaw)) {
    applyDamageAndKnockback(newYaw);
    hitCooldownRemaining = 20;   // reset
}
// cooldown ticks down every tick regardless
if (hitCooldownRemaining > 0) hitCooldownRemaining--;
```

> Change `HIT_COOLDOWN_TICKS` to `10` in the source to simulate
> 1.8-style CPS behaviour.

### Damage & knockback

```java
target.damage(1.0);   // 1.0 half-hearts = 2 HP, runs through armour/enchants

// Knockback in NPC's forward direction
double kbX = -Math.sin(Math.toRadians(npcYaw)) * 0.4;
double kbZ =  Math.cos(Math.toRadians(npcYaw)) * 0.4;

Vector v = target.getVelocity();
v.setX(v.getX() / 2.0 + kbX);
v.setY(0.3);                        // standard upward knock
v.setZ(v.getZ() / 2.0 + kbZ);
target.setVelocity(v);
```

---

## 9. Blocking / using items

`action.useItem()` toggles the hand-states metadata byte (bit `0x01`)
which makes the NPC visually raise its shield or begin eating:

```java
npc.changeMetadata(
    EntityMetadataFactory.blockingMetaFactory(),
    action.useItem()
).scheduleForTracked();
```

This is sent every tick, so the model can toggle blocking freely without
any extra state tracking on the caller's side.

---

## 10. Hotbar management

`BotTickLoop` tracks `prevHotbarSlot` and only sends the `HeldItemChange`
packet when the slot index actually changes:

```java
int newSlot = action.hotbarSlot();     // 0–8
if (newSlot != this.prevHotbarSlot) {
    npc.selectHotbarSlot(newSlot).scheduleForTracked();
    // Optionally refresh the visible main-hand item:
    // npc.changeItem(ItemSlot.MAIN_HAND, hotbarItems[newSlot]).scheduleForTracked();
    this.prevHotbarSlot = newSlot;
}
```

To make the item swap visible you should maintain a `ItemStack[]
hotbarItems` array in your own code and call `npc.changeItem` when the
slot changes:

```java
ItemStack[] hotbarItems = new ItemStack[9];
hotbarItems[0] = new ItemStack(Material.DIAMOND_SWORD);
hotbarItems[1] = new ItemStack(Material.BOW);
// … fill remaining slots

// Then inside your Supplier or a custom subclass:
if (newSlot != prevHotbarSlot) {
    npc.selectHotbarSlot(newSlot).scheduleForTracked();
    npc.changeItem(ItemSlot.MAIN_HAND, hotbarItems[newSlot]).scheduleForTracked();
}
```

---

## 11. Full plugin example

Below is a self-contained `JavaPlugin` that spawns a bot NPC when a
player runs `/startbot` and stops it on `/stopbot`.

```java
import com.github.juliarn.npclib.api.*;
import com.github.juliarn.npclib.api.profile.*;
import com.github.juliarn.npclib.api.protocol.enums.ItemSlot;
import com.github.juliarn.npclib.bukkit.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.Supplier;

public class PvpBotPlugin extends JavaPlugin implements CommandExecutor {

    // npc-lib platform
    private Platform<World, Player, ItemStack, Plugin> platform;

    // Active bot loops indexed by the human player's UUID
    private final Map<UUID, BotTickLoop> activeBots = new HashMap<>();
    // The spawned NPC for each human player
    private final Map<UUID, Npc<World, Player, ItemStack, Plugin>> activeNpcs = new HashMap<>();

    // -------------------------------------------------------------------------
    // Plugin lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        this.platform = BukkitPlatform.bukkitNpcPlatformBuilder()
            .extension(this)
            .build();

        getCommand("startbot").setExecutor(this);
        getCommand("stopbot").setExecutor(this);
    }

    @Override
    public void onDisable() {
        // Stop all active loops and despawn NPCs
        activeBots.values().forEach(BotTickLoop::stop);
        activeNpcs.values().forEach(Npc::unlink);
    }

    // -------------------------------------------------------------------------
    // Command handler
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Player only command.");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("startbot")) {
            startBot(player);
        } else if (cmd.getName().equalsIgnoreCase("stopbot")) {
            stopBot(player);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Bot management
    // -------------------------------------------------------------------------

    private void startBot(Player player) {
        if (activeBots.containsKey(player.getUniqueId())) {
            player.sendMessage("§cBot already running!");
            return;
        }

        // 1. Build a profile that mirrors the player's skin
        Set<ProfileProperty> skinProps = new HashSet<>();
        player.getPlayerProfile().getProperties().forEach(prop ->
            skinProps.add(ProfileProperty.property(
                prop.getName(), prop.getValue(), prop.getSignature())));

        Profile.Resolved botProfile = Profile.resolved(
            "PvP-Bot",
            UUID.randomUUID(),
            skinProps
        );

        // 2. Spawn the NPC 3 blocks in front of the player
        Location spawnLoc = player.getLocation().add(
            player.getLocation().getDirection().multiply(3));

        Position spawnPos = Position.position(
            spawnLoc.getX(), spawnLoc.getY(), spawnLoc.getZ(),
            spawnLoc.getYaw() + 180f,   // face the player
            0f,
            spawnLoc.getWorld().getName()
        );

        Npc<World, Player, ItemStack, Plugin> npc = this.platform.newNpcBuilder()
            .position(spawnPos)
            .profile(botProfile)
            .buildAndTrack();

        // Equip the bot with a diamond sword in the main hand
        npc.changeItem(ItemSlot.MAIN_HAND,
            new ItemStack(Material.DIAMOND_SWORD)).scheduleForTracked();

        // 3. Create the action supplier
        Supplier<AgentAction> supplier = new SimpleChaserSupplier(npc, player);

        // 4. Start the tick loop
        BotTickLoop loop = new BotTickLoop(this, npc, player, supplier);
        loop.start();

        activeBots.put(player.getUniqueId(), loop);
        activeNpcs.put(player.getUniqueId(), npc);
        player.sendMessage("§aBot spawned — good luck!");
    }

    private void stopBot(Player player) {
        BotTickLoop loop = activeBots.remove(player.getUniqueId());
        if (loop == null) {
            player.sendMessage("§cNo bot running.");
            return;
        }
        loop.stop();

        Npc<?, ?, ?, ?> npc = activeNpcs.remove(player.getUniqueId());
        if (npc != null) npc.unlink();

        player.sendMessage("§6Bot stopped and removed.");
    }

    // -------------------------------------------------------------------------
    // Simple scripted supplier: chases and attacks the target
    // -------------------------------------------------------------------------

    /**
     * A rule-based supplier that always moves toward the target, sprints
     * when far, attacks whenever facing the opponent, and blocks randomly.
     */
    private static final class SimpleChaserSupplier
            implements Supplier<AgentAction> {

        private final Npc<?, ?, ?, ?> npc;
        private final Player target;
        private int tickCounter = 0;

        SimpleChaserSupplier(Npc<?, ?, ?, ?> npc, Player target) {
            this.npc    = npc;
            this.target = target;
        }

        @Override
        public AgentAction get() {
            tickCounter++;
            Position npcPos    = this.npc.position();
            Location targetLoc = this.target.getLocation();

            // ---- Rotation: look toward the target ----------------------------
            double dx = targetLoc.getX() - npcPos.x();
            double dz = targetLoc.getZ() - npcPos.z();
            double dy = targetLoc.getY() - npcPos.y();

            float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float deltaYaw   = normaliseAngle(desiredYaw - npcPos.yaw());

            double horizDist  = Math.sqrt(dx * dx + dz * dz);
            float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizDist));
            float deltaPitch   = desiredPitch - npcPos.pitch();

            // ---- Movement: always move forward (toward target) ---------------
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean moveForward = distance > 1.5;   // stop when very close
            boolean sprint      = distance > 4.0;   // sprint when far

            // ---- Attack: swing when within reach -----------------------------
            boolean attack = distance < 3.5;

            // ---- Block occasionally ------------------------------------------
            boolean block = (tickCounter % 40) < 10; // block for 0.5 s every 2 s

            // ---- Jump: jump over obstacles occasionally ----------------------
            boolean jump = (tickCounter % 30 == 0);

            return AgentAction.builder()
                .deltaYaw   (clamp(deltaYaw,  -15f, 15f)) // max 15°/tick turn
                .deltaPitch (clamp(deltaPitch, -10f, 10f))
                .moveForward(moveForward)
                .sprint     (sprint)
                .attack     (attack)
                .useItem    (block)
                .jump       (jump)
                .hotbarSlot (0)   // always hold the sword (slot 0)
                .build();
        }

        /** Wraps an angle difference into the range (−180, +180]. */
        private static float normaliseAngle(float angle) {
            while (angle >  180f) angle -= 360f;
            while (angle < -180f) angle += 360f;
            return angle;
        }

        private static float clamp(float v, float min, float max) {
            return Math.max(min, Math.min(max, v));
        }
    }
}
```

---

## 12. Constants reference

### `AgentAction` constants

| Constant | Value | Description |
|---|---|---|
| `WALK_SPEED` | `0.21` | Movement speed while walking (blocks/tick) |
| `SPRINT_SPEED` | `0.28` | Movement speed while sprinting (blocks/tick) |
| `JUMP_VELOCITY` | `0.42` | Upward velocity applied on jump (blocks/tick) |

### `BotTickLoop` internal constants

| Constant | Value | Description |
|---|---|---|
| `GRAVITY` | `0.08` | Y-velocity decrease per tick (blocks/tick²) |
| `DRAG` | `0.98` | Y-velocity air-drag multiplier per tick |
| `MAX_FALL_SPEED` | `−3.92` | Terminal velocity (blocks/tick) |
| `PLAYER_HEIGHT` | `1.8` | NPC model height for head-collision check |
| `HIT_RANGE` | `3.0` | Maximum melee reach (blocks) |
| `HIT_COOLDOWN_TICKS` | `20` | Ticks between damage applications (≈ 1 s) |
| `HIT_DAMAGE` | `1.0` | Damage per hit in half-hearts (=2 HP) |
| `KNOCKBACK_H` | `0.4` | Horizontal knockback magnitude (blocks/tick) |
| `KNOCKBACK_V` | `0.3` | Vertical knockback component (blocks/tick) |

---

*Generated for npc-lib fork — PvP bot discrete model — February 2026.*

