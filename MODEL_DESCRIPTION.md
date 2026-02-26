# PvP Bot Simple — Model Output & API Documentation

This document describes the inference API contract between the Python ML server
(`pvp_api_simple.py`) and the Java Minecraft client (`PvPBotClient.java`).

---

## Overview

The bot uses a **GRU (Gated Recurrent Unit)** neural network that reads the
last **10 game ticks (~0.5 seconds)** of state and outputs a set of discrete
actions for the **next tick**.

The Java client calls `POST /predict` every game tick, receives a flat JSON
object, and translates it directly into key presses and Minecraft actions.

---

## Server Information

| Property | Value |
|---|---|
| Default host | `localhost` |
| Default port | `8000` |
| Protocol | HTTP/1.1 |
| Content-Type | `application/json` |
| Main endpoint | `POST /predict` |

---

## Request Format

### `POST /predict`

Send a JSON body with **exactly 10 frames** (oldest first, newest last).

```json
{
  "frames": [
    {
      "values": { ... raw game values ... },
      "hotbar": [0, 1, 0, 0, 0, 0, 0, 0, 0, 1]
    },
    ...
  ]
}
```

### Frame Fields

Each frame has two fields:

#### `values` (object) — Raw game state

Pass raw values directly from the game. The server normalises them automatically.

| Key | Type | Source (CSV column) | Notes |
|---|---|---|---|
| `health` | float | `health` | Current HP (0–20) |
| `max_health` | float | `max_health` | Max HP (usually 20) |
| `vel_x` | float | `vel_x` | X velocity (blocks/tick) |
| `vel_y` | float | `vel_y` | Y velocity |
| `vel_z` | float | `vel_z` | Z velocity |
| `yaw` | float | `yaw` | Horizontal look angle (−180 to 180) |
| `pitch` | float | `pitch` | Vertical look angle (−90 to 90) |
| `food_level` | int | `food_level` | Hunger (0–20) |
| `total_armor` | float | `total_armor` | Armour points sum (0–20) |
| `target_distance` | float | `target_distance` | Distance to opponent. Use **−1** if no opponent |
| `target_rel_x` | float | `target_rel_x` | Opponent X offset from player |
| `target_rel_y` | float | `target_rel_y` | Opponent Y offset |
| `target_rel_z` | float | `target_rel_z` | Opponent Z offset |
| `target_health` | float | `target_health` | Opponent HP (0–20). Use **0** if no opponent |
| `attack_cooldown` | int | `attack_cooldown` | Weapon charge 0–100 (100 = fully charged) |
| `item_use_duration` | int | `item_use_duration` | Remaining ticks of item use (0 if none) |
| `is_on_ground` | bool | `is_on_ground` | `true` / `false` |
| `is_jumping` | bool | `is_jumping` | `true` / `false` |
| `is_sprinting` | bool | `is_sprinting` | `true` / `false` |
| `is_sneaking` | bool | `is_sneaking` | `true` / `false` |
| `has_speed` | bool | `has_speed` | Speed potion active |
| `has_strength` | bool | `has_strength` | Strength potion active |
| `has_regeneration` | bool | `has_regeneration` | Regen potion active |
| `has_poison` | bool | `has_poison` | Poison effect active |
| `is_using_item` | bool | `is_using_item` | Currently eating/drinking/using |
| `target_is_blocking` | bool | `target_is_blocking` | Opponent is blocking with shield |
| `selected_slot` | int | `selected_slot` | Currently active hotbar slot (0–8) |

#### `hotbar` (array of 10 ints)

Vocabulary IDs for the 10 hotbar-related item slots:

```
[inv_0, inv_1, inv_2, inv_3, inv_4, inv_5, inv_6, inv_7, inv_8, main_hand]
```

- Use `GET /vocab` to get the full `{ "item_name": id }` mapping.
- **AIR / empty slot → `0`** (always).
- Strip the `:amount` part (e.g. `"ENCHANTED_GOLDEN_APPLE:3"` → look up `"ENCHANTED_GOLDEN_APPLE"`).

> **Tip:** Cache the vocabulary at startup and update it only if the server returns
> a new `vocab_size` on `/vocab_size`.

---

## Response Format

### `PredictResponse`

```json
{
  "move_forward": true,
  "move_back":    false,
  "move_left":    false,
  "move_right":   false,
  "jump":         false,
  "sprint":       true,
  "attack":       true,
  "use_item":     false,
  "hotbar_slot":  0,
  "confidences":  null
}
```

### Field Descriptions

| Field | Type | Minecraft Action |
|---|---|---|
| `move_forward` | bool | Hold **W** key |
| `move_back` | bool | Hold **S** key |
| `move_left` | bool | Hold **A** key |
| `move_right` | bool | Hold **D** key |
| `jump` | bool | Press **Space** |
| `sprint` | bool | Toggle/hold sprint (`Ctrl` or double-tap W) |
| `attack` | bool | Left-click (swing weapon, deal damage) |
| `use_item` | bool | Right-click (eat food, drink potion, use bow, etc.) |
| `hotbar_slot` | int (0–8) | Switch to this hotbar slot (`inv_0` = 0 … `inv_8` = 8) |
| `confidences` | object \| null | Only present if `include_confidences=true` query param is set |

### Important Implementation Notes

1. **`move_forward` + `move_back` can both be `false`** — the bot is standing still
   horizontally. Never force movement if both are false.

2. **`move_forward` and `move_back` will rarely both be `true`** at the same time
   (contradictory). If they are, prefer `move_forward` (treat as a model uncertainty).

3. **`hotbar_slot` is always present** (0–8). Even if no slot switch is needed,
   the value reflects the model's preferred slot. You can apply it every tick
   (Minecraft ignores slot switches if the slot is already active).

4. **`attack` should only fire when the weapon is reasonably charged** — the model
   is trained on human data where players don't click with 0% cooldown. As a
   safety guard, only actually swing if `attack_cooldown ≥ 80` (or let the model
   decide entirely and accept occasional wasted clicks).

5. **`use_item` = `true`** means the player should activate the currently held item.
   The Java client should call right-click on the item in `hotbar_slot`. If the item
   is a consumable (food, potion), the client holds the right-click until
   `is_using_item` becomes `true` in the game state.

6. **`sprint` + `move_forward` together** enables sprint. Sprint alone without
   `move_forward` is a no-op in vanilla Minecraft — still apply it anyway as the
   model reflects player intention.

---

## Optional: Confidence Scores

Add `?include_confidences=true` to the request URL:

```
POST /predict?include_confidences=true
```

Response will include a `confidences` object:

```json
{
  "move_forward": true,
  ...
  "confidences": {
    "move_forward": 0.8712,
    "move_back":    0.0411,
    "move_left":    0.1023,
    "move_right":   0.3214,
    "jump":         0.1190,
    "sprint":       0.9123,
    "attack":       0.7654,
    "use_item":     0.0234,
    "hotbar_probs": [-3.1, 0.0, -1.2, -2.5, -4.0, -3.8, -2.1, -1.9, -3.3]
  }
}
```

Confidence values are sigmoid probabilities (0.0–1.0). `hotbar_probs` are
relative log-odds (not probabilities), useful only for debugging.

---

## Utility Endpoints

| Endpoint | Method | Description |
|---|---|---|
| `/health` | GET | `{ "status": "ok", "model_loaded": true }` |
| `/vocab` | GET | `{ "vocab": {"AIR": 0, "NETHERITE_SWORD": 1, ...}, "vocab_size": N }` |
| `/col_order` | GET | Canonical feature column orders |
| `/norm_stats` | GET | Per-column mean/std used for normalisation |
| `/sequence_length` | GET | `{ "sequence_length": 10 }` |

---

## Java Client Integration Guide

### Startup

```java
// 1. Fetch vocabulary once at startup
Map<String, Integer> itemVocab = fetchVocab(); // GET /vocab

// 2. Initialise a circular buffer for the last 10 frames
Deque<GameFrame> frameBuffer = new ArrayDeque<>(10);
```

### Per-Tick Loop

```java
// 3. Snapshot current game state
GameFrame frame = new GameFrame();
frame.values.put("health", player.getHealth());
frame.values.put("max_health", player.getMaxHealth());
frame.values.put("vel_x", player.getVelocity().x);
frame.values.put("vel_y", player.getVelocity().y);
frame.values.put("vel_z", player.getVelocity().z);
frame.values.put("yaw", player.getYaw());
frame.values.put("pitch", player.getPitch());
// ... fill all fields from the table above ...

// Hotbar item IDs
int[] hotbar = new int[10];
for (int i = 0; i < 9; i++) {
    String itemName = getHotbarItemName(player, i);  // strip ":amount"
    hotbar[i] = itemVocab.getOrDefault(itemName, 0); // 0 = AIR
}
hotbar[9] = itemVocab.getOrDefault(getMainHandName(player), 0);
frame.hotbar = hotbar;

// 4. Add to rolling buffer (keep exactly 10 frames)
if (frameBuffer.size() >= 10) frameBuffer.pollFirst();
frameBuffer.addLast(frame);

// 5. Only call API once buffer is full
if (frameBuffer.size() < 10) return;

// 6. POST /predict
PredictResponse response = callPredict(new ArrayList<>(frameBuffer));

// 7. Apply actions
if (response.sprint)       pressKey(KeyBinding.SPRINT);
if (response.move_forward) pressKey(KeyBinding.FORWARD);
if (response.move_back)    pressKey(KeyBinding.BACK);
if (response.move_left)    pressKey(KeyBinding.LEFT);
if (response.move_right)   pressKey(KeyBinding.RIGHT);
if (response.jump)         pressKey(KeyBinding.JUMP);

// Switch slot (safe to do every tick)
player.selectHotbarSlot(response.hotbar_slot);

// Attack guard: only swing if cooldown is high enough
if (response.attack && player.getAttackCooldownProgress() >= 0.8f) {
    doLeftClick();
}

// Use item
if (response.use_item) {
    doRightClick();
}
```

### Key Binding Notes

- **Move keys** (`W/A/S/D`): Press and release within the same tick handler. The
  game reads movement intent as instantaneous key-down at tick start.
- **Jump**: Press Space once per tick where `jump = true`. Do not hold.
- **Sprint**: Toggle sprint mode ON when `sprint = true`, OFF when `false`.
- **Attack**: Trigger a single left-click event. Do not repeat-click.
- **Use item**: Hold right-click as long as `use_item` remains `true` in
  subsequent responses and `is_using_item` in the game state is `true`.

---

## Model Architecture Summary

| Property | Value |
|---|---|
| Architecture | GRU (Gated Recurrent Unit) |
| Input window | 10 ticks (~0.5 seconds) |
| Continuous features | 16 (z-score normalised) |
| Binary features | 19 (on/off flags + slot one-hot) |
| Hotbar item slots | 10 (embedded with shared lookup table) |
| Embedding dimension | 16 per item slot |
| GRU hidden size | 256 |
| Total input dim per tick | 195 |
| Output heads | 8 binary + 1 nine-class |

### Continuous Features (16)

| Feature | Description |
|---|---|
| `health_ratio` | `health / max_health` |
| `vel_x`, `vel_y`, `vel_z` | Player velocity (blocks/tick) |
| `sin_yaw`, `cos_yaw` | Yaw encoded to avoid wrap-around discontinuity |
| `pitch` | Vertical look angle |
| `food_ratio` | `food_level / 20` |
| `armor_ratio` | `total_armor / 20` |
| `target_distance` | Distance to opponent (−1 replaced by 32) |
| `target_rel_x/y/z` | Opponent position offset from player |
| `target_health_ratio` | `target_health / 20` |
| `attack_cooldown_norm` | `attack_cooldown / 100` |
| `item_use_norm` | `item_use_duration / 72` |

### Binary Features (19)

`is_on_ground`, `is_jumping`, `is_sprinting`, `is_sneaking`, `has_speed`,
`has_strength`, `has_regeneration`, `has_poison`, `is_using_item`,
`target_is_blocking`, plus 9-bit one-hot of `selected_slot`.

---

---

## Full Input Contract (Java Client → Python Server)

This section describes **exactly** what the Java client must send on every tick,
how the server interprets it, and what happens internally before the model sees it.

---

### High-Level Pipeline

```
Java game tick
    │
    ▼
Snapshot raw Minecraft values  (health, velocity, yaw, …)
    │
    ▼
Build JSON request body        (10 frames, oldest → newest)
    │
    ▼
POST /predict                  (HTTP to localhost:8000)
    │
    ▼  [SERVER SIDE]
Derive 16 continuous features  (ratios, sin/cos yaw, normalised values)
Apply z-score normalisation    (subtract mean, divide by std from training)
Derive 19 binary features      (flags + selected_slot one-hot)
Look up 10 hotbar item IDs     (from vocabulary)
    │
    ▼
GRU forward pass               (10 ticks × 195 dims → 9 action heads)
    │
    ▼
JSON response → Java client    (9 action booleans + hotbar_slot int)
```

---

### Request Body Structure

```json
{
  "frames": [
    { "values": { … }, "hotbar": [ 1, 0, 0, 0, 0, 0, 0, 0, 0, 1 ] },
    { "values": { … }, "hotbar": [ 1, 0, 0, 0, 0, 0, 0, 0, 0, 1 ] },
    …  (10 frames total, oldest first)
  ]
}
```

- **`frames`**: array of exactly **10** objects, ordered **oldest to newest**
  (index 0 = 10 ticks ago, index 9 = current tick).
- Each frame has two fields: `values` and `hotbar`.

---

### `values` — Raw Game State (27 keys)

Pass raw Minecraft values **exactly as the game reports them**.
The server does all normalisation and feature derivation internally.

#### Numeric fields (13)

| JSON Key | Java Source | Unit / Range | Default if missing |
|---|---|---|---|
| `health` | `player.getHealth()` | float, 0–20 | `20.0` |
| `max_health` | `player.getMaxHealth()` | float, usually 20 | `20.0` |
| `vel_x` | `player.getVelocity().x` | blocks/tick, ~−0.6 to 0.6 | `0.0` |
| `vel_y` | `player.getVelocity().y` | blocks/tick, ~−1.0 to 0.42 | `0.0` |
| `vel_z` | `player.getVelocity().z` | blocks/tick, ~−0.6 to 0.6 | `0.0` |
| `yaw` | `player.getYaw()` | degrees, −180 to 180 | `0.0` |
| `pitch` | `player.getPitch()` | degrees, −90 to 90 | `0.0` |
| `food_level` | `player.getHungerManager().getFoodLevel()` | int, 0–20 | `20` |
| `total_armor` | sum of all equipped armour points | float, 0–20 | `0.0` |
| `target_distance` | distance to nearest opponent | float, ≥ 0; **use −1 if no opponent** | `−1` |
| `target_rel_x` | `opponent.x − player.x` | float | `0.0` |
| `target_rel_y` | `opponent.y − player.y` | float | `0.0` |
| `target_rel_z` | `opponent.z − player.z` | float | `0.0` |
| `target_health` | `opponent.getHealth()` | float, 0–20; **use 0 if no opponent** | `0.0` |
| `attack_cooldown` | see note below | int, 0–100 | `100` |
| `item_use_duration` | ticks the current item has been in use | int, ≥ 0 | `0` |
| `selected_slot` | `player.getInventory().selectedSlot` | int, 0–8 | `0` |

> **`attack_cooldown` note:** Minecraft's internal cooldown is a float 0.0–1.0
> (`player.getAttackCooldownProgress()`). Multiply by 100 and round to int before
> sending, so `1.0 → 100`, `0.5 → 50`, `0.0 → 0`.

#### Boolean fields (10)

Send as JSON `true` / `false`. The server converts them to `1.0` / `0.0`.

| JSON Key | Java Source |
|---|---|
| `is_on_ground` | `player.isOnGround()` |
| `is_jumping` | `player.jumping` (field) or `player.getVelocity().y > 0 && !player.isOnGround()` |
| `is_sprinting` | `player.isSprinting()` |
| `is_sneaking` | `player.isSneaking()` |
| `has_speed` | `player.hasStatusEffect(StatusEffects.SPEED)` |
| `has_strength` | `player.hasStatusEffect(StatusEffects.STRENGTH)` |
| `has_regeneration` | `player.hasStatusEffect(StatusEffects.REGENERATION)` |
| `has_poison` | `player.hasStatusEffect(StatusEffects.POISON)` |
| `is_using_item` | `player.isUsingItem()` |
| `target_is_blocking` | `opponent.isBlocking()` — use `false` if no opponent |

---

### `hotbar` — Item Vocabulary IDs (array of 10 ints)

```json
"hotbar": [ inv_0_id, inv_1_id, inv_2_id, inv_3_id, inv_4_id,
            inv_5_id, inv_6_id, inv_7_id, inv_8_id, main_hand_id ]
```

- Always **exactly 10 integers**.
- Positions 0–8 = hotbar slots left-to-right (`inv_0` … `inv_8`).
- Position 9 = the item currently in the **main hand** (same as the active slot's item,
  but kept separately so the model can cross-reference).
- **Empty slot → `0`** (AIR always maps to ID 0).

#### How to get an item's ID

1. Call `GET /vocab` once at startup. Returns `{ "NETHERITE_SWORD": 1, "ENCHANTED_GOLDEN_APPLE": 2, … }`.
2. Cache this map in Java.
3. For each slot: get the item's registry name (e.g. `"minecraft:netherite_sword"`),
   strip the namespace (`"netherite_sword"`), uppercase it (`"NETHERITE_SWORD"`),
   look it up in the cached map. If not found → `0`.
4. Strip the `:count` suffix if your item string includes it
   (e.g. `"ENCHANTED_GOLDEN_APPLE:64"` → look up `"ENCHANTED_GOLDEN_APPLE"`).

```java
// Example helper
private int itemToVocabId(ItemStack stack) {
    if (stack == null || stack.isEmpty()) return 0;
    String name = stack.getItem().toString()          // "minecraft:netherite_sword"
                       .toUpperCase()
                       .replace("MINECRAFT:", "");    // "NETHERITE_SWORD"
    return itemVocab.getOrDefault(name, 0);           // 0 = unknown/AIR
}
```

---

### What the Server Does Internally

The Java client sends raw values. Before the model sees them, the server applies
these transformations (so the Java side does **not** need to do any of this):

| Internal Feature | Derived From | Formula |
|---|---|---|
| `health_ratio` | `health`, `max_health` | `health / max_health` |
| `sin_yaw` | `yaw` | `sin(yaw × π / 180)` |
| `cos_yaw` | `yaw` | `cos(yaw × π / 180)` |
| `food_ratio` | `food_level` | `food_level / 20` |
| `armor_ratio` | `total_armor` | `total_armor / 20` |
| `target_distance` | `target_distance` | −1 replaced by `32.0` (sentinel = "out of range") |
| `target_health_ratio` | `target_health` | `target_health / 20` |
| `attack_cooldown_norm` | `attack_cooldown` | `attack_cooldown / 100` |
| `item_use_norm` | `item_use_duration` | `item_use_duration / 72` |
| `slot_0` … `slot_8` | `selected_slot` | one-hot: `slot_i = 1` if `selected_slot == i` else `0` |
| z-score normalisation | all 16 continuous | `(value − mean) / std` using training statistics |

> `yaw` itself is **not** passed to the model directly. It is only used to
> compute `sin_yaw` and `cos_yaw`. This avoids a discontinuity at the −180/+180
> boundary that would confuse the GRU.

---

### Complete Wire-Format Example

A single frame in the `values` + `hotbar` format (tick where the player
is sprinting toward an opponent at distance ~6, holding a sword in slot 0):

```json
{
  "values": {
    "health":           18.0,
    "max_health":       20.0,
    "vel_x":           -0.18,
    "vel_y":            0.0,
    "vel_z":            0.31,
    "yaw":            -38.5,
    "pitch":           12.0,
    "food_level":      20,
    "total_armor":     20.0,
    "target_distance":  6.2,
    "target_rel_x":    -3.1,
    "target_rel_y":     0.0,
    "target_rel_z":     5.4,
    "target_health":   14.0,
    "attack_cooldown":  97,
    "item_use_duration": 0,
    "selected_slot":    0,
    "is_on_ground":     true,
    "is_jumping":       false,
    "is_sprinting":     true,
    "is_sneaking":      false,
    "has_speed":        true,
    "has_strength":     false,
    "has_regeneration": false,
    "has_poison":       false,
    "is_using_item":    false,
    "target_is_blocking": false
  },
  "hotbar": [1, 2, 0, 0, 0, 0, 0, 0, 0, 1]
}
```

Where vocab IDs are (example): `NETHERITE_SWORD = 1`, `ENCHANTED_GOLDEN_APPLE = 2`, `AIR = 0`.

The full request body wraps 10 such frames:

```json
{
  "frames": [
    { "values": { … tick t-9 … }, "hotbar": [1, 2, 0, 0, 0, 0, 0, 0, 0, 1] },
    { "values": { … tick t-8 … }, "hotbar": [1, 2, 0, 0, 0, 0, 0, 0, 0, 1] },
    …
    { "values": { … tick t   … }, "hotbar": [1, 2, 0, 0, 0, 0, 0, 0, 0, 1] }
  ]
}
```

---

### Edge Cases & Defensive Coding

| Situation | What to send |
|---|---|
| No opponent in range / no target | `target_distance: -1`, `target_health: 0`, `target_rel_x/y/z: 0`, `target_is_blocking: false` |
| Player just spawned (buffer not full yet) | Fill missing frames by repeating the oldest available frame |
| Item slot is empty | `0` (AIR) in the `hotbar` array |
| Unknown item (not in vocab) | `0` — the model treats it as an empty/irrelevant slot |
| `max_health` is 0 (edge case) | Clamp to `20.0` before sending to avoid division by zero on the server |
| Bot is dead / spectating | Do not call `/predict` — there are no actions to take |
| Server not yet loaded (startup) | Check `GET /health` returns `{ "model_loaded": true }` before sending frames |

---

### Input Dimension Summary

| Group | Count | How it reaches the model |
|---|---|---|
| Continuous features | 16 | z-score normalised floats |
| Binary features | 10 flags + 9 slot one-hot = **19** | passed as 0.0 / 1.0 |
| Hotbar item embeddings | 10 IDs × embed_dim 16 = **160** | looked up in embedding table |
| **Total per tick** | **195** | concatenated → GRU input |
| Ticks per request | 10 | GRU processes full sequence |
| **Total model input** | **1,950 values** | across the 10-tick window |

---

## Changelog

| Version | Date | Notes |
|---|---|---|
| 2.0.0 | 2026-02-25 | Initial simplified GRU bot (movement + combat + item use) |
| 2.1.0 | 2026-02-25 | Added full input contract section, wire-format example, edge cases |

