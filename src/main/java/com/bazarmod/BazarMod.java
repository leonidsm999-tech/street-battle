package com.bazarmod;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BazarMod implements ModInitializer {
    public static final String MOD_ID = "bazarmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Identifier BATTLE_THEME_ID = Identifier.of(MOD_ID, "battle_theme");
    public static final SoundEvent BATTLE_THEME_EVENT = SoundEvent.of(BATTLE_THEME_ID);

    private static final Map<UUID, ShowdownSession> ACTIVE_SHOWDOWNS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> IMMUNE_UNTIL_TICK = new ConcurrentHashMap<>();

    public static class ShowdownSession {
        public final UUID id = UUID.randomUUID();
        public final UUID primaryPlayerId;
        public UUID helperPlayerId = null;
        public final UUID targetMobId;
        public final int targetEntityId;
        public final String mobName;
        public final List<String> mobDissLines;
        public final List<String> promptWords;
        public final long createdTick;
        public boolean evaluating = false;

        public ShowdownSession(UUID primaryPlayerId, LivingEntity target, BazarDictionary.PromptBundle bundle, long createdTick) {
            this.primaryPlayerId = primaryPlayerId;
            this.targetMobId = target.getUuid();
            this.targetEntityId = target.getId();
            this.mobName = target.getName().getString();
            this.mobDissLines = bundle.mobDissLines();
            this.promptWords = bundle.words();
            this.createdTick = createdTick;
        }
    }

    @Override
    public void onInitialize() {
        Registry.register(Registries.SOUND_EVENT, BATTLE_THEME_ID, BATTLE_THEME_EVENT);
        BazarConfig.get();
        BazarDictionary.ensureLoaded();
        BazarNetworking.registerPayloads();

        // Intercept mob attacks to launch a street showdown
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient || hand != Hand.MAIN_HAND) {
                return ActionResult.PASS;
            }
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            if (!(entity instanceof LivingEntity target) || target instanceof PlayerEntity) {
                return ActionResult.PASS;
            }

            long now = world.getTime();
            Long immuneUntil = IMMUNE_UNTIL_TICK.get(target.getUuid());
            if (immuneUntil != null && now < immuneUntil) {
                return ActionResult.PASS;
            }

            if (isPlayerInShowdown(serverPlayer.getUuid()) || isMobInShowdown(target.getUuid())) {
                return ActionResult.FAIL;
            }

            startShowdown(serverPlayer, target);
            return ActionResult.FAIL;
        });

        // Receive audio from client
        ServerPlayNetworking.registerGlobalReceiver(BazarNetworking.SubmitAudioPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            MinecraftServer server = player.getServer();
            if (server == null) return;

            server.execute(() -> {
                ShowdownSession session = ACTIVE_SHOWDOWNS.get(payload.showdownId());
                if (session == null || session.evaluating) return;
                if (!session.primaryPlayerId.equals(player.getUuid())) return;

                session.evaluating = true;
                ServerPlayNetworking.send(player, new BazarNetworking.StopMusicPayload(true));
                player.sendMessage(Text.literal("⏳ Смотрящий слушает твой базар...").formatted(Formatting.GOLD), true);

                if (session.helperPlayerId != null) {
                    ServerPlayerEntity helper = server.getPlayerManager().getPlayer(session.helperPlayerId);
                    if (helper != null) {
                        ServerPlayNetworking.send(helper, new BazarNetworking.StopMusicPayload(true));
                        helper.sendMessage(Text.literal("⏳ Смотрящий выносит вердикт...").formatted(Formatting.GOLD), true);
                    }
                }

                String fullDiss = String.join(" ", session.mobDissLines);
                GeminiJudgeClient.evaluateAsync(
                        session.mobName,
                        fullDiss,
                        session.promptWords,
                        payload.wavData()
                ).thenAccept(verdict -> server.execute(() -> finishShowdown(server, session, verdict)));
            });
        });

        // Friend joins showdown
        ServerPlayNetworking.registerGlobalReceiver(BazarNetworking.JoinShowdownPayload.ID, (payload, context) -> {
            ServerPlayerEntity helper = context.player();
            MinecraftServer server = helper.getServer();
            if (server == null) return;

            server.execute(() -> {
                ShowdownSession session = ACTIVE_SHOWDOWNS.get(payload.showdownId());
                if (session == null || session.evaluating || session.helperPlayerId != null) return;
                if (session.primaryPlayerId.equals(helper.getUuid())) return;

                session.helperPlayerId = helper.getUuid();
                int halfWords = Math.max(1, session.promptWords.size() / 2);
                List<String> helperWords = session.promptWords.subList(halfWords, session.promptWords.size());

                ServerPlayNetworking.send(helper, new BazarNetworking.StartShowdownPayload(
                        session.id,
                        session.targetEntityId,
                        session.mobName,
                        session.mobDissLines,
                        new ArrayList<>(helperWords),
                        Math.max(5, BazarConfig.get().battleDurationSec / 2),
                        true
                ));

                ServerPlayerEntity primary = server.getPlayerManager().getPlayer(session.primaryPlayerId);
                if (primary != null) {
                    primary.sendMessage(Text.literal("🤝 Браток " + helper.getName().getString() + " вписался за тебя в базар!").formatted(Formatting.AQUA), false);
                }
            });
        });

        // Receive updated config from client GUI
        ServerPlayNetworking.registerGlobalReceiver(BazarNetworking.SyncConfigPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            MinecraftServer server = player.getServer();
            if (server == null) return;

            server.execute(() -> {
                if (!server.isSingleplayer() && !player.hasPermissionLevel(2)) {
                    player.sendMessage(Text.literal("⚠ Настройки сохранены локально (на сервере нужны права OP для смены API ключа).").formatted(Formatting.YELLOW), false);
                    return;
                }
                BazarConfig cfg = BazarConfig.get();
                cfg.apiKey = payload.apiKey();
                cfg.model = payload.model();
                cfg.proxyHost = payload.proxyHost();
                cfg.proxyPort = payload.proxyPort();
                cfg.battleDurationSec = payload.battleDurationSec();
                cfg.minWinScore = payload.minWinScore();
                cfg.mockMode = payload.mockMode();
                cfg.save();
                player.sendMessage(Text.literal("✅ Настройки Дворовой Стрелки применены!").formatted(Formatting.GREEN), false);
            });
        });

        // Keep combatants frozen during showdown & cleanup stale sessions
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.getOverworld().getTime();
            Iterator<Map.Entry<UUID, ShowdownSession>> it = ACTIVE_SHOWDOWNS.entrySet().iterator();
            while (it.hasNext()) {
                ShowdownSession session = it.next().getValue();
                if (now - session.createdTick > 20L * 55) { // 55s safety timeout (5s diss + 30s talk + 20s API)
                    it.remove();
                    continue;
                }
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.primaryPlayerId);
                if (player == null) {
                    it.remove();
                    continue;
                }
                ServerWorld world = player.getServerWorld();
                if (world.getEntity(session.targetMobId) instanceof LivingEntity mob && mob.isAlive()) {
                    mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 255, false, false));
                    mob.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 10, 255, false, false));
                }
            }
        });

        // Commands: /bazar test, /bazar config, /bazar reload
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("bazar")
                .then(CommandManager.literal("test").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    ServerWorld world = player.getServerWorld();
                    Box box = player.getBoundingBox().expand(12.0);
                    List<LivingEntity> nearby = world.getEntitiesByClass(LivingEntity.class, box,
                            e -> e != player && !(e instanceof PlayerEntity) && e.isAlive());

                    LivingEntity target;
                    if (!nearby.isEmpty()) {
                        target = nearby.get(0);
                    } else {
                        ZombieEntity gopnik = new ZombieEntity(EntityType.ZOMBIE, world);
                        Vec3d look = player.getRotationVec(1.0f);
                        gopnik.refreshPositionAndAngles(player.getX() + look.x * 3.0, player.getY(), player.getZ() + look.z * 3.0, 0, 0);
                        gopnik.setCustomName(Text.literal("Дворовый Зомби").formatted(Formatting.RED));
                        gopnik.setCustomNameVisible(true);
                        world.spawnEntity(gopnik);
                        target = gopnik;
                    }
                    startShowdown(player, target);
                    return 1;
                }))
                .then(CommandManager.literal("config").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    ServerPlayNetworking.send(player, new BazarNetworking.OpenConfigPayload(true));
                    return 1;
                }))
                .then(CommandManager.literal("reload").executes(ctx -> {
                    BazarConfig.reload();
                    ctx.getSource().sendFeedback(() -> Text.literal("✅ Конфиг bazar_mod.properties перезагружен!").formatted(Formatting.GREEN), false);
                    return 1;
                }))
        );
    }

    private void startShowdown(ServerPlayerEntity player, LivingEntity target) {
        BazarConfig cfg = BazarConfig.get();
        ServerWorld world = player.getServerWorld();

        BazarDictionary.PromptBundle bundle = BazarDictionary.generateFor(target);
        ShowdownSession session = new ShowdownSession(player.getUuid(), target, bundle, world.getTime());
        ACTIVE_SHOWDOWNS.put(session.id, session);

        int freezeTicks = (cfg.battleDurationSec + 8) * 20;
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, freezeTicks, 255, false, false));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, freezeTicks, 255, false, false));
        world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, target.getX(), target.getY() + 1.0, target.getZ(), 25, 0.7, 0.5, 0.7, 0.02);

        String fullDiss = String.join(" ", session.mobDissLines);
        player.sendMessage(Text.literal("🔥 СТРЕЛКА С ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(session.mobName).formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(": «" + fullDiss + "»").formatted(Formatting.YELLOW)), false);

        ServerPlayNetworking.send(player, new BazarNetworking.StartShowdownPayload(
                session.id,
                session.targetEntityId,
                session.mobName,
                session.mobDissLines,
                session.promptWords,
                cfg.battleDurationSec,
                false
        ));

        // Invite nearby friend within 12 blocks
        List<ServerPlayerEntity> friends = world.getPlayers(p -> p != player && p.squaredDistanceTo(player) <= 144.0);
        for (ServerPlayerEntity friend : friends) {
            if (!isPlayerInShowdown(friend.getUuid())) {
                ServerPlayNetworking.send(friend, new BazarNetworking.InviteFriendPayload(
                        session.id,
                        player.getName().getString(),
                        session.mobName
                ));
            }
        }
    }

    private void finishShowdown(MinecraftServer server, ShowdownSession session, GeminiJudgeClient.JudgeVerdict verdict) {
        ACTIVE_SHOWDOWNS.remove(session.id);

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.primaryPlayerId);
        if (player == null) return;

        ServerWorld world = player.getServerWorld();
        LivingEntity mob = (world.getEntity(session.targetMobId) instanceof LivingEntity le && le.isAlive()) ? le : null;

        if (mob != null) {
            mob.removeStatusEffect(StatusEffects.SLOWNESS);
            mob.removeStatusEffect(StatusEffects.RESISTANCE);
            IMMUNE_UNTIL_TICK.put(mob.getUuid(), world.getTime() + 100L);
        }

        BazarNetworking.ShowdownResultPayload resultPayload = new BazarNetworking.ShowdownResultPayload(
                verdict.win(),
                verdict.score(),
                session.promptWords,
                verdict.usedWords(),
                verdict.judgeComment(),
                verdict.mobReaction()
        );
        ServerPlayNetworking.send(player, resultPayload);

        ServerPlayerEntity helper = session.helperPlayerId != null ? server.getPlayerManager().getPlayer(session.helperPlayerId) : null;
        if (helper != null) {
            ServerPlayNetworking.send(helper, resultPayload);
        }

        if (verdict.win()) {
            player.sendMessage(Text.literal("✅ ПОБЕДА В БАЗАРЕ (" + verdict.score() + "/100)! ").formatted(Formatting.GREEN, Formatting.BOLD)
                    .append(Text.literal(verdict.judgeComment()).formatted(Formatting.WHITE)), false);

            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 600, 1));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 600, 0));
            world.playSound(null, player.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.0f);

            if (mob != null) {
                world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, mob.getX(), mob.getY() + 1.0, mob.getZ(), 40, 0.5, 0.8, 0.5, 0.2);
                world.spawnEntity(new ItemEntity(world, mob.getX(), mob.getY() + 0.5, mob.getZ(), new ItemStack(Items.EMERALD, 1 + new Random().nextInt(2))));
                mob.damage(world.getDamageSources().playerAttack(player), 9999.0f);
            }
        } else {
            player.sendMessage(Text.literal("❌ ТЕБЯ ПЕРЕБАЗАРИЛИ (" + verdict.score() + "/100)! ").formatted(Formatting.RED, Formatting.BOLD)
                    .append(Text.literal(verdict.judgeComment()).formatted(Formatting.GRAY)), false);

            player.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 900, 0));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 600, 0));
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1.2f, 0.8f);

            if (mob != null) {
                Vec3d knock = player.getPos().subtract(mob.getPos()).normalize().multiply(1.4);
                player.addVelocity(knock.x, 0.45, knock.z);
                player.velocityModified = true;
                player.damage(world.getDamageSources().mobAttack(mob), 5.0f);
            }
        }
    }

    private boolean isPlayerInShowdown(UUID playerId) {
        return ACTIVE_SHOWDOWNS.values().stream()
                .anyMatch(s -> s.primaryPlayerId.equals(playerId) || playerId.equals(s.helperPlayerId));
    }

    private boolean isMobInShowdown(UUID mobId) {
        return ACTIVE_SHOWDOWNS.values().stream()
                .anyMatch(s -> s.targetMobId.equals(mobId));
    }
}
