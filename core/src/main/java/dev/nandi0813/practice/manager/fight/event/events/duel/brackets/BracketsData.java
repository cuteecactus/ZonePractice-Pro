package dev.nandi0813.practice.manager.fight.event.events.duel.brackets;

import dev.nandi0813.practice.manager.fight.event.enums.EventType;
import dev.nandi0813.practice.manager.fight.event.events.duel.interfaces.DuelEventData;
import dev.nandi0813.practice.module.interfaces.KitData;
import dev.nandi0813.practice.module.util.ClassImport;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;

@Getter
public class BracketsData extends DuelEventData {

    protected final KitData kitData;

    // Build Mode settings
    @Setter
    private boolean buildMode = false;
    private static final int BUILD_MODE_VOLUME_LIMIT = 10000; // Max blocks for build mode arenas

    public BracketsData() {
        super(EventType.BRACKETS);
        this.kitData = ClassImport.createKitData();
    }

    @Override
    protected void setCustomData() {
        kitData.saveData(config, "kit");
        config.set("build-mode", buildMode);
    }

    @Override
    protected void getCustomData() {
        kitData.getData(config, "kit");
        buildMode = config.getBoolean("build-mode", false);
    }

    @Override
    protected void enable() throws IOException {
        if (!kitData.isSet()) {
            throw new IOException("Kit data is not set.");
        } else if (spawns.size() != 2) {
            throw new IOException("Spawn positions are not set. Or not equal to 2. Current size: " + spawns.size());
        }

        // Check volume limit if build mode is enabled
        if (buildMode && cuboid != null) {
            int volume = cuboid.getVolume();
            if (volume > BUILD_MODE_VOLUME_LIMIT) {
                throw new IOException("Build Mode arena is too large! Volume: " + volume + " blocks. Maximum allowed: " + BUILD_MODE_VOLUME_LIMIT + " blocks.");
            }
        }
    }

    public static int getBuildModeVolumeLimit() {
        return BUILD_MODE_VOLUME_LIMIT;
    }

}
