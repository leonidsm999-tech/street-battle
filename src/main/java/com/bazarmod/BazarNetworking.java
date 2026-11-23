package com.bazarmod;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class BazarNetworking {

    private static void writeStringList(RegistryByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) {
            buf.writeString(s);
        }
    }

    private static List<String> readStringList(RegistryByteBuf buf) {
        int size = buf.readVarInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readString());
        }
        return list;
    }

    // S2C: Start showdown on client (includes mob progression info)
    public record StartShowdownPayload(
            UUID showdownId,
            int mobEntityId,
            String mobName,
            List<String> mobDissLines,
            List<String> promptWords,
            int durationSec,
            boolean isHelper,
            int mobCurrentWins,
            int mobRequiredWins,
            String playerRank,
            int playerXp
    ) implements CustomPayload {
        public static final Id<StartShowdownPayload> ID = new Id<>(Identifier.of(BazarMod.MOD_ID, "start_showdown"));
        public static final PacketCodec<RegistryByteBuf, StartShowdownPayload> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> {
                    buf.writeUuid(payload.showdownId);
                    buf.writeVarInt(payload.mobEntityId);
                    buf.writeString(payload.mobName);
                    writeStringList(buf, payload.mobDissLines);
                    writeStringList(buf, payload.promptWords);
                    buf.writeVarInt(payload.durationSec);
                    buf.writeBoolean(payload.isHelper);
                    buf.writeVarInt(payload.mobCurrentWins);
                    buf.writeVarInt(payload.mobRequiredWins);
                    buf.writeString(payload.playerRank);
                    buf.writeVarInt(payload.playerXp);
                },
                buf -> new StartShowdownPayload(
                        buf.readUuid(),
                        buf.readVarInt(),
                        buf.readString(),
                        readStringList(buf),
                        readStringList(buf),
                        buf.readVarInt(),
                        buf.readBoolean(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readString(),
                        buf.readVarInt()
                )
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // S2C: Invite nearby friend to join showdown
    public record InviteFriendPayload(
            UUID showdownId,
            String friendName,
            String mobName
    ) implements CustomPayload {
        public static final Id<InviteFriendPayload> ID = new Id<>(Identifier.of(BazarMod.MOD_ID, "invite_friend"));
        public static final PacketCodec<RegistryByteBuf, InviteFriendPayload> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> {
                    buf.writeUuid(payload.showdownId);
                    buf.writeString(payload.friendName);
                    buf.writeString(payload.mobName);
                },
                buf -> new InviteFriendPayload(
                        buf.readUuid(),
                        buf.readString(),
                        buf.readString()
                )
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // S2C: Showdown result + detailed breakdown + progression update
    public record ShowdownResultPayload(
            boolean win,
            int score,
            List<String> promptWords,
            List<String> usedWords,
            String playerTranscript,
            String bestPunch,
            String judgeComment,
            String mobReaction,
            int xpGained,
            int totalXp,
            String rankTitle,
            boolean rankUp,
            int mobWinsAfter,
            int mobWinsRequired,
            boolean newlyDominated
    ) implements CustomPayload {
        public static final Id<ShowdownResultPayload> ID = new Id<>(Identifier.of(BazarMod.MOD_ID, "showdown_result"));
        public static final PacketCodec<RegistryByteBuf, ShowdownResultPayload> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> {
                    buf.writeBoolean(payload.win);
                    buf.writeVarInt(payload.score);
                    writeStringList(buf, payload.promptWords);
                    writeStringList(buf, payload.usedWords);
                    buf.writeString(payload.playerTranscript);
                    buf.writeString(payload.bestPunch);
                    buf.writeString(payload.judgeComment);
                    buf.writeString(payload.mobReaction);
                    buf.writeVarInt(payload.xpGained);
                    buf.writeVarInt(payload.totalXp);
                    buf.writeString(payload.rankTitle);
                    buf.writeBoolean(payload.rankUp);
                    buf.writeVarInt(payload.mobWinsAfter);
                    buf.writeVarInt(payload.mobWinsRequired);
                    buf.writeBoolean(payload.newlyDominated);
                },
                buf -> new ShowdownResultPayload(
                        buf.readBoolean(),
                        buf.readVarInt(),
                        readStringList(buf),
                        readStringList(buf),
                        buf.readString(),
                        buf.readString(),
                        buf.readString(),
                        buf.readString(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readString(),
                        buf.readBoolean(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readBoolean()
                )
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // S2C: Stop battle theme music
    public record StopMusicPayload(boolean stop) implements CustomPayload {
        public static final Id<StopMusicPayload> ID = new Id<>(Identifier.of(BazarMod.MOD_ID, "stop_music"));
        public static final PacketCodec<RegistryByteBuf, StopMusicPayload> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> buf.writeBoolean(payload.stop),
                buf -> new StopMusicPayload(buf.readBoolean())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // S2C: Open screen on client ("config" or "history")
    public record OpenScreenPayload(String screenName) implements CustomPayload {
        public static final Id<OpenScreenPayload> ID = new Id<>(Identifier.of(BazarMod.MOD_ID, "open_screen"));
        public static final PacketCodec<RegistryByteBuf, OpenScreenPayload> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> buf.writeString(payload.screenName),
                buf -> new OpenScreenPayload(buf.readString())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // C2S: Client sends recorded WAV audio bytes
    public record SubmitAudioPayload(UUID showdownId, byte[] wavData) implements CustomPayload {
        public static final Id<SubmitAudioPayload> ID = new Id<>(Identifier.of(BazarMod.MOD_ID, "submit_audio"));
        public static final PacketCodec<RegistryByteBuf, SubmitAudioPayload> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> {
                    buf.writeUuid(payload.showdownId);
                    buf.writeByteArray(payload.wavData);
                },
                buf -> new SubmitAudioPayload(
                        buf.readUuid(),
                        buf.readByteArray(2_500_000)
                )
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // C2S: Friend accepts invite to back up player
    public record JoinShowdownPayload(UUID showdownId) implements CustomPayload {
        public static final Id<JoinShowdownPayload> ID = new Id<>(Identifier.of(BazarMod.MOD_ID, "join_showdown"));
        public static final PacketCodec<RegistryByteBuf, JoinShowdownPayload> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> buf.writeUuid(payload.showdownId),
                buf -> new JoinShowdownPayload(buf.readUuid())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // C2S: Sync config edited in-game to server
    public record SyncConfigPayload(
            String apiKey,
            String model,
            String proxyHost,
            int proxyPort,
            int battleDurationSec,
            int minWinScore,
            boolean mockMode
    ) implements CustomPayload {
        public static final Id<SyncConfigPayload> ID = new Id<>(Identifier.of(BazarMod.MOD_ID, "sync_config"));
        public static final PacketCodec<RegistryByteBuf, SyncConfigPayload> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> {
                    buf.writeString(payload.apiKey);
                    buf.writeString(payload.model);
                    buf.writeString(payload.proxyHost);
                    buf.writeVarInt(payload.proxyPort);
                    buf.writeVarInt(payload.battleDurationSec);
                    buf.writeVarInt(payload.minWinScore);
                    buf.writeBoolean(payload.mockMode);
                },
                buf -> new SyncConfigPayload(
                        buf.readString(),
                        buf.readString(),
                        buf.readString(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readBoolean()
                )
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(StartShowdownPayload.ID, StartShowdownPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(InviteFriendPayload.ID, InviteFriendPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ShowdownResultPayload.ID, ShowdownResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StopMusicPayload.ID, StopMusicPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenScreenPayload.ID, OpenScreenPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(SubmitAudioPayload.ID, SubmitAudioPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(JoinShowdownPayload.ID, JoinShowdownPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SyncConfigPayload.ID, SyncConfigPayload.CODEC);
    }
}
