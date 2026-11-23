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
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class BazarModClient implements ClientModInitializer {
    private static KeyBinding joinBattleKey;
    private static KeyBinding openConfigKey;
    private static BattleMusicSoundInstance activeMusic;

    private enum BattlePhase {
        NONE,
        MOB_DISS,
        PLAYER_TURN,
        EVALUATING,
        VERDICT
    }

    private static BattlePhase phase = BattlePhase.NONE;
    private static UUID activeShowdownId = null;
    private static int activeMobEntityId = -1;
    private static String currentMobName = "";
    private static List<String> currentMobDissLines = List.of();
    private static List<String> currentWords = List.of();
    private static int playerTurnDurationSec = 15;

    private static long phaseStartMs = 0L;
    private static long phaseEndMs = 0L;
    private static boolean recordingStartedForCurrentBattle = false;

    // Friend invite state
    private static UUID pendingInviteId = null;
    private static String pendingInviteText = "";
    private static long inviteExpireMs = 0L;

    // Verdict banner state
    private static BazarNetworking.ShowdownResultPayload lastVerdict = null;

    public static boolean isBattleHudActive() {
        return phase == BattlePhase.MOB_DISS
                || phase == BattlePhase.PLAYER_TURN
                || phase == BattlePhase.EVALUATING;
    }

    @Override
    public void onInitializeClient() {
        joinBattleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bazarmod.join_battle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.bazarmod"
        ));

        openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bazarmod.open_config",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.bazarmod"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openConfigKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new BazarConfigScreen(null));
                }
            }

            while (joinBattleKey.wasPressed()) {
                if (pendingInviteId != null && System.currentTimeMillis() < inviteExpireMs) {
                    ClientPlayNetworking.send(new BazarNetworking.JoinShowdownPayload(pendingInviteId));
                    pendingInviteId = null;
                }
            }

            // Phase transition: MOB_DISS -> PLAYER_TURN
            if (phase == BattlePhase.MOB_DISS && System.currentTimeMillis() >= phaseEndMs && !recordingStartedForCurrentBattle) {
                startPlayerTurnPhase(client);
            }
        });

        // Start showdown -> Phase 1 (MOB_DISS: mob roasts player in centered window + battle music)
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.StartShowdownPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                activeShowdownId = payload.showdownId();
                activeMobEntityId = payload.mobEntityId();
                currentMobName = payload.mobName();
                currentMobDissLines = payload.mobDissLines().isEmpty()
                        ? List.of("Поясни за свой базар, фраер!")
                        : payload.mobDissLines();
                currentWords = payload.promptWords();
                playerTurnDurationSec = payload.durationSec();
                lastVerdict = null;
                recordingStartedForCurrentBattle = false;

                startBattleMusic(client);

                long dissDurationMs = Math.max(4500L, currentMobDissLines.size() * 1800L);
                phase = BattlePhase.MOB_DISS;
                phaseStartMs = System.currentTimeMillis();
                phaseEndMs = phaseStartMs + dissDurationMs;
            });
        });

        // Friend invite
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.InviteFriendPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                pendingInviteId = payload.showdownId();
                pendingInviteText = "Кент " + payload.friendName() + " забился с [" + payload.mobName() + "]! Нажми [G], чтобы вписаться!";
                inviteExpireMs = System.currentTimeMillis() + 10000L;
            });
        });

        // Verdict received -> Show centered verdict card
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.ShowdownResultPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> {
                stopBattleMusic(client);
                lastVerdict = payload;
                phase = BattlePhase.VERDICT;
                phaseStartMs = System.currentTimeMillis();
                phaseEndMs = phaseStartMs + 9000L;
            });
        });

        // Stop music
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.StopMusicPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> stopBattleMusic(client));
        });

        // Open config GUI via /bazar config command
        ClientPlayNetworking.registerGlobalReceiver(BazarNetworking.OpenConfigPayload.ID, (payload, context) -> {
            MinecraftClient client = context.client();
            client.execute(() -> client.setScreen(new BazarConfigScreen(client.currentScreen)));
        });

        // Render Centered Dialogue HUD
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> renderCenteredDialogHud(drawContext));
    }

    private static void startPlayerTurnPhase(MinecraftClient client) {
        recordingStartedForCurrentBattle = true;

        phase = BattlePhase.PLAYER_TURN;
        phaseStartMs = System.currentTimeMillis();
        phaseEndMs = phaseStartMs + (playerTurnDurationSec * 1000L);

        UUID sessionId = activeShowdownId;
        MicrophoneRecorder.recordAsync(playerTurnDurationSec, wavBytes -> {
            client.execute(() -> {
                if (phase == BattlePhase.PLAYER_TURN) {
                    phase = BattlePhase.EVALUATING;
                    phaseStartMs = System.currentTimeMillis();
                    phaseEndMs = phaseStartMs + 15000L;
                }
                stopBattleMusic(client);
                if (sessionId != null) {
                    ClientPlayNetworking.send(new BazarNetworking.SubmitAudioPayload(sessionId, wavBytes));
                }
            });
        });
    }

    private static void startBattleMusic(MinecraftClient client) {
        stopBattleMusic(client);
        activeMusic = new BattleMusicSoundInstance();
        client.getSoundManager().play(activeMusic);
    }

    private static void stopBattleMusic(MinecraftClient client) {
        if (activeMusic != null) {
            activeMusic.stopMusic();
            client.getSoundManager().stop(activeMusic);
            activeMusic = null;
        }
    }

    private static void renderCenteredDialogHud(DrawContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        TextRenderer tr = client.textRenderer;
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        int centerX = screenW / 2;
        int centerY = screenH / 2;
        long now = System.currentTimeMillis();

        if (phase != BattlePhase.NONE && now > phaseEndMs && phase != BattlePhase.MOB_DISS) {
            phase = BattlePhase.NONE;
        }

        // --- CENTERED DIALOG BOX ---
        if (phase == BattlePhase.MOB_DISS) {
            int boxW = Math.min(screenW - 24, 390);
            int boxH = 128;
            int x1 = centerX - boxW / 2;
            int y1 = centerY - boxH / 2;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xE0110E16);
            ctx.drawBorder(x1, y1, boxW, boxH, 0xFFFF4444);
            ctx.fill(x1 + 1, y1 + 1, x1 + boxW - 1, y1 + 22, 0xBB3A1018);

            ctx.drawCenteredTextWithShadow(tr,
                    Text.literal("🔥 НАЕЗД МОБА: " + currentMobName.toUpperCase(Locale.ROOT) + " БАТТЛИТ ТЕБЯ!"),
                    centerX, y1 + 7, 0xFFFF6666);

            // 3D Mob Avatar inside the centered dialog box
            int avatarAreaW = 68;
            ctx.fill(x1 + 8, y1 + 28, x1 + 8 + avatarAreaW, y1 + boxH - 10, 0x66000000);
            ctx.drawBorder(x1 + 8, y1 + 28, avatarAreaW, boxH - 38, 0x88FF5555);
            renderMobAvatar(client, ctx, x1 + 8, y1 + 28, x1 + 8 + avatarAreaW, y1 + boxH - 10);

            int textLeft = x1 + avatarAreaW + 18;
            int textMaxW = boxW - avatarAreaW - 28;
            double progress = Math.min(1.0, (double) (now - phaseStartMs) / Math.max(1L, phaseEndMs - phaseStartMs));
            int visibleLines = Math.min(currentMobDissLines.size(), 1 + (int) (progress * currentMobDissLines.size()));

            int curY = y1 + 30;
            for (int i = 0; i < visibleLines; i++) {
                boolean isNewest = (i == visibleLines - 1);
                int lineColor = isNewest ? 0xFFFFD700 : 0xFFCCCCCC;
                String prefix = isNewest ? "▶ " : "• ";
                List<OrderedText> wrapped = tr.wrapLines(Text.literal(prefix + currentMobDissLines.get(i)), textMaxW);
                for (OrderedText ot : wrapped) {
                    if (curY + 10 < y1 + boxH - 14) {
                        ctx.drawTextWithShadow(tr, ot, textLeft, curY, lineColor);
                        curY += 11;
                    }
                }
                curY += 3;
            }

            ctx.drawCenteredTextWithShadow(tr,
                    Text.literal("Готовь ответку! Скоро врубится твой микрофон..."),
                    centerX + avatarAreaW / 2, y1 + boxH - 13, 0xFF999999);
            return;
        }

        if (phase == BattlePhase.PLAYER_TURN) {
            int boxW = Math.min(screenW - 24, 410);
            int boxH = 138;
            int x1 = centerX - boxW / 2;
            int y1 = centerY - boxH / 2;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xE00E151E);
            ctx.drawBorder(x1, y1, boxW, boxH, 0xFF00E5FF);
            ctx.fill(x1 + 1, y1 + 1, x1 + boxW - 1, y1 + 22, 0xBB0C2E3E);

            double remaining = Math.max(0.0, (phaseEndMs - now) / 1000.0);
            String title = String.format(Locale.US, "🎤 ТВОЙ ВЫХОД! ПЕРЕБАЗАРЬ %s  |  ⏱ %.1fс",
                    currentMobName.toUpperCase(Locale.ROOT), remaining);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(title), centerX, y1 + 7, 0xFF55FFFF);

            String lastLine = currentMobDissLines.isEmpty() ? "" : currentMobDissLines.get(currentMobDissLines.size() - 1);
            List<OrderedText> claimWrapped = tr.wrapLines(Text.literal("Моб: «" + lastLine + "»"), boxW - 24);
            int curY = y1 + 28;
            for (int i = 0; i < Math.min(2, claimWrapped.size()); i++) {
                ctx.drawCenteredTextWithShadow(tr, claimWrapped.get(i), centerX, curY, 0xFFFFB86C);
                curY += 11;
            }

            ctx.drawCenteredTextWithShadow(tr, Text.literal("ВПЛЕТИ В БАЗАР ЭТИ СЛОВА:"), centerX, y1 + 54, 0xFFAAAAAA);

            String wordsLine = "[ " + String.join(" ]   [ ", currentWords).toUpperCase(Locale.ROOT) + " ]";
            List<OrderedText> wordsWrapped = tr.wrapLines(Text.literal(wordsLine), boxW - 20);
            int wY = y1 + 68;
            for (OrderedText ot : wordsWrapped) {
                ctx.drawCenteredTextWithShadow(tr, ot, centerX, wY, 0xFF55FF55);
                wY += 12;
            }

            // Live Microphone Level Bar
            float level = MicrophoneRecorder.getCurrentLevel();
            int barW = boxW - 40;
            int barH = 10;
            int barX = centerX - barW / 2;
            int barY = y1 + boxH - 28;
            ctx.fill(barX, barY, barX + barW, barY + barH, 0xFF22222A);
            int filledW = (int) (barW * level);
            int barColor = level > 0.65f ? 0xFFFF5555 : (level > 0.15f ? 0xFF55FF55 : 0xFF5599FF);
            ctx.fill(barX, barY, barX + filledW, barY + barH, barColor);
            ctx.drawBorder(barX, barY, barW, barH, 0xFF666677);

            String micHint = level > 0.08f
                    ? "🎙 Микрофон слышит тебя! Дави напором!"
                    : "🎙 Говори в микрофон громче и увереннее!";
            ctx.drawCenteredTextWithShadow(tr, Text.literal(micHint), centerX, y1 + boxH - 14, 0xFFDDDDDD);
            return;
        }

        if (phase == BattlePhase.EVALUATING) {
            int boxW = Math.min(screenW - 24, 340);
            int boxH = 54;
            int x1 = centerX - boxW / 2;
            int y1 = centerY - boxH / 2;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xE0141218);
            ctx.drawBorder(x1, y1, boxW, boxH, 0xFFFFAA00);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("⏳ СМОТРЯЩИЙ ОЦЕНИВАЕТ ТВОЙ БАЗАР..."), centerX, y1 + 14, 0xFFFFD700);
            ctx.drawCenteredTextWithShadow(tr, Text.literal("Нейросеть проверяет слова и уверенность в голосе"), centerX, y1 + 30, 0xFFBBBBBB);
            return;
        }

        if (phase == BattlePhase.VERDICT && lastVerdict != null) {
            int boxW = Math.min(screenW - 24, 410);
            int boxH = 128;
            int x1 = centerX - boxW / 2;
            int y1 = centerY - boxH / 2;
            int borderColor = lastVerdict.win() ? 0xFF55FF55 : 0xFFFF5555;

            ctx.fill(x1, y1, x1 + boxW, y1 + boxH, 0xE0101016);
            ctx.drawBorder(x1, y1, boxW, boxH, borderColor);

            String header = (lastVerdict.win() ? "✅ АВТОРИТЕТ ПОДТВЕРЖДЁН" : "❌ ТЕБЯ ПЕРЕБАЗАРИЛИ")
                    + "  |  СЧЁТ: " + lastVerdict.score() + "/100";
            ctx.drawCenteredTextWithShadow(tr, Text.literal(header), centerX, y1 + 8, borderColor);

            Set<String> usedLower = new HashSet<>();
            for (String w : lastVerdict.usedWords()) {
                usedLower.add(w.toLowerCase(Locale.ROOT).trim());
            }
            StringBuilder wordStatus = new StringBuilder("Слова: ");
            for (String pw : lastVerdict.promptWords()) {
                boolean hit = usedLower.stream().anyMatch(u -> u.contains(pw.toLowerCase(Locale.ROOT)) || pw.toLowerCase(Locale.ROOT).contains(u));
                wordStatus.append(hit ? "§a[✓ " + pw + "] " : "§7[✗ " + pw + "] ");
            }
            ctx.drawCenteredTextWithShadow(tr, Text.literal(wordStatus.toString().trim()), centerX, y1 + 24, 0xFFFFFFFF);

            int curY = y1 + 42;
            List<OrderedText> judgeLines = tr.wrapLines(Text.literal("Смотрящий: " + lastVerdict.judgeComment()), boxW - 24);
            for (int i = 0; i < Math.min(3, judgeLines.size()); i++) {
                ctx.drawCenteredTextWithShadow(tr, judgeLines.get(i), centerX, curY, 0xFFFFFFFF);
                curY += 11;
            }

            curY += 4;
            List<OrderedText> mobLines = tr.wrapLines(Text.literal(currentMobName + ": «" + lastVerdict.mobReaction() + "»"), boxW - 24);
            for (int i = 0; i < Math.min(2, mobLines.size()); i++) {
                ctx.drawCenteredTextWithShadow(tr, mobLines.get(i), centerX, curY, 0xFFFFD700);
                curY += 11;
            }
            return;
        }

        // Friend Invite Banner (top-center)
        if (pendingInviteId != null && now < inviteExpireMs) {
            int boxW = Math.min(screenW - 20, 380);
            int x1 = centerX - boxW / 2;
            ctx.fill(x1, 12, x1 + boxW, 34, 0xCC181828);
            ctx.drawBorder(x1, 12, boxW, 22, 0xFF55FFFF);
            ctx.drawCenteredTextWithShadow(tr, Text.literal(pendingInviteText), centerX, 19, 0xFF55FFFF);
        }
    }

    private static void renderMobAvatar(MinecraftClient client, DrawContext ctx, int x1, int y1, int x2, int y2) {
        if (client.world == null || activeMobEntityId < 0) return;
        Entity entity = client.world.getEntityById(activeMobEntityId);
        if (entity instanceof LivingEntity living) {
            try {
                float height = Math.max(0.6f, living.getHeight());
                int scale = (int) Math.max(14, Math.min(34, 48.0f / height));
                InventoryScreen.drawEntity(ctx, x1, y1, x2, y2, scale, 0.0625f, (x1 + x2) / 2.0f - 15.0f, (y1 + y2) / 2.0f, living);
            } catch (Throwable ignored) {
            }
        }
    }
}
