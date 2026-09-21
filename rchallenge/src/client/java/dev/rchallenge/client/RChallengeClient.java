package dev.rchallenge.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.rchallenge.ChallengeManager;
import dev.rchallenge.ChallengePackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class RChallengeClient implements ClientModInitializer {
    private static volatile String event = "—";
    private static volatile String letter = "—";
    private static volatile int seconds = 0;
    private static volatile int phase = ChallengeManager.STOPPED;
    private static volatile boolean running = false;
    private static int scanTicks = 0;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(ChallengePackets.State.ID, (payload, context) ->
                context.client().execute(() -> {
                    event = payload.event();
                    letter = payload.letter();
                    seconds = payload.secondsLeft();
                    phase = payload.phase();
                    running = payload.running();
                }));

        ClientPlayNetworking.registerGlobalReceiver(ChallengePackets.Candidate.ID, (payload, context) ->
                context.client().execute(() -> testAndReply(payload.kind(), payload.registryId())));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!running || phase != ChallengeManager.ACTIVE || !"НАЙТИ ПРЕДМЕТ".equals(event)) return;
            if (++scanTicks < 10 || client.player == null) return;
            scanTicks = 0;
            for (int i = 0; i < client.player.getInventory().size(); i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                Identifier id = Registries.ITEM.getId(stack.getItem());
                if (RussianNames.startsWith(ChallengeManager.ITEM, id, letter)) {
                    ClientPlayNetworking.send(new ChallengePackets.Success(ChallengeManager.ITEM, id.toString()));
                }
            }
        });

        HudRenderCallback.EVENT.register((draw, tickCounter) -> renderHud(draw));
    }

    private static void testAndReply(int kind, String rawId) {
        Identifier id = Identifier.tryParse(rawId);
        if (id != null && RussianNames.startsWith(kind, id, letter)) {
            ClientPlayNetworking.send(new ChallengePackets.Success(kind, rawId));
        }
    }

    private static void renderHud(DrawContext draw) {
        if (!running) return;
        MinecraftClient client = MinecraftClient.getInstance();
        int screen = client.getWindow().getScaledWidth();
        int gap = 5;
        int boxW = Math.min(145, (screen - 20 - gap * 2) / 3);
        int total = boxW * 3 + gap * 2;
        int x = (screen - total) / 2;
        int y = 10;
        int color = phase == ChallengeManager.FAILED ? 0xFFFF5555
                : phase == ChallengeManager.COMPLETE ? 0xFF55FF55 : 0xFFFFE35A;

        panel(draw, x, y, boxW, "СОБЫТИЕ", event, color);
        panel(draw, x + boxW + gap, y, boxW, "БУКВА", letter, color);
        panel(draw, x + (boxW + gap) * 2, y, boxW, "ТАЙМЕР", timeText(), color);
    }

    private static void panel(DrawContext draw, int x, int y, int w, String title, String value, int valueColor) {
        MinecraftClient client = MinecraftClient.getInstance();
        draw.fill(x, y, x + w, y + 40, 0xB8101010);
        draw.fill(x, y, x + w, y + 1, 0xAAFFFFFF);
        draw.fill(x, y + 39, x + w, y + 40, 0x66000000);
        draw.drawCenteredTextWithShadow(client.textRenderer, title, x + w / 2, y + 5, 0xFFFFFFFF);
        String fitted = client.textRenderer.trimToWidth(value, w - 8);
        draw.drawCenteredTextWithShadow(client.textRenderer, fitted, x + w / 2, y + 23, valueColor);
    }

    private static String timeText() {
        if (phase == ChallengeManager.SPIN_EVENT || phase == ChallengeManager.SPIN_LETTER || seconds <= 0) return "—";
        if (seconds < 60) return seconds + " сек";
        return (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    private static final class RussianNames {
        private static final Map<String, String> TRANSLATIONS = new HashMap<>();
        private static boolean loaded = false;

        static boolean startsWith(int kind, Identifier id, String wantedLetter) {
            ensureLoaded();
            String prefix = kind == ChallengeManager.ITEM ? "item" : kind == ChallengeManager.BLOCK ? "block" : "entity";
            String key = prefix + "." + id.getNamespace() + "." + id.getPath();
            String name = TRANSLATIONS.getOrDefault(key, "");
            String normalized = name.replaceAll("§.", "").strip().toUpperCase(Locale.forLanguageTag("ru"));
            return !normalized.isEmpty() && normalized.startsWith(wantedLetter.toUpperCase(Locale.forLanguageTag("ru")));
        }

        private static void ensureLoaded() {
            if (loaded) return;
            loaded = true;
            try {
                Identifier lang = Identifier.of("minecraft", "lang/ru_ru.json");
                Resource resource = MinecraftClient.getInstance().getResourceManager().getResource(lang).orElseThrow();
                try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) TRANSLATIONS.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } catch (Exception exception) {
                System.err.println("[R Challenge] Не удалось загрузить ru_ru.json: " + exception);
            }
        }
    }
}
