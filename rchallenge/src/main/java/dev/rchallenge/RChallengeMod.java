package dev.rchallenge;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import static net.minecraft.server.command.CommandManager.literal;

public final class RChallengeMod implements ModInitializer {
    public static final String MOD_ID = "rchallenge";
    public static final ChallengeManager CHALLENGE = new ChallengeManager();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(ChallengePackets.State.ID, ChallengePackets.State.CODEC);
        PayloadTypeRegistry.playS2C().register(ChallengePackets.Candidate.ID, ChallengePackets.Candidate.CODEC);
        PayloadTypeRegistry.playC2S().register(ChallengePackets.Success.ID, ChallengePackets.Success.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(CHALLENGE::attach);
        ServerTickEvents.END_SERVER_TICK.register(CHALLENGE::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> CHALLENGE.sync(handler.player));

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                CHALLENGE.sendCandidate(serverPlayer, ChallengeManager.BLOCK, Registries.BLOCK.getId(state.getBlock()));
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (source.getAttacker() instanceof ServerPlayerEntity player) {
                CHALLENGE.sendCandidate(player, ChallengeManager.MOB, Registries.ENTITY_TYPE.getId(entity.getType()));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(ChallengePackets.Success.ID, (payload, context) ->
                context.server().execute(() -> {
                    Identifier id = Identifier.tryParse(payload.registryId());
                    if (id != null) CHALLENGE.acceptClientSuccess(context.player(), payload.kind(), id);
                }));

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.getContent().getString().trim();
            if (!text.startsWith(".r")) return true;
            handleDotCommand(sender, text);
            return false;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(literal("r")
                        .then(literal("start").executes(ctx -> run(ctx.getSource(), true)))
                        .then(literal("reroll").executes(ctx -> run(ctx.getSource(), false)))));
    }

    private static void handleDotCommand(ServerPlayerEntity player, String text) {
        if (!player.hasPermissionLevel(2)) {
            player.sendMessage(Text.literal("§cТолько оператор может использовать эту команду."), false);
            return;
        }
        if (text.equalsIgnoreCase(".r start")) CHALLENGE.start();
        else if (text.equalsIgnoreCase(".r reroll")) CHALLENGE.reroll();
        else player.sendMessage(Text.literal("§eКоманды: .r start, .r reroll"), false);
    }

    private static int run(ServerCommandSource source, boolean start) {
        if (!source.hasPermissionLevel(2)) {
            source.sendError(Text.literal("Только оператор может использовать эту команду."));
            return 0;
        }
        if (start) CHALLENGE.start(); else CHALLENGE.reroll();
        return Command.SINGLE_SUCCESS;
    }
}
