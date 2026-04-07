package dev.nandi0813.practice.manager.fight.match.bot.neural;

public class BotPrediction {
    private float deltaYaw;
    private float deltaPitch;
    private float inputForward;
    private float inputBackward;
    private float inputLeft;
    private float inputRight;
    private float inputJump;
    private float inputSneak;
    private float inputSprint;
    private float inputLmb;
    private float inputRmb;
    private int inputSlot;

    public float getDeltaYaw() {
        return deltaYaw;
    }

    public float getDeltaPitch() {
        return deltaPitch;
    }

    public float getInputForward() {
        return inputForward;
    }

    public float getInputBackward() {
        return inputBackward;
    }

    public float getInputLeft() {
        return inputLeft;
    }

    public float getInputRight() {
        return inputRight;
    }

    public float getInputJump() {
        return inputJump;
    }

    public float getInputSneak() {
        return inputSneak;
    }

    public float getInputSprint() {
        return inputSprint;
    }

    public float getInputLmb() {
        return inputLmb;
    }

    public float getInputRmb() {
        return inputRmb;
    }

    public int getInputSlot() {
        return inputSlot;
    }
}

