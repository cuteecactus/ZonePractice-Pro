package dev.nandi0813.practice.manager.fight.match.bot.neural;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.TraitInfo;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BotSpawnerUtil {
    private BotSpawnerUtil() {
    }

    public static NPC spawnNeuralBot(JavaPlugin plugin, Location spawnLocation, Player target) {
        registerTrait();

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, "PvP Bot");

        npc.getNavigator().getDefaultParameters().avoidWater(false);
        npc.getNavigator().getDefaultParameters().useNewPathfinder(false);
        npc.getDefaultGoalController().clear();

        PvPBotTrait trait = new PvPBotTrait(plugin, target);
        npc.addTrait(trait);

        SkinTrait skinTrait = npc.getOrAddTrait(SkinTrait.class);
        skinTrait.setSkinName(target != null ? target.getName() : "Dream");

        npc.spawn(spawnLocation);
        return npc;
    }

    private static void registerTrait() {
        try {
            CitizensAPI.getTraitFactory().registerTrait(
                    TraitInfo.create(PvPBotTrait.class).withName("neural_bot")
            );
        } catch (IllegalArgumentException ignored) {
            // Already registered.
        }
    }
}


