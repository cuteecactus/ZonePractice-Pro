package dev.nandi0813.practice.manager.fight.event.events.duel.brackets;

import dev.nandi0813.practice.ZonePractice;
import dev.nandi0813.practice.manager.arena.ArenaType;
import dev.nandi0813.practice.manager.arena.arenas.Arena;
import dev.nandi0813.practice.manager.arena.arenas.ArenaCopy;
import dev.nandi0813.practice.manager.arena.util.ArenaWorldUtil;
import dev.nandi0813.practice.manager.fight.event.events.duel.interfaces.DuelEvent;
import dev.nandi0813.practice.manager.fight.event.events.duel.interfaces.DuelFight;
import dev.nandi0813.practice.module.util.ClassImport;
import dev.nandi0813.practice.util.Common;
import dev.nandi0813.practice.util.Cuboid;
import dev.nandi0813.practice.util.playerutil.PlayerUtil;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Brackets extends DuelEvent {

    @Getter
    private final List<ArenaCopy> generatedArenas = new ArrayList<>();
    private final Map<DuelFight, ArenaCopy> fightArenaMap = new HashMap<>();
    private boolean arenasGenerated = false;
    private int arenaGenerationTaskId = -1;

    public Brackets(Object starter, BracketsData eventData) {
        super(starter, eventData, "COMMAND.EVENT.ARGUMENTS.BRACKETS");
    }

    @Override
    public BracketsData getEventData() {
        return (BracketsData) eventData;
    }

    @Override
    protected void customStart() {
        // If build mode is enabled, generate arenas before starting
        if (getEventData().isBuildMode()) {
            generateArenas();
        } else {
            // Standard brackets - start immediately
            super.customStart();
        }
    }

    private void generateArenas() {
        int playerCount = players.size();
        int arenaCount = calculateRequiredArenas(playerCount);

        sendMessage("<yellow>⚒ Generating " + arenaCount + " tournament arena copies, please wait...", false);

        // Generate arenas asynchronously to prevent lag
        arenaGenerationTaskId = Bukkit.getScheduler().runTaskAsynchronously(ZonePractice.getInstance(), () -> {
            try {
                generateArenaCopies(arenaCount);

                // Once complete, start the tournament on main thread
                Bukkit.getScheduler().runTask(ZonePractice.getInstance(), () -> {
                    arenasGenerated = true;
                    sendMessage("<green>✓ Arena generation complete! Starting tournament...", false);

                    // Wait 2 seconds before starting
                    Bukkit.getScheduler().runTaskLater(ZonePractice.getInstance(), () -> {
                        Brackets.super.customStart();
                    }, 40L); // 2 seconds
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(ZonePractice.getInstance(), () -> {
                    sendMessage("<red>✗ Failed to generate arenas: " + e.getMessage(), false);
                    Common.sendConsoleMMMessage("<red>Error generating brackets arenas: " + e.getMessage());
                    e.printStackTrace();

                    // Force end the event
                    this.forceEnd(null);
                });
            }
        }).getTaskId();
    }

    private void generateArenaCopies(int count) {
        Cuboid baseCuboid = eventData.getCuboid();
        Location baseReference = baseCuboid.getLowerNE();

        for (int i = 0; i < count; i++) {
            // Use the existing ArenaCopyUtil system to find available locations
            Location newLocation = getNextAvailableLocation();

            if (newLocation == null) {
                throw new RuntimeException("No available location found for arena copy #" + i);
            }

            // Create arena copy using the project's infrastructure
            ArenaCopy arenaCopy = createEventArenaCopy(i, baseCuboid, baseReference, newLocation);
            generatedArenas.add(arenaCopy);
        }
    }

    private Location getNextAvailableLocation() {
        final World copyWorld = ArenaWorldUtil.getArenasCopyWorld();

        // Use the same logic as ArenaCopyUtil.getAvailableLocation()
        // Four thousand arenas fit in that line
        for (int x = -1000000; x <= 1000000; x = x + 1000) {
            Location location = copyWorld.getBlockAt(x, 60, 0).getLocation();
            if (!isCuboidContainsLocation(location)) {
                return location;
            }
        }
        return null;
    }

    private boolean isCuboidContainsLocation(Location location) {
        // Check against all existing arenas AND our generated arenas
        for (Cuboid cuboid : dev.nandi0813.practice.manager.arena.ArenaManager.getInstance().getArenaCuboids().keySet()) {
            if (cuboid.contains(location)) return true;
        }

        // Also check against arenas we just generated
        for (ArenaCopy generated : generatedArenas) {
            if (generated.getCuboid() != null && generated.getCuboid().contains(location)) {
                return true;
            }
        }

        return false;
    }

    private ArenaCopy createEventArenaCopy(int index, Cuboid baseCuboid, Location baseReference, Location newLocation) {
        World copyWorld = ArenaWorldUtil.getArenasCopyWorld();

        // Create a temporary Arena object to use with ArenaCopyUtil
        // This mimics the structure needed by the copy system
        Arena tempArena = new Arena("brackets_temp_" + index, ArenaType.BASIC) {
            @Override
            public String getDisplayName() {
                return "Brackets Temp Arena";
            }
        };

        // Set up the temporary arena with event data
        tempArena.setCorner1(eventData.getCuboidLoc1());
        tempArena.setCorner2(eventData.getCuboidLoc2());
        tempArena.createCuboid();
        tempArena.setPosition1(getEventData().getLocation1());
        tempArena.setPosition2(getEventData().getLocation2());

        // Create arena copy for the event
        ArenaCopy arenaCopy = new ArenaCopy("brackets_event_" + index, tempArena) {
            @Override
            public void delete() {
                // Custom delete for event arenas - just remove blocks
                try {
                    ClassImport.getClasses().getArenaCopyUtil().deleteArena("brackets_event", cuboid);
                } catch (Exception e) {
                    Common.sendConsoleMMMessage("<red>Error deleting event arena: " + e.getMessage());
                }
            }

            @Override
            public String getDisplayName() {
                return "Brackets Arena #" + (index + 1);
            }
        };

        // Set reference in copy world (same as ArenaCopyUtil does)
        Location reference = baseReference.clone();
        reference.setWorld(copyWorld);

        // Calculate positions using the same approach as ArenaCopyUtil.createCopy()
        Location corner1 = tempArena.getCorner1().clone();
        Location corner2 = tempArena.getCorner2().clone();
        Location position1 = tempArena.getPosition1().clone();
        Location position2 = tempArena.getPosition2().clone();

        corner1.setWorld(copyWorld);
        corner2.setWorld(copyWorld);
        position1.setWorld(copyWorld);
        position2.setWorld(copyWorld);

        // Use the copyArena method from ArenaCopyUtil (through reflection or direct call)
        // Since copyArena is protected, we'll use the approach from createCopy
        arenaCopy.setCorner1(corner1.clone().subtract(reference).add(newLocation));
        arenaCopy.setCorner2(corner2.clone().subtract(reference).add(newLocation));
        arenaCopy.createCuboid();

        arenaCopy.setPosition1(position1.clone().subtract(reference).add(newLocation));
        arenaCopy.setPosition2(position2.clone().subtract(reference).add(newLocation));

        // Directly call the internal copy methods
        // This uses the existing infrastructure properly
        try {
            // Access the copyArena method through the util
            java.lang.reflect.Method copyMethod = ClassImport.getClasses().getArenaCopyUtil().getClass()
                .getDeclaredMethod("copyArena",
                    dev.nandi0813.practice.manager.profile.Profile.class,
                    ArenaCopy.class,
                    Cuboid.class,
                    Location.class,
                    Location.class);
            copyMethod.setAccessible(true);
            copyMethod.invoke(ClassImport.getClasses().getArenaCopyUtil(), null, arenaCopy, baseCuboid, reference, newLocation);
        } catch (Exception e) {
            // If reflection fails, fall back to manual block copy
            Common.sendConsoleMMMessage("<yellow>Using fallback block copy method for event arena");
            copyBlocksManually(baseCuboid, reference, newLocation);
        }

        return arenaCopy;
    }

    private void copyBlocksManually(Cuboid source, Location reference, Location newLocation) {
        World destWorld = newLocation.getWorld();
        World sourceWorld = source.getWorld();

        for (int x = source.getLowerX(); x <= source.getUpperX(); x++) {
            for (int y = source.getLowerY(); y <= source.getUpperY(); y++) {
                for (int z = source.getLowerZ(); z <= source.getUpperZ(); z++) {
                    Location srcLoc = new Location(sourceWorld, x, y, z);

                    // Calculate relative offset from reference
                    double relX = srcLoc.getX() - reference.getX();
                    double relY = srcLoc.getY() - reference.getY();
                    double relZ = srcLoc.getZ() - reference.getZ();

                    // Apply to destination
                    Location destLoc = new Location(destWorld,
                        newLocation.getX() + relX,
                        newLocation.getY() + relY,
                        newLocation.getZ() + relZ);

                    destWorld.getBlockAt(destLoc).setType(sourceWorld.getBlockAt(srcLoc).getType(), false);
                }
            }
        }
    }


    private int calculateRequiredArenas(int playerCount) {
        // Calculate total matches in a single-elimination tournament
        // For N players: matches = N - 1 (every match eliminates one player)
        // But we need to account for rounds to avoid conflicts

        int matches = 0;
        int remainingPlayers = playerCount;

        while (remainingPlayers > 1) {
            // Matches in this round
            int roundMatches = remainingPlayers / 2;
            matches += roundMatches;

            // Players advancing to next round
            remainingPlayers = roundMatches + (remainingPlayers % 2);
        }

        return matches;
    }

    @Override
    protected void startNextRound() {
        if (getEventData().isBuildMode() && !arenasGenerated) {
            // Wait for arenas to be generated
            sendMessage("<yellow>⏳ Waiting for arena generation to complete...", false);
            return;
        }

        // In build mode, the rollback on main cuboid won't affect our separate arena copies
        // Each arena copy is independent and already fresh
        super.startNextRound();
    }

    @Override
    public void teleport(Player player, Location location) {
        // If build mode, use the assigned arena
        if (getEventData().isBuildMode() && arenasGenerated) {
            DuelFight fight = getFight(player);
            if (fight != null) {
                ArenaCopy arena = getArenaForFight(fight);
                if (arena != null) {
                    // Determine which spawn position to use
                    Location spawnLoc;
                    if (fight.getPlayers().get(0).equals(player)) {
                        spawnLoc = arena.getPosition1();
                    } else {
                        spawnLoc = arena.getPosition2();
                    }

                    player.teleport(spawnLoc);
                    PlayerUtil.setFightPlayer(player);
                    this.getEventData().getKitData().loadKitData(player, true);
                    return;
                }
            }
        }

        // Fallback to standard teleport
        player.teleport(location);
        PlayerUtil.setFightPlayer(player);
        this.getEventData().getKitData().loadKitData(player, true);
    }

    private ArenaCopy getArenaForFight(DuelFight fight) {
        // Assign arena to fight if not already assigned
        if (!fightArenaMap.containsKey(fight)) {
            assignArenaToFight(fight);
        }
        return fightArenaMap.get(fight);
    }

    private void assignArenaToFight(DuelFight fight) {
        // Find next available arena based on fight index
        int fightIndex = getFights().indexOf(fight);
        int roundOffset = calculateRoundOffset(getRound() - 1);
        int arenaIndex = roundOffset + fightIndex;

        if (arenaIndex < generatedArenas.size()) {
            fightArenaMap.put(fight, generatedArenas.get(arenaIndex));
        }
    }

    private int calculateRoundOffset(int roundNumber) {
        // Calculate how many arenas were used in previous rounds
        int offset = 0;
        int playersInRound = getPlayers().size();

        for (int i = 0; i < roundNumber; i++) {
            offset += playersInRound / 2;
            playersInRound = (playersInRound / 2) + (playersInRound % 2);
        }

        return offset;
    }

    @Override
    public void addSpectator(Player spectator, Player target, boolean teleport, boolean message) {
        // Override spectator logic to teleport to specific arena in build mode
        if (getEventData().isBuildMode() && arenasGenerated) {
            Player actualTarget = target != null ? target : getRandomFightPlayer();

            if (actualTarget != null) {
                DuelFight fight = getFight(actualTarget);
                if (fight != null) {
                    ArenaCopy arena = getArenaForFight(fight);
                    if (arena != null) {
                        // Teleport to arena center for spectating
                        spectator.teleport(arena.getCuboid().getCenter());
                        super.addSpectator(spectator, actualTarget, false, message);
                        return;
                    }
                }
            }

            // Fallback: if we can't find an arena, use first generated arena
            if (!generatedArenas.isEmpty() && generatedArenas.get(0).getCuboid() != null) {
                spectator.teleport(generatedArenas.get(0).getCuboid().getCenter());
                super.addSpectator(spectator, target, false, message);
                return;
            }
        }

        super.addSpectator(spectator, target, teleport, message);
    }

    @Override
    public void endEvent() {
        // Cleanup generated arenas
        if (getEventData().isBuildMode() && !generatedArenas.isEmpty()) {
            sendMessage("<yellow>🧹 Cleaning up generated arenas...", false);

            Bukkit.getScheduler().runTaskAsynchronously(ZonePractice.getInstance(), () -> {
                for (ArenaCopy arena : generatedArenas) {
                    try {
                        arena.delete();
                    } catch (Exception e) {
                        Common.sendConsoleMMMessage("<red>Error deleting arena copy: " + e.getMessage());
                    }
                }
                generatedArenas.clear();
                fightArenaMap.clear();

                Bukkit.getScheduler().runTask(ZonePractice.getInstance(), () -> {
                    Common.sendConsoleMMMessage("<green>Brackets arenas cleaned up successfully.");
                });
            });
        }

        // Cancel any pending generation task
        if (arenaGenerationTaskId != -1) {
            Bukkit.getScheduler().cancelTask(arenaGenerationTaskId);
        }

        super.endEvent();
    }

    @Override
    public void forceEnd(Player player) {
        // Ensure cleanup happens even on force end
        if (getEventData().isBuildMode() && !generatedArenas.isEmpty()) {
            for (ArenaCopy arena : generatedArenas) {
                try {
                    arena.delete();
                } catch (Exception e) {
                    Common.sendConsoleMMMessage("<red>Error deleting arena copy during force end: " + e.getMessage());
                }
            }
            generatedArenas.clear();
            fightArenaMap.clear();
        }

        if (arenaGenerationTaskId != -1) {
            Bukkit.getScheduler().cancelTask(arenaGenerationTaskId);
        }

        super.forceEnd(player);
    }

}

