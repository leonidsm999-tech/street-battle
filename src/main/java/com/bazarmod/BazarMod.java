package com.bazarmod;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.VillagerEntity;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
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

    private static final List<String> SPECTATOR_NAMES = List.of(
            "§6Смотрящий",
            "§eПацан с района",
            "§eБраток",
            "§eСвидетель базара",
            "§6Авторитет",
            "§eКент"
    );

    public static class ShowdownSession {
        public final UUID id = UUID.randomUUID();
        public final UUID primaryPlayerId;
        public UUID helperPlayerId = null;
        public final UUID targetMobId;
        public final int targetEntityId;
        public final String mobTypeKey;
        public final int tier;
        public final String mobName;
        public final String mobStreetNickname;
        public final String tierBadge;
        public final List<String> mobDissLines;
        public final List<String> promptWords;
        public final int effectiveWinThreshold;
        public final long createdTick;
        public final List<UUID> spectatorIds = new ArrayList<>();
        public boolean evaluating = false;

        public ShowdownSession(UUID primaryPlayerId, LivingEntity target, BazarDictionary.PromptBundle bundle, int effectiveWinThreshold, long createdTick) {
            this.primaryPlayerId = primaryPlayerId;
            this.targetMobId = target.getUuid();
            this.targetEntityId = target.getId();
            this.mobTypeKey = bundle.mobTypeKey();
            this.tier = bundle.tier();
            this.mobName = target.getName().getString();
            this.mobStreetNickname = bundle.mobStreetNickname();
            this.tierBadge = bundle.tierBadge();
            this.mobDissLines = bundle.mobDissLines();
            this.promptWords = bundle.words();
            this.effectiveWinThreshold = effectiveWinThreshold;
            this.createdTick = createdTick;
        }
    }

    @Override
    public void onInitialize() {
        Registry.register(Registries.SOUND_EVENT, BATTLE_THEME_ID, BATTLE_THEME_EVENT);
        BazarConfig.get();
        BazarDictionary.ensureLoaded();
        BazarProgressionManager.ensureLoaded();
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

            // Do not allow hitting spectators during a battle
            if (isSpectatorEntity(target.getUuid())) {
                return ActionResult.FAIL;
            }

            long now = world.getTime();
            Long immuneUntil = IMMUNE_UNTIL_TICK.get(target.getUuid());
            if (immuneUntil != null && now < immuneUntil) {
                return ActionResult.PASS;
            }

            if (isPlayerInShowdown(serverPlayer.getUuid()) || isMobInShowdown(target.getUuid())) {
                return ActionResult.FAIL;
            }

            // Check progression domination
            int tier = BazarDictionary.resolveTier(target);
            String mobKey = Registries.ENTITY_TYPE.getId(target.getType()).getPath();
            if (BazarProgressionManager.isMobTypeDominated(serverPlayer.getUuid(), mobKey, tier)) {
                IMMUNE_UNTIL_TICK.put(target.getUuid(), now + 200L);
                serverPlayer.sendMessage(Text.literal("😱 " + target.getName().getString() + " в ужасе от твоего авторитета и откупается без базара!")
                        .formatted(Formatting.AQUA), true);
                if (world instanceof ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.SMOKE, target.getX(), target.getY() + 1.0, target.getZ(), 15, 0.4, 0.4, 0.4, 0.05);
                    if (new Random().nextFloat() < 0.35f) {
                        sw.spawnEntity(new ItemEntity(sw, target.getX(), target.getY() + 0.5, target.getZ(), new ItemStack(Items.EMERALD, 1)));
                    }
                }
                Vec3d flee = target.getPos().subtract(serverPlayer.getPos()).normalize().multiply(1.3);
                target.addVelocity(flee.x, 0.35, flee.z);
                target.velocityModified = true;
                return ActionResult.PASS;
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
                        session.mobStreetNickname,
                        fullDiss,
                        session.promptWords,
                        session.effectiveWinThreshold,
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

                BazarProgressionManager.PlayerProgress hp = BazarProgressionManager.get(helper.getUuid());
                int curWins = BazarProgressionManager.getMobWins(helper.getUuid(), session.mobTypeKey);
                int reqWins = BazarProgressionManager.getRequiredWinsForTier(session.tier);

                ServerPlayNetworking.send(helper, new BazarNetworking.StartShowdownPayload(
                        session.id,
                        session.targetEntityId,
                        session.mobName,
                        session.mobStreetNickname,
                        session.tierBadge,
                        session.mobDissLines,
                        new ArrayList<>(helperWords),
                        Math.max(5, BazarConfig.get().battleDurationSec / 2),
                        true,
                        curWins,
                        reqWins,
                        BazarProgressionManager.getRankTitle(hp.respectXp),
                        hp.respectXp
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

        // Keep combatants & spectators frozen and looking at the center of the battle!
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = server.getOverworld().getTime();
            Iterator<Map.Entry<UUID, ShowdownSession>> it = ACTIVE_SHOWDOWNS.entrySet().iterator();
            while (it.hasNext()) {
                ShowdownSession session = it.next().getValue();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.primaryPlayerId);
                if (player == null || now - session.createdTick > 20L * 65) {
                    if (player != null) {
                        despawnSpectators(player.getServerWorld(), session);
                    }
                    it.remove();
                    continue;
                }

                ServerWorld world = player.getServerWorld();
                Vec3d midPoint = player.getEyePos();

                if (world.getEntity(session.targetMobId) instanceof LivingEntity mob && mob.isAlive()) {
                    mob.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 255, false, false));
                    mob.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 10, 255, false, false));
                    mob.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, player.getEyePos());
                    midPoint = player.getEyePos().add(mob.getEyePos()).multiply(0.5);
                }

                // Keep spectators watching the showdown
                for (UUID specId : session.spectatorIds) {
                    Entity spec = world.getEntity(specId);
                    if (spec instanceof LivingEntity livingSpec && livingSpec.isAlive()) {
                        livingSpec.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 10, 255, false, false));
                        livingSpec.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 10, 255, false, false));
                        livingSpec.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, midPoint);
                    }
                }
            }
        });

        // Commands: /bazar test, /bazar config, /bazar history, /bazar rank, /bazar reload
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
    }

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("bazar")
                .then(CommandManager.literal("test").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    ServerWorld world = player.getServerWorld();
                    Box box = player.getBoundingBox().expand(12.0);
                    List<LivingEntity> nearby = world.getEntitiesByClass(LivingEntity.class, box,
                            e -> e != player && !(e instanceof PlayerEntity) && !isSpectatorEntity(e.getUuid()) && e.isAlive());

                    LivingEntity target;
                    if (!nearby.isEmpty()) {
                        target = nearby.get(0);
                    } else {
                        ZombieEntity gopnik = new ZombieEntity(EntityType.ZOMBIE, world);
                        Vec3d look = player.getRotationVec(1.0f);
                        gopnik.refreshPositionAndAngles(player.getX() + look.x * 3.2, player.getY(), player.getZ() + look.z * 3.2, 0, 0);
                        gopnik.setCustomName(Text.literal("Гнилой Пахан").formatted(Formatting.RED));
                        gopnik.setCustomNameVisible(true);
                        world.spawnEntity(gopnik);
                        target = gopnik;
                    }
                    startShowdown(player, target);
                    return 1;
                }))
                .then(CommandManager.literal("config").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    ServerPlayNetworking.send(player, new BazarNetworking.OpenScreenPayload("config"));
                    return 1;
                }))
                .then(CommandManager.literal("history").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    ServerPlayNetworking.send(player, new BazarNetworking.OpenScreenPayload("history"));
                    return 1;
                }))
                .then(CommandManager.literal("rank").executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                    BazarProgressionManager.PlayerProgress prog = BazarProgressionManager.get(player.getUuid());
                    String rank = BazarProgressionManager.getRankTitle(prog.respectXp);
                    player.sendMessage(Text.literal("👑 Твой уличный ранг: ")
                            .formatted(Formatting.GOLD)
                            .append(Text.literal(rank).formatted(Formatting.AQUA, Formatting.BOLD))
                            .append(Text.literal(" (" + prog.respectXp + " XP | Побед: " + prog.totalWins + ", Поражений: " + prog.totalLosses + ")").formatted(Formatting.GRAY)), false);
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

        String mobKey = Registries.ENTITY_TYPE.getId(target.getType()).getPath();
        int curWins = BazarProgressionManager.getMobWins(player.getUuid(), mobKey);
        BazarDictionary.PromptBundle bundle = BazarDictionary.generateFor(target, curWins);
        int reqWins = BazarProgressionManager.getRequiredWinsForTier(bundle.tier());

        int effectiveThreshold = Math.min(85, cfg.minWinScore + (curWins * 5));

        ShowdownSession session = new ShowdownSession(player.getUuid(), target, bundle, effectiveThreshold, world.getTime());
        ACTIVE_SHOWDOWNS.put(session.id, session);

        int freezeTicks = (cfg.battleDurationSec + 15) * 20;
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, freezeTicks, 255, false, false));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, freezeTicks, 255, false, false));
        world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, target.getX(), target.getY() + 1.0, target.getZ(), 30, 0.8, 0.5, 0.8, 0.02);

        // Spawn 4 "Смотрящие" (spectators) on both sides of the arena!
        spawnSpectatorsAroundArena(world, player, target, session);

        BazarProgressionManager.PlayerProgress prog = BazarProgressionManager.get(player.getUuid());

        ServerPlayNetworking.send(player, new BazarNetworking.StartShowdownPayload(
                session.id,
                session.targetEntityId,
                session.mobName,
                session.mobStreetNickname,
                session.tierBadge,
                session.mobDissLines,
                session.promptWords,
                cfg.battleDurationSec,
                false,
                curWins,
                reqWins,
                BazarProgressionManager.getRankTitle(prog.respectXp),
                prog.respectXp
        ));

        // Invite nearby friend within 12 blocks
        List<ServerPlayerEntity> friends = world.getPlayers(p -> p != player && p.squaredDistanceTo(player) <= 144.0);
        for (ServerPlayerEntity friend : friends) {
            if (!isPlayerInShowdown(friend.getUuid())) {
                ServerPlayNetworking.send(friend, new BazarNetworking.InviteFriendPayload(
                        session.id,
                        player.getName().getString(),
                        session.mobStreetNickname
                ));
            }
        }
    }

    private void spawnSpectatorsAroundArena(ServerWorld world, ServerPlayerEntity player, LivingEntity target, ShowdownSession session) {
        Vec3d pPos = player.getPos();
        Vec3d mPos = target.getPos();
        Vec3d mid = pPos.add(mPos).multiply(0.5);
        Vec3d dir = mPos.subtract(pPos);
        if (dir.lengthSquared() < 0.01) {
            dir = player.getRotationVec(1.0f);
        }
        dir = new Vec3d(dir.x, 0, dir.z).normalize();
        // Perpendicular vector (left & right sides of the duel!)
        Vec3d side = new Vec3d(-dir.z, 0, dir.x);

        // 4 positions: 2 on the left side, 2 on the right side
        Vec3d[] offsets = new Vec3d[] {
                mid.add(side.multiply(2.8)).add(dir.multiply(-0.8)),
                mid.add(side.multiply(3.2)).add(dir.multiply(0.9)),
                mid.add(side.multiply(-2.8)).add(dir.multiply(-0.8)),
                mid.add(side.multiply(-3.2)).add(dir.multiply(0.9))
        };

        List<String> names = new ArrayList<>(SPECTATOR_NAMES);
        Collections.shuffle(names);

        for (int i = 0; i < offsets.length; i++) {
            Vec3d spot = offsets[i];
            BlockPos topPos = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, BlockPos.ofFloored(spot.x, mid.y, spot.z));
            double spawnY = Math.abs(topPos.getY() - mid.y) <= 3.0 ? topPos.getY() : mid.y;

            VillagerEntity spectator = new VillagerEntity(EntityType.VILLAGER, world);
            spectator.refreshPositionAndAngles(spot.x, spawnY, spot.z, 0, 0);
            spectator.setAiDisabled(true);
            spectator.setInvulnerable(true);
            spectator.setSilent(true);
            spectator.setCustomName(Text.literal(names.get(i % names.size())));
            spectator.setCustomNameVisible(true);
            spectator.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, mid.add(0, 1.2, 0));

            if (world.spawnEntity(spectator)) {
                session.spectatorIds.add(spectator.getUuid());
                world.spawnParticles(ParticleTypes.POOF, spot.x, spawnY + 0.8, spot.z, 10, 0.3, 0.4, 0.3, 0.02);
            }
        }
    }

    private void despawnSpectators(ServerWorld world, ShowdownSession session) {
        for (UUID specId : session.spectatorIds) {
            Entity spec = world.getEntity(specId);
            if (spec != null) {
                world.spawnParticles(ParticleTypes.POOF, spec.getX(), spec.getY() + 0.8, spec.getZ(), 8, 0.25, 0.4, 0.25, 0.02);
                spec.discard();
            }
        }
        session.spectatorIds.clear();
    }

    private void finishShowdown(MinecraftServer server, ShowdownSession session, GeminiJudgeClient.JudgeVerdict verdict) {
        ACTIVE_SHOWDOWNS.remove(session.id);

        ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.primaryPlayerId);
        if (player == null) return;

        ServerWorld world = player.getServerWorld();
        despawnSpectators(world, session);

        LivingEntity mob = (world.getEntity(session.targetMobId) instanceof LivingEntity le && le.isAlive()) ? le : null;

        if (mob != null) {
            mob.removeStatusEffect(StatusEffects.SLOWNESS);
            mob.removeStatusEffect(StatusEffects.RESISTANCE);
            IMMUNE_UNTIL_TICK.put(mob.getUuid(), world.getTime() + 100L);
        }

        BazarProgressionManager.BattleOutcomeUpdate progUpdate = BazarProgressionManager.recordBattle(
                player.getUuid(),
                session.mobTypeKey,
                session.tier,
                verdict.win(),
                verdict.score()
        );

        BazarNetworking.ShowdownResultPayload resultPayload = new BazarNetworking.ShowdownResultPayload(
                verdict.win(),
                verdict.score(),
                session.promptWords,
                verdict.usedWords(),
                verdict.playerTranscript(),
                verdict.bestPunch(),
                verdict.judgeComment(),
                verdict.mobReaction(),
                progUpdate.xpGained(),
                progUpdate.totalXp(),
                progUpdate.newRank(),
                progUpdate.rankUp(),
                progUpdate.mobWinsAfter(),
                progUpdate.mobWinsRequired(),
                progUpdate.newlyDominated()
        );
        ServerPlayNetworking.send(player, resultPayload);

        ServerPlayerEntity helper = session.helperPlayerId != null ? server.getPlayerManager().getPlayer(session.helperPlayerId) : null;
        if (helper != null) {
            ServerPlayNetworking.send(helper, resultPayload);
        }

        if (progUpdate.rankUp()) {
            player.sendMessage(Text.literal("👑 ПОВЫШЕНИЕ РАНГА! Теперь ты: ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal(progUpdate.newRank()).formatted(Formatting.AQUA, Formatting.BOLD)), false);
            world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1.0f, 1.0f);
        }

        if (progUpdate.newlyDominated()) {
            player.sendMessage(Text.literal("🔥 ДОМИНАЦИЯ! Все мобы типа [" + session.mobName + "] теперь боятся тебя и будут съёбывать без боя!")
                    .formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD), false);
        }

        if (verdict.win()) {
            int rankLvl = BazarProgressionManager.getRankLevel(progUpdate.totalXp());
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 600 + rankLvl * 100, rankLvl >= 2 ? 1 : 0));
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 600 + rankLvl * 100, rankLvl >= 4 ? 1 : 0));
            if (rankLvl >= 4) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.LUCK, 1200, 0));
            }
            world.playSound(null, player.getBlockPos(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1.0f, 1.0f);

            if (mob != null) {
                world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, mob.getX(), mob.getY() + 1.0, mob.getZ(), 40, 0.5, 0.8, 0.5, 0.2);
                int emeraldBonus = (rankLvl >= 3 ? 2 : 1) + new Random().nextInt(2);
                world.spawnEntity(new ItemEntity(world, mob.getX(), mob.getY() + 0.5, mob.getZ(), new ItemStack(Items.EMERALD, emeraldBonus)));
                mob.damage(world.getDamageSources().playerAttack(player), 9999.0f);
            }
        } else {
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

    private boolean isSpectatorEntity(UUID entityId) {
        return ACTIVE_SHOWDOWNS.values().stream()
                .anyMatch(s -> s.spectatorIds.contains(entityId));
    }
}
