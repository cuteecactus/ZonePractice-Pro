package dev.nandi0813.practice.manager.fight.match.bot.neural;

import com.google.gson.annotations.SerializedName;

public class GameState {
    @SerializedName("bot_id")
    private String botId;

    private RawEntityState bot;
    private RawEntityState target;
    private InventoryState inventory;

    public GameState() {
    }

    public GameState(String botId, RawEntityState bot, RawEntityState target, InventoryState inventory) {
        this.botId = botId;
        this.bot = bot;
        this.target = target;
        this.inventory = inventory;
    }

    public String getBotId() {
        return botId;
    }

    public RawEntityState getBot() {
        return bot;
    }

    public RawEntityState getTarget() {
        return target;
    }

    public InventoryState getInventory() {
        return inventory;
    }
}

