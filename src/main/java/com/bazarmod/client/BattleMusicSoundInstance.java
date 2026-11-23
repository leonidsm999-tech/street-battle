package com.bazarmod.client;

import com.bazarmod.BazarMod;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;

public class BattleMusicSoundInstance extends MovingSoundInstance {
    private boolean stopped = false;

    public BattleMusicSoundInstance() {
        super(BazarMod.BATTLE_THEME_EVENT, SoundCategory.RECORDS, SoundInstance.createRandom());
        this.repeat = true;
        this.repeatDelay = 0;
        this.relative = true;
        this.attenuationType = AttenuationType.NONE;
        this.volume = 1.0f;
        this.pitch = 1.0f;
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    public void stopMusic() {
        this.stopped = true;
        this.setDone();
    }

    @Override
    public boolean isDone() {
        return this.stopped;
    }

    @Override
    public void tick() {
        // Keep playing until explicitly stopped when player finishes speaking
    }
}
