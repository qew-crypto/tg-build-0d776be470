package dev.rchallenge;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public final class ChallengePackets {
    private ChallengePackets() {}

    public record State(String event, String letter, int secondsLeft, int phase, boolean running)
            implements CustomPayload {
        public static final Id<State> ID = new Id<>(Identifier.of(RChallengeMod.MOD_ID, "state"));
        public static final PacketCodec<RegistryByteBuf, State> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, State::event,
                PacketCodecs.STRING, State::letter,
                PacketCodecs.VAR_INT, State::secondsLeft,
                PacketCodecs.VAR_INT, State::phase,
                PacketCodecs.BOOLEAN, State::running,
                State::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Server asks the responsible client to test a broken block or killed mob by its Russian name. */
    public record Candidate(int kind, String registryId) implements CustomPayload {
        public static final Id<Candidate> ID = new Id<>(Identifier.of(RChallengeMod.MOD_ID, "candidate"));
        public static final PacketCodec<RegistryByteBuf, Candidate> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Candidate::kind,
                PacketCodecs.STRING, Candidate::registryId,
                Candidate::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Client reports a candidate whose bundled Russian translation starts with the active letter. */
    public record Success(int kind, String registryId) implements CustomPayload {
        public static final Id<Success> ID = new Id<>(Identifier.of(RChallengeMod.MOD_ID, "success"));
        public static final PacketCodec<RegistryByteBuf, Success> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Success::kind,
                PacketCodecs.STRING, Success::registryId,
                Success::new);
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }
}
