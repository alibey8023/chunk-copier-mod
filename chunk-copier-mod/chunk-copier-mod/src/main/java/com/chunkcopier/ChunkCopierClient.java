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

public class ChunkCopierClient implements ClientModInitializer {

    // ── Recording state ───────────────────────────────────────────────────────

    private enum RecordState { IDLE, RECORDING }
    private RecordState recordState = RecordState.IDLE;

    private final Map<String, String> copiedBlocks  = new ConcurrentHashMap<>();
    private final Set<Long>           scannedChunks = ConcurrentHashMap.newKeySet();
    private final Queue<WorldChunk>   scanQueue     = new ArrayDeque<>();
    private int refX = 0, refZ = 0;

    // Per-chunk scan cursor — remembers where we left off inside a chunk
    // so we never restart from lx=0,lz=0,y=minY after a budget cut.
    private WorldChunk activeScanChunk = null;
    private int        scanLx = 0, scanLz = 0, scanY = 0;
    private int        scanMinY = 0, scanMaxY = 0;

    // ── Paste state ───────────────────────────────────────────────────────────

    private List<Map.Entry<String, String>> pasteList    = null;
    private int                              pasteIndex   = 0;
    private int                              pasteOriginX = 0;
    private int                              pasteOriginZ = 0;
    private final AtomicInteger              pasteCount   = new AtomicInteger(0);

    // ── Key / tick ────────────────────────────────────────────────────────────

    private static KeyBinding gKey;
    private boolean keyWasDown = false;

    /** Ticks between chunk-discovery sweeps (60 = every 3 s). */
    private int enqueueTimer = 0;
    /** Ticks between status messages (100 = every 5 s). */
    private int logTimer = 0;

    /** Max nanoseconds spent scanning per tick (3 ms = 6 % of a 50 ms tick). */
    private static final long SCAN_BUDGET_NS = 3_000_000L;
    /** Blocks pasted per tick — small enough that the server never hiccups. */
    private static final int  PASTE_PER_TICK = 64;

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
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            client.execute(() -> loadPendingAndStartPaste(client))
        );
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    private void onTick(MinecraftClient client) {
        boolean isDown = gKey.isPressed();
        if (isDown && !keyWasDown) handleG(client);
        keyWasDown = isDown;

        if (recordState == RecordState.RECORDING) {
            // Discover new visible chunks periodically
            if (++enqueueTimer >= 60) { enqueueTimer = 0; enqueueNewChunks(client); }
            // Continue scanning from where we left off
            drainScanQueue(client);
            // Status log every 5 seconds
            if (++logTimer >= 100 && client.player != null) {
                logTimer = 0;
                int queued = scanQueue.size() + (activeScanChunk != null ? 1 : 0);
                localMsg(client, "§7[CC] §f" + copiedBlocks.size() + " blok, "
                        + scannedChunks.size() + " chunk — kopyalanıyor"
                        + (queued > 0 ? " (§e" + queued + " chunk§7 kuyrukta)" : " §a✔")
                        + " §7| G = bitir");
            }
        }

        if (pasteList != null) tickPaste(client);
    }

    // ── G key ─────────────────────────────────────────────────────────────────

    private void handleG(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        switch (recordState) {
            case IDLE -> {
                copiedBlocks.clear();
                scannedChunks.clear();
                scanQueue.clear();
                activeScanChunk = null;
                enqueueTimer = 60; // trigger immediate discovery
                logTimer = 0;
                ChunkPos start = new ChunkPos(client.player.getBlockPos());
                refX = start.getStartX();
                refZ = start.getStartZ();
                recordState = RecordState.RECORDING;
                localMsg(client, "§a● §fKayıt başladı — etrafı gez. §7G = durdur.");
            }
            case RECORDING -> {
                recordState = RecordState.IDLE;
                scanQueue.clear();
                activeScanChunk = null;
                if (copiedBlocks.isEmpty()) {
                    localMsg(client, "§cHiç blok bulunamadı, tekrar dene."); return;
                }
                int count  = copiedBlocks.size();
                int chunks = scannedChunks.size();
                Map<String, String> snapshot = new HashMap<>(copiedBlocks);
                int sx = refX, sz = refZ;
                new Thread(() -> {
                    savePending(client, snapshot, sx, sz);
                    client.execute(() -> {
                        localMsg(client, "§a✔ §f" + count + " blok / " + chunks
                                + " chunk kaydedildi.");
                        localMsg(client, "§e→ §fBoş dünyana gir, bloklar otomatik yapıştırılır.");
                    });
                }, "cc-save").start();
            }
        }
    }

    // ── Chunk discovery ───────────────────────────────────────────────────────

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

    // ── Chunk scanning — time-budgeted, continues across ticks ───────────────
    //
    // KEY FIX: we store (activeScanChunk, scanLx, scanLz, scanY) so each tick
    // we resume exactly where the budget cut us off.  Previously the code used
    // peek() + restart-from-zero each tick, so large chunks were never fully read.

    private void drainScanQueue(MinecraftClient client) {
        if (client.world == null) return;

        // Pick up a new chunk from the queue if we don't have one in progress
        if (activeScanChunk == null) {
            if (scanQueue.isEmpty()) return;
            activeScanChunk = scanQueue.poll();
            scanLx   = 0;
            scanLz   = 0;
            scanMinY = client.world.getBottomY();
            scanMaxY = client.world.getBottomY() + client.world.getHeight();
            scanY    = scanMinY;
        }

        long deadline = System.nanoTime() + SCAN_BUDGET_NS;
        ChunkPos cp = activeScanChunk.getPos();

        scan:
        while (true) {
            int wx = cp.getStartX() + scanLx;
            int wz = cp.getStartZ() + scanLz;

            while (scanY < scanMaxY) {
                var bs = activeScanChunk.getBlockState(new BlockPos(wx, scanY, wz));
                if (!bs.isAir()) {
                    copiedBlocks.put(
                        (wx - refX) + "," + scanY + "," + (wz - refZ),
                        Registries.BLOCK.getId(bs.getBlock()).toString()
                    );
                }
                scanY++;
                // Budget check every 256 Y levels to reduce nanoTime() calls
                if ((scanY & 0xFF) == 0 && System.nanoTime() >= deadline) break scan;
            }

            // Column done — advance to next column
            scanY = scanMinY;
            scanLz++;
            if (scanLz >= 16) { scanLz = 0; scanLx++; }
            if (scanLx >= 16) {
                // Chunk fully scanned
                activeScanChunk = null;
                // Start next chunk if budget remains
                if (scanQueue.isEmpty() || System.nanoTime() >= deadline) break scan;
                activeScanChunk = scanQueue.poll();
                cp = activeScanChunk.getPos();
                scanLx = 0; scanLz = 0; scanY = scanMinY;
            }
        }
    }

    // ── NBT I/O ───────────────────────────────────────────────────────────────

    private void savePending(MinecraftClient client, Map<String, String> blocks,
                             int originX, int originZ) {
        NbtCompound nbt  = new NbtCompound();
        NbtCompound bNbt = new NbtCompound();
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
        if (client.getServer() == null || client.player == null) return;
        File f = getPendingPath(client).toFile();
        if (!f.exists()) return;
        NbtCompound nbt;
        try { nbt = NbtIo.read(getPendingPath(client)); }
        catch (IOException e) { localMsg(client, "§cPending okunamadı: " + e.getMessage()); return; }
        if (nbt == null) return;
        NbtCompound bNbt = nbt.getCompound("blocks");
        if (bNbt.isEmpty()) { f.delete(); return; }

        pasteOriginX = nbt.getInt("refX");
        pasteOriginZ = nbt.getInt("refZ");
        pasteList    = new ArrayList<>(bNbt.getKeys().stream()
                           .map(k -> Map.entry(k, bNbt.getString(k))).toList());
        pasteIndex   = 0;
        pasteCount.set(0);
        localMsg(client, "§e▶ §fYapıştırma başladı: §e" + pasteList.size()
                + " §fblok — her tick §e" + PASTE_PER_TICK + " §fblok.");
    }

    // ── Paste — one small batch per tick ──────────────────────────────────────

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
                sw.setBlockState(
                    new BlockPos(pasteOriginX + relX, y, pasteOriginZ + relZ),
                    Registries.BLOCK.get(id).getDefaultState(), 3);
                pasteCount.incrementAndGet();
            }
        }
        pasteIndex = end;

        // Progress log every ~5 000 blocks
        if (pasteList.size() > 0 && (pasteIndex % 5000) < PASTE_PER_TICK) {
            int pct = pasteIndex * 100 / pasteList.size();
            localMsg(client, "§7[CC] §fYapıştırılıyor §e" + pct + "%"
                    + " §7(" + pasteIndex + "/" + pasteList.size() + " blok)");
        }

        if (pasteIndex >= pasteList.size()) {
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
