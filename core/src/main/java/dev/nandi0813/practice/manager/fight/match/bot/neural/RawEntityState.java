package dev.nandi0813.practice.manager.fight.match.bot.neural;

import com.google.gson.annotations.SerializedName;

public class RawEntityState {
    private float x;
    private float y;
    private float z;
    private float yaw;
    private float pitch;

    @SerializedName("vel_x")
    private float velX;

    @SerializedName("vel_y")
    private float velY;

    @SerializedName("vel_z")
    private float velZ;

    private float health;
    private float food;

    @SerializedName("is_on_ground")
    private boolean onGround;

    public RawEntityState() {
    }

    public RawEntityState(float x, float y, float z, float yaw, float pitch, float velX, float velY, float velZ,
                          float health, float food, boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.velX = velX;
        this.velY = velY;
        this.velZ = velZ;
        this.health = health;
        this.food = food;
        this.onGround = onGround;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public float getVelX() {
        return velX;
    }

    public float getVelY() {
        return velY;
    }

    public float getVelZ() {
        return velZ;
    }

    public float getHealth() {
        return health;
    }

    public float getFood() {
        return food;
    }

    public boolean isOnGround() {
        return onGround;
    }
}

