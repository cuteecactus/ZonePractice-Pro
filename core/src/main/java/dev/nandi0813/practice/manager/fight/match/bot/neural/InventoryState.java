package dev.nandi0813.practice.manager.fight.match.bot.neural;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class InventoryState {
    @SerializedName("main_hand")
    private String mainHand;

    @SerializedName("off_hand")
    private String offHand;

    @SerializedName("selected_slot")
    private int selectedSlot;

    private List<String> hotbar;

    // Helmet, chestplate, leggings, boots in this order.
    private List<String> armor;

    @SerializedName("total_armor")
    private float totalArmor;

    public InventoryState() {
        this.hotbar = new ArrayList<>();
        this.armor = new ArrayList<>();
    }

    public InventoryState(String mainHand, String offHand, List<String> hotbar) {
        this(mainHand, offHand, 0, hotbar, null, 0f);
    }

    public InventoryState(String mainHand, String offHand, int selectedSlot, List<String> hotbar, List<String> armor, float totalArmor) {
        this.mainHand = mainHand;
        this.offHand = offHand;
        this.selectedSlot = Math.max(0, Math.min(8, selectedSlot));
        this.hotbar = normalizeHotbar(hotbar);
        this.armor = normalizeArmor(armor);
        this.totalArmor = Math.max(0f, totalArmor);
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

    public int getSelectedSlot() {
        return selectedSlot;
    }

    public List<String> getArmor() {
        return armor;
    }

    public float getTotalArmor() {
        return totalArmor;
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

    private static List<String> normalizeArmor(List<String> source) {
        List<String> normalized = new ArrayList<>(4);
        if (source != null) {
            for (String item : source) {
                if (normalized.size() == 4) {
                    break;
                }
                normalized.add(item == null ? "AIR" : item);
            }
        }
        while (normalized.size() < 4) {
            normalized.add("AIR");
        }
        return normalized;
    }
}

