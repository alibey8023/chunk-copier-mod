package com.chunkcopier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chunk Copier — simplified, crash-free flow:
 *
 *  1) Source world  : press G → walk around → press G again → blocks saved to disk
 *  2) Destination   : open ANY singleplayer world you created → blocks paste automatically,
 *                     chunk by chunk (small batches per tick, no lag / freeze)
 *
 * No automatic world creation, no disconnect calls → no crashes.
 */
public class ChunkCopierClient implements ClientModInitializer {

    // ── State ─────────────────────────────────────────────────────────────────

    private enum RecordState { IDLE, RECORDING }
    private RecordState recordState = RecordState.IDLE;

    private final Map<String, String> copiedBlocks = new ConcurrentHashMap<>();
    private final Set<Long>           scannedChunks = ConcurrentHashMap.newKeySet();
    private final Queue<WorldChunk>   scanQueue     = new ArrayDeque<>();
    private int refX = 0, refZ = 0;

    // Paste state — lives across world loads
    private List<Map.Entry<String, String>> pasteList    = null;
    private int                              pasteIndex   = 0;
    private int                              pasteOriginX = 0;
    private int                              pasteOriginZ = 0;
    private final AtomicInteger              pasteCount   = new AtomicInteger(0);

    private static KeyBinding gKey;
    private boolean keyWasDown = false;
    private int     scanTick   = 0;

    /** Blocks pasted per tick during paste phase. Keep low to avoid TPS/FPS hitches. */
    private static final int  PASTE_PER_TICK = 50;
    /** Max nanoseconds spent scanning per tick (2 ms = 4 % of a 50 ms tick). */
    private static final long SCAN_BUDGET_NS = 2_000_000L;

    private static final String PENDING_FILE = "chunk_copier_pending.nbt";

    // ── Init ──────────────────────────────────────────────────────────────────

    @Override
    public void onInitializeClient() {
        gKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chunkcopier.g",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.chunkcopier"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // When joining ANY singleplayer world: load pending.nbt and start pasting
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            client.execute(() -> loadPendingAndStartPaste(client))
        );
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    private void onTick(MinecraftClient client) {
        // Key edge detection
        boolean isDown = gKey.isPressed();
        if (isDown && !keyWasDown) handleG(client);
        keyWasDown = isDown;

        // Chunk scanning (main thread, time-budgeted)
        if (recordState == RecordState.RECORDING) {
            if (++scanTick >= 60) { scanTick = 0; enqueueNewChunks(client); }
            drainScanQueue(client);
        }

        // Incremental paste — one small batch per tick
        if (pasteList != null && !pasteList.isEmpty()) {
            tickPaste(client);
        }
    }

    // ── G key handler ─────────────────────────────────────────────────────────

    private void handleG(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        switch (recordState) {
            case IDLE -> {
                copiedBlocks.clear();
                scannedChunks.clear();
                scanQueue.clear();
                scanTick = 60; // trigger immediate enqueue next tick
                ChunkPos start = new ChunkPos(client.player.getBlockPos());
                refX = start.getStartX();
                refZ = start.getStartZ();
                recordState = RecordState.RECORDING;
                localMsg(client, "§a● §fKayıt başladı — etrafı gez, chunk'lar kopyalanıyor.");
                localMsg(client, "§7G'ye tekrar bas → kaydı bitir ve diske kaydet.");
            }
            case RECORDING -> {
                recordState = RecordState.IDLE;
                scanQueue.clear();
                if (copiedBlocks.isEmpty()) {
                    localMsg(client, "§cHiç blok bulunamadı — tekrar dene.");
                    return;
                }
                int count = copiedBlocks.size();
                int chunks = scannedChunks.size();
                // Save to disk in background (NBT I/O, doesn't touch game state)
                Map<String, String> snapshot = new HashMap<>(copiedBlocks);
                int snapRefX = refX, snapRefZ = refZ;
                Thread t = new Thread(() -> {
                    savePending(client, snapshot, snapRefX, snapRefZ);
                    client.execute(() -> {
                        localMsg(client, "§a✔ §f" + count + " blok / " + chunks + " chunk kaydedildi.");
                        localMsg(client, "§e→ §fBoş bir dünyaya gir → bloklar otomatik yapıştırılacak.");
                    });
                });
                t.setDaemon(true);
                t.start();
            }
        }
    }

    // ── Chunk scanning ────────────────────────────────────────────────────────

    private void enqueueNewChunks(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        int viewDist = Math.min(client.options.getViewDistance().getValue(), 10);
        ChunkPos center = new ChunkPos(client.player.getBlockPos());
        for (int cx = center.x - viewDist; cx <= center.x + viewDist; cx++) {
            for (int cz = center.z - viewDist; cz <= center.z + viewDist; cz++) {
                long key = ChunkPos.toLong(cx, cz);
                if (scannedChunks.contains(key)) continue;
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;
                if (scannedChunks.add(key)) scanQueue.add(chunk);
            }
        }
    }

    /**
     * Reads chunk block states on the MAIN THREAD with a strict nanosecond budget.
     * Never spends more than SCAN_BUDGET_NS (2 ms) per tick → no freezes, no FPS loss.
     */
    private void drainScanQueue(MinecraftClient client) {
        if (client.world == null || scanQueue.isEmpty()) return;
        int minY = client.world.getBottomY();
        int maxY = client.world.getBottomY() + client.world.getHeight();

        long deadline = System.nanoTime() + SCAN_BUDGET_NS;
        boolean didWork = false;

        outer:
        while (!scanQueue.isEmpty()) {
            WorldChunk chunk = scanQueue.peek();
            ChunkPos cp = chunk.getPos();
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = cp.getStartX() + lx;
                    int wz = cp.getStartZ() + lz;
                    for (int y = minY; y < maxY; y++) {
                        var bs = chunk.getBlockState(new BlockPos(wx, y, wz));
                        if (!bs.isAir()) {
                            copiedBlocks.put(
                                (wx - refX) + "," + y + "," + (wz - refZ),
                                Registries.BLOCK.getId(bs.getBlock()).toString()
                            );
                        }
                        // Check budget every 256 iterations (reduces nanoTime overhead)
                        if ((y & 0xFF) == 0 && System.nanoTime() >= deadline) break outer;
                    }
                }
            }
            scanQueue.poll(); // finished this chunk
            didWork = true;
            if (System.nanoTime() >= deadline) break;
        }

        if (didWork) {
            localMsg(client, "§7[CC] §f" + copiedBlocks.size() + " blok / "
                    + scannedChunks.size() + " chunk — kuyruk: " + scanQueue.size()
                    + " §7| G = durdur");
        }
    }

    // ── NBT I/O ───────────────────────────────────────────────────────────────

    private void savePending(MinecraftClient client, Map<String, String> blocks,
                             int originX, int originZ) {
        NbtCompound nbt   = new NbtCompound();
        NbtCompound bNbt  = new NbtCompound();
        nbt.putInt("refX", originX);
        nbt.putInt("refZ", originZ);
        for (var e : blocks.entrySet()) bNbt.putString(e.getKey(), e.getValue());
        nbt.put("blocks", bNbt);
        try {
            NbtIo.write(nbt, getPendingPath(client));
        } catch (IOException e) {
            client.execute(() -> localMsg(client, "§cKayıt hatası: " + e.getMessage()));
        }
    }

    private void loadPendingAndStartPaste(MinecraftClient client) {
        // Only paste into singleplayer worlds (integrated server present)
        IntegratedServer server = client.getServer();
        if (server == null || client.player == null) return;

        File saveFile = getPendingPath(client).toFile();
        if (!saveFile.exists()) return;

        NbtCompound nbt;
        try { nbt = NbtIo.read(getPendingPath(client)); }
        catch (IOException e) { localMsg(client, "§cPending okunamadı: " + e.getMessage()); return; }
        if (nbt == null) return;

        NbtCompound bNbt = nbt.getCompound("blocks");
        if (bNbt.isEmpty()) { saveFile.delete(); return; }

        pasteOriginX = nbt.getInt("refX");
        pasteOriginZ = nbt.getInt("refZ");
        pasteList    = new ArrayList<>(bNbt.getKeys().stream()
                .map(k -> Map.entry(k, bNbt.getString(k))).toList());
        pasteIndex   = 0;
        pasteCount.set(0);

        localMsg(client, "§eYapıştırılıyor: §f" + pasteList.size()
                + " blok (her tick " + PASTE_PER_TICK + " blok)...");
    }

    // ── Incremental paste (called every tick) ─────────────────────────────────

    private void tickPaste(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null || client.player == null) return;

        ServerWorld sw = server.getWorld(World.OVERWORLD);
        if (sw == null) return;

        int end = Math.min(pasteIndex + PASTE_PER_TICK, pasteList.size());
        for (int i = pasteIndex; i < end; i++) {
            var e = pasteList.get(i);
            String[] p = e.getKey().split(",");
            int relX = Integer.parseInt(p[0]);
            int y    = Integer.parseInt(p[1]);
            int relZ = Integer.parseInt(p[2]);
            Identifier id = Identifier.of(e.getValue());
            if (Registries.BLOCK.containsId(id)) {
                Block block = Registries.BLOCK.get(id);
                sw.setBlockState(
                        new BlockPos(pasteOriginX + relX, y, pasteOriginZ + relZ),
                        block.getDefaultState(), 3);
                pasteCount.incrementAndGet();
            }
        }
        pasteIndex = end;

        // Progress message every 5000 blocks
        if (pasteIndex % 5000 < PASTE_PER_TICK) {
            localMsg(client, "§7[CC] §eYapıştırılıyor... §f"
                    + pasteIndex + " / " + pasteList.size());
        }

        if (pasteIndex >= pasteList.size()) {
            // Done — clean up
            getPendingPath(client).toFile().delete();
            localMsg(client, "§a✔ §fTamamlandı! §e" + pasteCount.get() + " §fblok yapıştırıldı.");
            pasteList  = null;
            pasteIndex = 0;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path getPendingPath(MinecraftClient client) {
        return client.runDirectory.toPath().resolve(PENDING_FILE);
    }

    private void localMsg(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }
}
