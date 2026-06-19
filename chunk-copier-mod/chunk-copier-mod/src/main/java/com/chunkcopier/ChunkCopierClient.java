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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkCopierClient implements ClientModInitializer {

    private enum State { IDLE, RECORDING, PENDING_PASTE }
    private State state = State.IDLE;

    private final Map<String, String> copiedBlocks = new ConcurrentHashMap<>();
    private final Set<Long> scannedChunks = ConcurrentHashMap.newKeySet();
    // Chunks waiting to be scanned — drained on the main thread each tick (thread-safe)
    private final Queue<WorldChunk> scanQueue = new ArrayDeque<>();
    private int refX = 0, refZ = 0;

    private static KeyBinding gKey;
    private boolean keyWasDown = false;
    private int scanTick = 0;

    private static final int    DATA_VERSION  = 4189; // Minecraft 1.21.4
    private static final String PENDING_FILE  = "chunk_copier_pending.nbt";
    private static final String WORLD_NAME    = "ChunkCopierExport";
    // How many chunks to fully process per game-tick (limits per-tick main-thread time)
    private static final int    CHUNKS_PER_TICK = 2;

    @Override
    public void onInitializeClient() {
        gKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chunkcopier.g",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.chunkcopier"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            Thread t = new Thread(() -> {
                try { Thread.sleep(3000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }
                client.execute(() -> tryPastePending(client));
            });
            t.setDaemon(true);
            t.start();
        });
    }

    private void onTick(MinecraftClient client) {
        boolean isDown = gKey.isPressed();
        if (isDown && !keyWasDown) handleG(client);
        keyWasDown = isDown;

        if (state == State.RECORDING) {
            // Every 60 ticks (~3 s): enqueue newly visible chunks
            if (++scanTick >= 60) { scanTick = 0; enqueueNewChunks(client); }
            // Every tick: drain a small batch from the queue — always on the main thread
            drainScanQueue(client);
        }
    }

    private void handleG(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        switch (state) {
            case IDLE -> {
                copiedBlocks.clear();
                scannedChunks.clear();
                scanQueue.clear();
                scanTick = 60; // trigger immediate enqueue on next tick
                ChunkPos start = new ChunkPos(client.player.getBlockPos());
                refX = start.getStartX();
                refZ = start.getStartZ();
                state = State.RECORDING;
                localMsg(client, "§a● §fKayıt başladı! Etrafı gez, chunk'lar kopyalanıyor.");
                localMsg(client, "§7G'ye tekrar bas → durdur ve dünya oluştur.");
            }
            case RECORDING -> {
                state = State.PENDING_PASTE;
                scanQueue.clear();
                if (copiedBlocks.isEmpty()) {
                    localMsg(client, "§cHiç blok bulunamadı — tekrar dene.");
                    state = State.IDLE;
                    return;
                }
                localMsg(client, "§a✔ §f" + copiedBlocks.size() + " blok / "
                        + scannedChunks.size() + " chunk kopyalandı. Dünya hazırlanıyor...");
                Thread t = new Thread(() -> {
                    savePending(client);
                    prepareExportWorld(client);
                });
                t.setDaemon(true);
                t.start();
            }
            case PENDING_PASTE ->
                localMsg(client, "§6Zaten bekleyen bir yapıştırma var — ChunkCopierExport dünyasına gir.");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Chunk scanning — ALL block-state reads happen on the main thread to avoid
    // crashes. WorldChunk is not thread-safe; reading it from a background thread
    // causes AccessViolation / ConcurrentModificationException crashes in 1.21.4.
    // ──────────────────────────────────────────────────────────────────────────

    /** Adds newly visible, unscanned chunks to the queue. Called on main thread. */
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
                if (scannedChunks.add(key)) { // mark first to prevent double-queueing
                    scanQueue.add(chunk);
                }
            }
        }
    }

    /** Processes up to CHUNKS_PER_TICK chunks from the queue. Called on main thread. */
    private void drainScanQueue(MinecraftClient client) {
        if (client.world == null) return;
        int minY = client.world.getBottomY();
        int maxY = client.world.getBottomY() + client.world.getHeight();

        int processed = 0;
        while (!scanQueue.isEmpty() && processed < CHUNKS_PER_TICK) {
            WorldChunk chunk = scanQueue.poll();
            if (chunk == null) continue;
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
                    }
                }
            }
            processed++;
        }

        if (processed > 0) {
            localMsg(client, "§7[CC] §f" + copiedBlocks.size() + " blok / "
                    + scannedChunks.size() + " chunk — kuyruk: " + scanQueue.size()
                    + " §e(G = durdur)");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NBT save / world creation
    // ──────────────────────────────────────────────────────────────────────────

    private void savePending(MinecraftClient client) {
        NbtCompound nbt    = new NbtCompound();
        NbtCompound blocks = new NbtCompound();
        nbt.putInt("refX", refX);
        nbt.putInt("refZ", refZ);
        for (var e : copiedBlocks.entrySet()) blocks.putString(e.getKey(), e.getValue());
        nbt.put("blocks", blocks);
        try {
            NbtIo.write(nbt, getPendingPath(client));
        } catch (IOException e) {
            client.execute(() -> localMsg(client, "§cDosya kaydedilemedi: " + e.getMessage()));
        }
    }

    private void prepareExportWorld(MinecraftClient client) {
        Path savesDir = client.runDirectory.toPath().resolve("saves");
        Path worldDir = savesDir.resolve(WORLD_NAME);

        deleteDirectory(worldDir.toFile());

        try {
            Files.createDirectories(worldDir);
            writeLevelDat(worldDir.resolve("level.dat"));
        } catch (Exception e) {
            client.execute(() -> localMsg(client, "§cDünya oluşturulamadı: " + e.getMessage()));
            return;
        }

        client.execute(() -> {
            localMsg(client, "§a✔ §f'ChunkCopierExport' hazır!");
            localMsg(client, "§e→ §fEsc §f→ §eSingleplayer §f→ §eChunkCopierExport §f→ §eGir");
            localMsg(client, "§7Girince bloklar otomatik yapıştırılacak.");
        });
    }

    /**
     * Writes a valid level.dat for Minecraft 1.21.4.
     *
     * Changes vs. old version:
     *  - Overworld generator switched to "minecraft:flat" + void preset.
     *    Flat/void is much simpler (no biome registry lookups) and never fails.
     *  - Added Nether and End dimension entries — MC 1.21.4 expects all three.
     *  - Added generate_features / bonus_chest flags on WorldGenSettings.
     */
    private void writeLevelDat(Path path) throws IOException {
        NbtCompound root = new NbtCompound();
        root.putInt("DataVersion", DATA_VERSION);

        NbtCompound data = new NbtCompound();
        data.putInt("DataVersion", DATA_VERSION);
        data.putString("LevelName", WORLD_NAME);
        data.putInt("GameType", 1);            // Creative
        data.putByte("Difficulty", (byte) 1);
        data.putByte("allowCommands", (byte) 1);
        data.putByte("hardcore", (byte) 0);
        data.putLong("RandomSeed", 0L);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.putInt("SpawnX", 0);
        data.putInt("SpawnY", 65);
        data.putInt("SpawnZ", 0);
        data.putFloat("SpawnAngle", 0f);
        data.putLong("Time", 0L);
        data.putLong("DayTime", 6000L);
        data.putByte("initialized", (byte) 1); // skip MC re-init

        NbtCompound version = new NbtCompound();
        version.putInt("Id", DATA_VERSION);
        version.putString("Name", "1.21.4");
        version.putString("Series", "main");
        version.putByte("Snapshot", (byte) 0);
        data.put("Version", version);

        NbtCompound gameRules = new NbtCompound();
        gameRules.putString("doMobSpawning", "false");
        gameRules.putString("doDaylightCycle", "false");
        gameRules.putString("doWeatherCycle", "false");
        gameRules.putString("keepInventory", "true");
        data.put("GameRules", gameRules);

        // ── WorldGenSettings ──────────────────────────────────────────────────
        NbtCompound wgs = new NbtCompound();
        wgs.putLong("seed", 0L);
        wgs.putByte("generate_features", (byte) 0);
        wgs.putByte("bonus_chest", (byte) 0);

        NbtCompound dims = new NbtCompound();

        // Overworld — flat/void so no terrain generates (just one bedrock layer)
        NbtCompound ow = new NbtCompound();
        ow.putString("type", "minecraft:overworld");

        NbtCompound flatGen = new NbtCompound();
        flatGen.putString("type", "minecraft:flat");

        NbtCompound flatSettings = new NbtCompound();
        flatSettings.putString("biome", "minecraft:the_void");
        flatSettings.putByte("lakes", (byte) 0);
        flatSettings.putByte("features", (byte) 0);
        flatSettings.put("structure_overrides", new NbtList());

        NbtList layers = new NbtList();
        NbtCompound bedrock = new NbtCompound();
        bedrock.putString("block", "minecraft:bedrock");
        bedrock.putInt("height", 1);
        layers.add(bedrock);
        flatSettings.put("layers", layers);

        flatGen.put("settings", flatSettings);
        ow.put("generator", flatGen);
        dims.put("minecraft:overworld", ow);

        // Nether (minimal — MC 1.21.4 expects all three dimensions)
        NbtCompound nether    = new NbtCompound();
        nether.putString("type", "minecraft:the_nether");
        NbtCompound netherGen = new NbtCompound();
        netherGen.putString("type", "minecraft:noise");
        netherGen.putString("settings", "minecraft:nether");
        NbtCompound netherBiome = new NbtCompound();
        netherBiome.putString("type", "minecraft:multi_noise");
        netherBiome.putString("preset", "minecraft:nether");
        netherGen.put("biome_source", netherBiome);
        nether.put("generator", netherGen);
        dims.put("minecraft:the_nether", nether);

        // End
        NbtCompound end    = new NbtCompound();
        end.putString("type", "minecraft:the_end");
        NbtCompound endGen = new NbtCompound();
        endGen.putString("type", "minecraft:noise");
        endGen.putString("settings", "minecraft:end");
        NbtCompound endBiome = new NbtCompound();
        endBiome.putString("type", "minecraft:the_end");
        endGen.put("biome_source", endBiome);
        end.put("generator", endGen);
        dims.put("minecraft:the_end", end);

        wgs.put("dimensions", dims);
        data.put("WorldGenSettings", wgs);
        // ──────────────────────────────────────────────────────────────────────

        root.put("Data", data);
        NbtIo.writeCompressed(root, path);
    }

    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDirectory(f); else f.delete();
        }
        dir.delete();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Paste pending blocks into the export world on JOIN
    // ──────────────────────────────────────────────────────────────────────────

    private void tryPastePending(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server == null || client.player == null) return;

        Path savePath = getPendingPath(client);
        File saveFile = savePath.toFile();
        if (!saveFile.exists()) return;

        NbtCompound nbt;
        try { nbt = NbtIo.read(savePath); }
        catch (IOException e) { localMsg(client, "§cChunk okunamadı: " + e.getMessage()); return; }
        if (nbt == null) return;

        int originX = nbt.getInt("refX");
        int originZ = nbt.getInt("refZ");
        NbtCompound blocks = nbt.getCompound("blocks");

        ServerWorld sw = server.getWorld(World.OVERWORLD);
        if (sw == null) { localMsg(client, "§cOverworld bulunamadı."); return; }

        localMsg(client, "§eYapıştırılıyor: §f" + blocks.getKeys().size() + " blok...");

        AtomicInteger pasted = new AtomicInteger(0);
        Thread t = new Thread(() -> {
            List<Map.Entry<String, String>> entries = new ArrayList<>(
                    blocks.getKeys().stream().map(k -> Map.entry(k, blocks.getString(k))).toList());

            for (int i = 0; i < entries.size(); i += 500) {
                int from = i, to = Math.min(i + 500, entries.size());
                client.execute(() -> {
                    for (int j = from; j < to; j++) {
                        var e  = entries.get(j);
                        String[] p = e.getKey().split(",");
                        int relX = Integer.parseInt(p[0]);
                        int y    = Integer.parseInt(p[1]);
                        int relZ = Integer.parseInt(p[2]);
                        Identifier id = Identifier.of(e.getValue());
                        if (Registries.BLOCK.containsId(id)) {
                            Block block = Registries.BLOCK.get(id);
                            sw.setBlockState(
                                    new BlockPos(originX + relX, y, originZ + relZ),
                                    block.getDefaultState(), 3);
                            pasted.incrementAndGet();
                        }
                    }
                });
                try { Thread.sleep(50); } catch (InterruptedException ex) { break; }
            }

            client.execute(() -> {
                saveFile.delete();
                state = State.IDLE;
                localMsg(client, "§a✔ §fTamamlandı! §e" + pasted.get() + " §fblok yapıştırıldı.");
            });
        });
        t.setDaemon(true);
        t.start();
    }

    private Path getPendingPath(MinecraftClient client) {
        return client.runDirectory.toPath().resolve(PENDING_FILE);
    }

    private void localMsg(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }
}
