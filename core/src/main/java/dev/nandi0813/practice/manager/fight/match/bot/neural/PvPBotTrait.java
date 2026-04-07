package dev.nandi0813.practice.manager.fight.match.bot.neural;

import lombok.Setter;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.util.PlayerAnimation;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@TraitName("neural_bot")
public class PvPBotTrait extends Trait {
    private final NeuralInferenceClient inferenceClient;
    @Setter
    private JavaPlugin plugin;
    @Setter
    private Player target;
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private final AtomicLong requestCounter = new AtomicLong(0L);
    private final AtomicLong lastAppliedRequest = new AtomicLong(0L);

    public PvPBotTrait() {
        super("neural_bot");
        this.inferenceClient = new NeuralInferenceClient();
    }

    public PvPBotTrait(JavaPlugin plugin, Player target) {
        super("neural_bot");
        this.inferenceClient = new NeuralInferenceClient();
        this.plugin = plugin;
        this.target = target;
    }

    @Override
    public void run() {
        if (npc == null || !npc.isSpawned() || target == null || !target.isOnline()) {
            return;
        }

        Entity entity = npc.getEntity();
        if (!(entity instanceof Player botPlayer)) {
            return;
        }

        GameState state = buildGameState(botPlayer, target);
        JavaPlugin resolvedPlugin = resolvePlugin();
        long requestId = requestCounter.incrementAndGet();

        if (!requestInFlight.compareAndSet(false, true)) {
            return;
        }

        inferenceClient.fetchPrediction(state)
                .whenComplete((ignored, throwable) -> requestInFlight.set(false))
                .thenAcceptAsync(
                        prediction -> {
                            if (prediction == null || requestId < lastAppliedRequest.get()) {
                                return;
                            }
                            lastAppliedRequest.set(requestId);
                            applyPrediction(prediction);
                        },
                        resolvedPlugin.getServer().getScheduler().getMainThreadExecutor(resolvedPlugin)
                )
                .exceptionally(ignored -> null);
    }

    private JavaPlugin resolvePlugin() {
        if (plugin == null) {
            plugin = JavaPlugin.getProvidingPlugin(PvPBotTrait.class);
        }
        return plugin;
    }

    private GameState buildGameState(Player botPlayer, Player currentTarget) {
        RawEntityState botState = extractEntityState(botPlayer);
        RawEntityState targetState = extractEntityState(currentTarget);

        ItemStack mainHandItem = botPlayer.getInventory().getItemInMainHand();
        ItemStack offHandItem = botPlayer.getInventory().getItemInOffHand();

        List<String> hotbar = new ArrayList<>(9);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = botPlayer.getInventory().getItem(slot);
            hotbar.add(stack == null ? "AIR" : stack.getType().name());
        }

        InventoryState inventory = new InventoryState(
                mainHandItem.getType().name(),
                offHandItem.getType().name(),
                hotbar
        );

        return new GameState(String.valueOf(npc.getId()), botState, targetState, inventory);
    }

    private RawEntityState extractEntityState(Player player) {
        Location loc = player.getLocation();
        Vector vel = player.getVelocity();
        return new RawEntityState(
                (float) loc.getX(),
                (float) loc.getY(),
                (float) loc.getZ(),
                loc.getYaw(),
                loc.getPitch(),
                (float) vel.getX(),
                (float) vel.getY(),
                (float) vel.getZ(),
                (float) player.getHealth(),
                (float) player.getFoodLevel(),
                player.isOnGround()
        );
    }

    private void applyPrediction(BotPrediction pred) {
        if (pred == null || npc == null || !npc.isSpawned() || target == null || !target.isOnline()) {
            return;
        }

        Entity entity = npc.getEntity();
        if (!(entity instanceof Player botPlayer)) {
            return;
        }

        float newYaw = botPlayer.getYaw() + (pred.getDeltaYaw() * 180f);
        float newPitch = clamp(botPlayer.getPitch() + (pred.getDeltaPitch() * 90f), -90f, 90f);
        botPlayer.setRotation(newYaw, newPitch);

        Vector forward = botPlayer.getLocation().getDirection().setY(0).normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));

        Vector movement = new Vector();
        if (pred.getInputForward() > 0.5f) {
            movement.add(forward);
        }
        if (pred.getInputBackward() > 0.5f) {
            movement.subtract(forward);
        }
        if (pred.getInputRight() > 0.5f) {
            movement.add(right);
        }
        if (pred.getInputLeft() > 0.5f) {
            movement.subtract(right);
        }

        if (movement.lengthSquared() > 0) {
            movement.normalize().multiply(pred.getInputSprint() > 0.5f ? 0.28 : 0.22);
        }

        movement.setY(botPlayer.getVelocity().getY());
        if (pred.getInputJump() > 0.5f && botPlayer.isOnGround()) {
            movement.setY(0.42);
        }

        botPlayer.setVelocity(movement);

        botPlayer.setSneaking(pred.getInputSneak() > 0.5f);
        botPlayer.setSprinting(pred.getInputSprint() > 0.5f);

        if (pred.getInputSlot() >= 2 && pred.getInputSlot() <= 10) {
            botPlayer.getInventory().setHeldItemSlot(pred.getInputSlot() - 2);
        }

        if (pred.getInputLmb() > 0.5f) {
            PlayerAnimation.ARM_SWING.play(botPlayer);

            Location eye = botPlayer.getEyeLocation();
            RayTraceResult rayResult = botPlayer.getWorld().rayTraceEntities(
                    eye,
                    eye.getDirection(),
                    3.0,
                    hit -> Objects.equals(hit, target)
            );

            if (rayResult != null && rayResult.getHitEntity() == target) {
                target.damage(7.0, botPlayer);
            }
        }

        if (pred.getInputRmb() > 0.5f) {
            ItemStack inMainHand = botPlayer.getInventory().getItemInMainHand();
            Material heldType = inMainHand.getType();

            if (heldType == Material.SPLASH_POTION) {
                botPlayer.launchProjectile(ThrownPotion.class);
                decrementMainHand(botPlayer);
            }

            if (heldType == Material.ENDER_PEARL) {
                botPlayer.launchProjectile(EnderPearl.class);
                decrementMainHand(botPlayer);
            }
        }
    }

    private static void decrementMainHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            return;
        }

        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            return;
        }

        item.setAmount(item.getAmount() - 1);
        player.getInventory().setItemInMainHand(item);
    }

    private static float clamp(float value, float min, float max) {
        return Math.clamp(value, min, max);
    }
}

