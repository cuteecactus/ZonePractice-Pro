package dev.nandi0813.practice.manager.fight.match.bot.neural;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class InventoryState {
    @SerializedName("main_hand")
    private String mainHand;

    @SerializedName("off_hand")
    private String offHand;

    private List<String> hotbar;

    public InventoryState() {
        this.hotbar = new ArrayList<>();
    }

    public InventoryState(String mainHand, String offHand, List<String> hotbar) {
        this.mainHand = mainHand;
        this.offHand = offHand;
        this.hotbar = normalizeHotbar(hotbar);
    }

    public String getMainHand() {
        return mainHand;
    }

    public String getOffHand() {
        return offHand;
    }

    public List<String> getHotbar() {
        return hotbar;
    }

    private static List<String> normalizeHotbar(List<String> source) {
        List<String> normalized = new ArrayList<>(9);
        if (source != null) {
            for (String item : source) {
                if (normalized.size() == 9) {
                    break;
                }
                normalized.add(item == null ? "AIR" : item);
            }
        }
        while (normalized.size() < 9) {
            normalized.add("AIR");
        }
        return normalized;
    }
}

