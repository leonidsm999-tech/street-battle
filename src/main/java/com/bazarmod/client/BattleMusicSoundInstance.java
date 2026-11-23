package com.bazarmod.client;

import com.bazarmod.BazarMod;
import net.minecraft.client.sound.AbstractSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;

public class BattleMusicSoundInstance extends AbstractSoundInstance {
    public BattleMusicSoundInstance() {
        super(BazarMod.BATTLE_THEME_ID, SoundCategory.MUSIC, SoundInstance.createRandom());
        this.repeat = true;
        this.repeatDelay = 0;
        this.relative = true;
        this.attenuationType = AttenuationType.NONE;
        this.volume = 0.75f;
        this.pitch = 1.0f;
    }
}
