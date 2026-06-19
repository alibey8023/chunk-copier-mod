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
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.level.storage.LevelStorage;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ChunkCopierClient implements ClientModInitializer {

    private enum State { IDLE, RECORDING, PENDING_PASTE }
    private State state = State.IDLE;

    private final Map<String, String> copiedBlocks = new ConcurrentHashMap<>();
    private final Set<Long> scannedChunks = ConcurrentHashMap.newKeySet();
    private int refX = 0, refZ = 0;

    private static KeyBinding gKey;
    private boolean keyWasDown = false;
    private int scanTick = 0;

    private static final int    DATA_VERSION = 4189; // Minecraft 1.21.4
    private static final String PENDING_FILE = "chunk_copier_pending.nbt";
    private static final String WORLD_NAME   = "ChunkCopierExport";

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
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
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
        if (state == State.RECORDING && ++scanTick >= 60) { scanTick = 0; scheduleScan(client); }
    }

    private void handleG(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        switch (state) {
            case IDLE -> {
                copiedBlocks.clear();
                scannedChunks.clear();
                scanTick = 60;
                ChunkPos start = new ChunkPos(client.player.getBlockPos());
                refX = start.getStartX();
                refZ = start.getStartZ();
                state = State.RECORDING;
                localMsg(client, "§a● §fKayıt başladı! Etrafı gez, chunk'lar arka planda kopyalanıyor.");
                localMsg(client, "§7G'ye tekrar bas → durdur ve 'ChunkCopierExport' dünyasına yapıştır.");
            }
            case RECORDING -> {
                state = State.PENDING_PASTE;
                if (copiedBlocks.isEmpty()) {
                    localMsg(client, "§cHiç blok bulunamadı — tekrar dene.");
                    state = State.IDLE;
                    return;
                }
                localMsg(client, "§a✔ §f" + copiedBlocks.size() + " blok / " + scannedChunks.size()
                        + " chunk kopyalandı. Dünya açılıyor...");
                Thread t = new Thread(() -> { savePending(client); openOrCreateWorld(client); });
                t.setDaemon(true);
                t.start();
            }
            case PENDING_PASTE -> localMsg(client, "§6Zaten bekleyen bir yapıştırma var. Dünyaya gir.");
        }
    }

    private void scheduleScan(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        int viewDist = Math.min(client.options.getViewDistance().getValue(), 10);
        ChunkPos center = new ChunkPos(client.player.getBlockPos());
        int minY = client.world.getBottomY();
        int maxY = client.world.getBottomY() + client.world.getHeight();

        List<WorldChunk> toScan = new ArrayList<>();
        for (int cx = center.x - viewDist; cx <= center.x + viewDist; cx++) {
            for (int cz = center.z - viewDist; cz <= center.z + viewDist; cz++) {
                long key = ChunkPos.toLong(cx, cz);
                if (scannedChunks.contains(key)) continue;
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;
                scannedChunks.add(key);
                toScan.add(chunk);
            }
        }
        if (toScan.isEmpty()) return;

        Thread scanner = new Thread(() -> {
            Map<String, String> batch = new HashMap<>();
            for (WorldChunk chunk : toScan) {
                ChunkPos cp = chunk.getPos();
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int wx = cp.getStartX() + lx;
                        int wz = cp.getStartZ() + lz;
                        for (int y = minY; y < maxY; y++) {
                            var bs = chunk.getBlockState(new BlockPos(wx, y, wz));
                            if (!bs.isAir()) {
                                batch.put((wx - refX) + "," + y + "," + (wz - refZ),
                                        Registries.BLOCK.getId(bs.getBlock()).toString());
                            }
                        }
                    }
                }
            }
            client.execute(() -> {
                copiedBlocks.putAll(batch);
                localMsg(client, "§7[CC] §f" + copiedBlocks.size() + " blok / " + scannedChunks.size()
                        + " chunk — §edevam et veya yana git §7(G = durdur)");
            });
        });
        scanner.setDaemon(true);
        scanner.start();
    }

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

    private void openOrCreateWorld(MinecraftClient client) {
        LevelStorage storage = client.getLevelStorage();

        // Eski bozuk klasörü sil
        try {
            boolean exists = storage.levelExists(WORLD_NAME);
            if (exists) {
                // Eski dünya var — session aç, klasörü bul, kapat, sil
                try (LevelStorage.Session old = storage.createSession(WORLD_NAME)) {
                    Path oldDir = old.getDirectory(WorldSavePath.ROOT).getParent();
                    old.close();
                    deleteDirectory(oldDir.toFile());
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // Temiz session oluştur ve level.dat yaz
        LevelStorage.Session session;
        try {
            session = storage.createSession(WORLD_NAME);
            writeLevelDat(session.getDirectory(WorldSavePath.ROOT).resolve("level.dat"));
            client.execute(() -> localMsg(client, "§7'ChunkCopierExport' hazırlandı."));
        } catch (Exception e) {
            client.execute(() -> localMsg(client, "§cDünya oluşturulamadı: " + e.getMessage()));
            return;
        }

        // start(session) — session'ı IntegratedServerLoader'a devret, o yönetir
        final LevelStorage.Session finalSession = session;
        client.execute(() -> {
            localMsg(client, "§a→ §fChunkCopierExport açılıyor...");
            client.createIntegratedServerLoader().start(finalSession, () -> {});
        });
    }

    private void writeLevelDat(Path path) throws IOException {
        NbtCompound root = new NbtCompound();

        // DataVersion hem root hem Data içinde — Minecraft farklı versiyonlarda farklı yerden okuyabiliyor
        root.putInt("DataVersion", DATA_VERSION);

        NbtCompound data = new NbtCompound();
        data.putInt("DataVersion", DATA_VERSION);
        data.putString("LevelName", WORLD_NAME);
        data.putInt("GameType", 1);
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
        data.putByte("initialized", (byte) 0);

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
                            sw.setBlockState(new BlockPos(originX + relX, y, originZ + relZ),
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
