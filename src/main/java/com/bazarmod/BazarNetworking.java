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

    // S2C: Start showdown on client
    public record StartShowdownPayload(
            UUID showdownId,
            String mobName,
            String mobClaim,
            List<String> promptWords,
            int durationSec,
            boolean isHelper
    ) implements CustomPayload {
        public static final Id<StartShowdownPayload> ID = new Id<>(Identifier.of(BazarMod.MOD_ID, "start_showdown"));
        public static final PacketCodec<RegistryByteBuf, StartShowdownPayload> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> {
                    buf.writeUuid(payload.showdownId);
                    buf.writeString(payload.mobName);
                    buf.writeString(payload.mobClaim);
                    writeStringList(buf, payload.promptWords);
                    buf.writeVarInt(payload.durationSec);
                    buf.writeBoolean(payload.isHelper);
                },
                buf -> new StartShowdownPayload(
                        buf.readUuid(),
                        buf.readString(),
                        buf.readString(),
                        readStringList(buf),
                        buf.readVarInt(),
                        buf.readBoolean()
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

    // S2C: Showdown result from AI judge
    public record ShowdownResultPayload(
            boolean win,
            int score,
            List<String> usedWords,
            String judgeComment,
            String mobReaction
    ) implements CustomPayload {
        public static final Id<ShowdownResultPayload> ID = new Id<>(Identifier.of(BazarMod.MOD_ID, "showdown_result"));
        public static final PacketCodec<RegistryByteBuf, ShowdownResultPayload> CODEC = PacketCodec.ofStatic(
                (buf, payload) -> {
                    buf.writeBoolean(payload.win);
                    buf.writeVarInt(payload.score);
                    writeStringList(buf, payload.usedWords);
                    buf.writeString(payload.judgeComment);
                    buf.writeString(payload.mobReaction);
                },
                buf -> new ShowdownResultPayload(
                        buf.readBoolean(),
                        buf.readVarInt(),
                        readStringList(buf),
                        buf.readString(),
                        buf.readString()
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

    public static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(StartShowdownPayload.ID, StartShowdownPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(InviteFriendPayload.ID, InviteFriendPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ShowdownResultPayload.ID, ShowdownResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StopMusicPayload.ID, StopMusicPayload.CODEC);

        PayloadTypeRegistry.playC2S().register(SubmitAudioPayload.ID, SubmitAudioPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(JoinShowdownPayload.ID, JoinShowdownPayload.CODEC);
    }
}
