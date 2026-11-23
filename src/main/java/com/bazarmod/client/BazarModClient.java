package com.bazarmod.client;

import com.bazarmod.BazarNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class BazarModClient implements ClientModInitializer {
    private static KeyBinding joinBattleKey;
    private static BattleMusicSoundInstance activeMusic;

    // Active battle state for HUD
    private static boolean inBattle = false;
    private static String currentMobName = "";
    private static String currentClaim = "";
    private static List<String> currentWords = List.of();
    private static long battleEndTimeMs = 0L;

    // Friend invite state
    private static UUID pendingInviteId = null;
    private static String pendingInviteText = "";
    private static long inviteExpireMs = 0L;

    // Verdict banner state
    private static BazarNetworking.ShowdownResultPayload lastVerdict = null;
    private static long verdictExpireMs = 0L;

    @Override
    public void onInitializeClient() {
        joinBattleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bazarmod.join_battle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.bazarmod"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (joinBattleKey.wasPressed()) {
                if (pendingInviteId != null && System.currentTimeMillis() < inviteExpireMs) {
                    ClientPlayNetworking.send(new BazarNetworking.JoinShowdownPayload(pendingInviteId));
                    pendingInviteId = null;
                }
            }
        });

        // Start showdown
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.StartShowdownPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                inBattle = true;
                lastVerdict = null;
                currentMobName = payload.mobName();
                currentClaim = payload.mobClaim();
                currentWords = payload.promptWords();
                battleEndTimeMs = System.currentTimeMillis() + (payload.durationSec() * 1000L);

                stopBattleMusic(client);
                activeMusic = new BattleMusicSoundInstance();
                client.getSoundManager().play(activeMusic);

                MicrophoneRecorder.recordAsync(payload.durationSec(), wavBytes -> {
                    client.execute(() -> {
                        inBattle = false;
                        stopBattleMusic(client);
                        ClientPlayNetworking.send(new BazarNetworking.SubmitAudioPayload(payload.showdownId(), wavBytes));
                    });
                });
            });
        });

        // Friend invite
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.InviteFriendPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                pendingInviteId = payload.showdownId();
                pendingInviteText = "Кент " + payload.friendName() + " забился с [" + payload.mobName() + "]! Нажми [G], чтобы вписаться!";
                inviteExpireMs = System.currentTimeMillis() + 8000L;
            });
        });

        // Verdict received
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.ShowdownResultPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                inBattle = false;
                stopBattleMusic(client);
                lastVerdict = payload;
                verdictExpireMs = System.currentTimeMillis() + 8500L;
            });
        });

        // Stop music
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.StopMusicPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> stopBattleMusic(client));
        });

        // Render HUD
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> renderHud(drawContext));
    }

    private static void stopBattleMusic(MinecraftClient client) {
        if (activeMusic != null) {
            client.getSoundManager().stop(activeMusic);
            activeMusic = null;
        }
    }

    private static void renderHud(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        TextRenderer tr = client.textRenderer;
        int screenWidth = client.getWindow().getScaledWidth();
        int centerX = screenWidth / 2;
        long now = System.currentTimeMillis();

        // 1. Active Showdown Prompter HUD
        if (inBattle && now < battleEndTimeMs) {
            double remaining = Math.max(0.0, (battleEndTimeMs - now) / 1000.0);
            int boxW = Math.min(screenWidth - 20, 360);
            int boxH = 82;
            int x1 = centerX - boxW / 2;
            int y1 = 10;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xCC121218);
            ctx.drawBorder(x1, y1, boxW, boxH, 0xFFFFAA00);

            String title = String.format(Locale.US, "🔥 СТРЕЛКА: %s  |  ⏱ %.1f сек", currentMobName, remaining);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(title), centerX, y1 + 8, 0xFFFF5555);

            String claimTrimmed = currentClaim.length() > 54 ? currentClaim.substring(0, 51) + "..." : currentClaim;
            ctx.drawCenteredTextWithShadow(tr, Text.literal("«" + claimTrimmed + "»"), centerX, y1 + 23, 0xFFDDDDDD);

            ctx.drawCenteredTextWithShadow(tr, Text.literal("Вплети в базар слова:"), centerX, y1 + 40, 0xFFAAAAAA);

            String wordsLine = String.join("  •  ", currentWords).toUpperCase(Locale.ROOT);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(wordsLine), centerX, y1 + 55, 0xFF55FFFF);
            return;
        }

        // 2. Verdict HUD
        if (lastVerdict != null && now < verdictExpireMs) {
            int boxW = Math.min(screenWidth - 20, 380);
            int boxH = 76;
            int x1 = centerX - boxW / 2;
            int y1 = 10;
            int borderColor = lastVerdict.win() ? 0xFF55FF55 : 0xFFFF5555;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xCC101014);
            ctx.drawBorder(x1, y1, boxW, boxH, borderColor);

            String header = (lastVerdict.win() ? "✅ АВТОРИТЕТ ПОДТВЕРЖДЁН" : "❌ ТЕБЯ ПЕРЕБАЗАРИЛИ")
                    + " (" + lastVerdict.score() + "/100)";
            ctx.drawCenteredTextWithShadow(tr, Text.literal(header), centerX, y1 + 8, borderColor);

            String usedStr = lastVerdict.usedWords().isEmpty()
                    ? "Засчитано слов: 0"
                    : "Засчитано: " + String.join(", ", lastVerdict.usedWords());
            ctx.drawCenteredTextWithShadow(tr, Text.literal(usedStr), centerX, y1 + 23, 0xFF55FF55);

            String comment = lastVerdict.judgeComment();
            if (comment.length() > 58) comment = comment.substring(0, 55) + "...";
            ctx.drawCenteredTextWithShadow(tr, Text.literal(comment), centerX, y1 + 39, 0xFFFFFFFF);

            String reaction = "Моб: «" + lastVerdict.mobReaction() + "»";
            if (reaction.length() > 58) reaction = reaction.substring(0, 55) + "...";
            ctx.drawCenteredTextWithShadow(tr, Text.literal(reaction), centerX, y1 + 54, 0xFFFFD700);
            return;
        }

        // 3. Friend Invite Banner
        if (pendingInviteId != null && now < inviteExpireMs) {
            int boxW = Math.min(screenWidth - 20, 360);
            int x1 = centerX - boxW / 2;
            ctx.fill(x1, 10, x1 + boxW, 32, 0xCC181828);
            ctx.drawBorder(x1, 10, boxW, 22, 0xFF55FFFF);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(pendingInviteText), centerX, 17, 0xFF55FFFF);
        }
    }
}
