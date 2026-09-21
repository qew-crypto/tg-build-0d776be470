package dev.rchallenge;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;

public final class ChallengeManager {
    public static final int ITEM = 0, BLOCK = 1, MOB = 2;
    public static final int STOPPED = 0, SPIN_EVENT = 1, SPIN_LETTER = 2, SPIN_TIME = 3,
            ACTIVE = 4, COMPLETE = 5, FAILED = 6;

    private static final Random RANDOM = new Random();
    private static final String[] ITEM_BLOCK_LETTERS =
            {"А","Б","В","Г","Д","Ж","З","И","К","Л","М","Н","О","П","Р","С","Т","У","Ф","Х","Ц","Ч","Ш","Э","Я"};
    private static final String[] MOB_LETTERS =
            {"А","В","Г","Д","Ж","З","И","К","Л","М","О","П","Р","С","Т","Ф","Х","Ч","Ш","Э"};
    private static final String[] EVENT_NAMES = {"НАЙТИ ПРЕДМЕТ", "СЛОМАТЬ БЛОК", "УБИТЬ МОБА"};

    private MinecraftServer server;
    private int event = ITEM;
    private String shownEvent = "—";
    private String letter = "—";
    private int seconds = 0;
    private int phase = STOPPED;
    private int phaseTicks = 0;
    private int activeTicks = 0;
    private final Map<UUID, Map<Identifier, Integer>> inventoryBaseline = new HashMap<>();
    private final Map<UUID, PendingCandidate> pending = new HashMap<>();

    public void attach(MinecraftServer server) { this.server = server; }
    public boolean isActive() { return phase == ACTIVE; }
    public int event() { return event; }
    public String letter() { return letter; }

    public void start() {
        if (server == null) return;
        beginSpin();
        broadcast(Text.literal("§a[R Challenge] Испытание запущено!"));
    }

    public void reroll() {
        if (server == null) return;
        beginSpin();
        broadcast(Text.literal("§e[R Challenge] Задание заменено."));
    }

    private void beginSpin() {
        phase = SPIN_EVENT;
        phaseTicks = 0;
        shownEvent = EVENT_NAMES[RANDOM.nextInt(EVENT_NAMES.length)];
        letter = "—";
        seconds = 0;
        activeTicks = 0;
        inventoryBaseline.clear();
        pending.clear();
        syncAll();
    }

    public void tick(MinecraftServer server) {
        this.server = server;
        phaseTicks++;
        switch (phase) {
            case SPIN_EVENT -> {
                if (phaseTicks % 3 == 0) shownEvent = EVENT_NAMES[RANDOM.nextInt(EVENT_NAMES.length)];
                if (phaseTicks >= 40) {
                    event = RANDOM.nextInt(3);
                    shownEvent = EVENT_NAMES[event];
                    phase = SPIN_LETTER;
                    phaseTicks = 0;
                }
            }
            case SPIN_LETTER -> {
                if (phaseTicks % 3 == 0) letter = randomLetter();
                if (phaseTicks >= 40) {
                    letter = randomLetter();
                    phase = SPIN_TIME;
                    phaseTicks = 0;
                }
            }
            case SPIN_TIME -> {
                if (phaseTicks % 3 == 0) seconds = randomSeconds();
                if (phaseTicks >= 40) {
                    seconds = randomSeconds();
                    activeTicks = seconds * 20;
                    phase = ACTIVE;
                    phaseTicks = 0;
                    snapshotInventories();
                    broadcast(Text.literal("§6Задание: §f" + shownEvent + " §6на букву §f" + letter +
                            " §6за §f" + formatTime(seconds)));
                }
            }
            case ACTIVE -> {
                if (--activeTicks <= 0) fail();
                else seconds = (activeTicks + 19) / 20;
            }
            case COMPLETE -> {
                if (phaseTicks >= 60) beginSpin();
            }
            default -> { }
        }
        if (phase != STOPPED && (phaseTicks % 3 == 0 || phase == ACTIVE && activeTicks % 10 == 0)) syncAll();
    }

    private String randomLetter() {
        String[] source = event == MOB ? MOB_LETTERS : ITEM_BLOCK_LETTERS;
        return source[RANDOM.nextInt(source.length)];
    }

    private int randomSeconds() {
        return event == MOB ? 300 + RANDOM.nextInt(601) : 60 + RANDOM.nextInt(31);
    }

    private void snapshotInventories() {
        inventoryBaseline.clear();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            Map<Identifier, Integer> counts = new HashMap<>();
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (!stack.isEmpty()) counts.merge(Registries.ITEM.getId(stack.getItem()), stack.getCount(), Integer::sum);
            }
            inventoryBaseline.put(player.getUuid(), counts);
        }
    }

    public void sendCandidate(ServerPlayerEntity player, int kind, Identifier id) {
        if (phase != ACTIVE || event != kind) return;
        pending.put(player.getUuid(), new PendingCandidate(kind, id));
        ServerPlayNetworking.send(player, new ChallengePackets.Candidate(kind, id.toString()));
    }

    public void acceptClientSuccess(ServerPlayerEntity player, int kind, Identifier id) {
        if (phase != ACTIVE || event != kind) return;
        if (kind == ITEM) {
            Item item = Registries.ITEM.get(id);
            int now = 0;
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (stack.isOf(item)) now += stack.getCount();
            }
            int before = inventoryBaseline.getOrDefault(player.getUuid(), Map.of()).getOrDefault(id, 0);
            if (now <= before) return;
        } else {
            PendingCandidate candidate = pending.remove(player.getUuid());
            if (candidate == null || candidate.kind != kind || !candidate.id.equals(id)) return;
        }
        complete(player);
    }

    private void complete(ServerPlayerEntity player) {
        if (phase != ACTIVE) return;
        phase = COMPLETE;
        phaseTicks = 0;
        seconds = 0;
        shownEvent = "ВЫПОЛНЕНО";
        broadcast(Text.literal("§a✔ " + player.getName().getString() + " выполнил общее задание!"));
        syncAll();
    }

    private void fail() {
        phase = FAILED;
        phaseTicks = 0;
        seconds = 0;
        shownEvent = "ВРЕМЯ ВЫШЛО";
        broadcast(Text.literal("§cВремя вышло! Используйте §f.r reroll§c для нового задания."));
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.damage(player.getServerWorld(), player.getDamageSources().genericKill(), Float.MAX_VALUE);
        }
        syncAll();
    }

    public void sync(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new ChallengePackets.State(shownEvent, letter, seconds, phase, phase != STOPPED));
    }
    public void syncAll() {
        if (server == null) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) sync(player);
    }
    private void broadcast(Text text) { if (server != null) server.getPlayerManager().broadcast(text, false); }
    private static String formatTime(int total) {
        if (total < 60) return total + " сек";
        return (total / 60) + ":" + String.format(Locale.ROOT, "%02d", total % 60);
    }
    private record PendingCandidate(int kind, Identifier id) {}
}
