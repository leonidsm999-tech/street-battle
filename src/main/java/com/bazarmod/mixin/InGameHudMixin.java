package com.bazarmod.mixin;

import com.bazarmod.client.BazarModClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void bazarmod$hideCrosshairDuringBattle(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (BazarModClient.isBattleHudActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void bazarmod$hideHotbarDuringBattle(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (BazarModClient.isBattleHudActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
    private void bazarmod$hideStatusBarsDuringBattle(DrawContext context, CallbackInfo ci) {
        if (BazarModClient.isBattleHudActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void bazarmod$hideExpBarDuringBattle(DrawContext context, int x, CallbackInfo ci) {
        if (BazarModClient.isBattleHudActive()) {
            ci.cancel();
        }
    }
}
