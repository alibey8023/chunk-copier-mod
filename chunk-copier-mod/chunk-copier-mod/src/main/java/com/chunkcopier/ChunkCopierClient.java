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

    // MC 1.21.4 data version — hardcoded because SharedConstants returns 0 from background thread
    private static final int DATA_VERSION = 4189;
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

        if (state == State.RECORDING) {
            if (++scanTick >= 60) { // her 3 saniyede bir — FPS için daha az sıklıkta
                scanTick = 0;
                scheduleScan(client);
            }
        }
    }

    private void handleG(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        switch (state) {
            case IDLE -> {
                copiedBlocks.clear();
                scannedChunks.clear();
                scanTick = 60; // ilk basışta hemen tara

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

                Thread t = new Thread(() -> {
                    savePending(client);
                    openOrCreateWorld(client);
                });
                t.setDaemon(true);
                t.start();
            }
            case PENDING_PASTE -> localMsg(client, "§6Zaten bekleyen bir yapıştırma var. Dünyaya gir.");
        }
    }

    // Arka planda chunk tara — ana thread'e dokunmaz, FPS korunur
    private void scheduleScan(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        int viewDist = Math.min(client.options.getViewDistance().getValue(), 10);
        ChunkPos center = new ChunkPos(client.player.getBlockPos());
        int minY = client.world.getBottomY();
        int maxY = client.world.getBottomY() + client.world.getHeight();

        // Önce chunk referanslarını ana thread'de topla
        List<WorldChunk> toScan = new ArrayList<>();
        for (int cx = center.x - viewDist; cx <= center.x + viewDist; cx++) {
            for (int cz = center.z - viewDist; cz <= center.z + viewDist; cz++) {
                long key = ChunkPos.toLong(cx, cz);
                if (scannedChunks.contains(key)) continue;
                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue;
                scannedChunks.add(key); // hemen işaretle
                toScan.add(chunk);
            }
        }

        if (toScan.isEmpty()) return;

        // Blok iterasyonunu arka plan thread'ine taşı
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

            // Sonuçları ana thread'e bildir
            client.execute(() -> {
                copiedBlocks.putAll(batch);

                // Yön mesajı — oyuncuya nereye gitmesi gerektiğini söyle
                int scanned = scannedChunks.size();
                String dir = directionHint(client, center);
                localMsg(client, "§7[Chunk Copier] §f" + copiedBlocks.size() + " blok / "
                        + scanned + " chunk" + dir);
            });
        });
        scanner.setDaemon(true);
        scanner.start();
    }

    // Oyuncunun hangi yönde gitmesi gerektiğine dair ipucu
    private String directionHint(MinecraftClient client, ChunkPos center) {
        if (client.player == null) return "";
        float yaw = client.player.getYaw() % 360;
        if (yaw < 0) yaw += 360;

        // Yön önerileri
        String goDir;
        if      (yaw <  45 || yaw >= 315) goDir = "güney";
        else if (yaw <  90)               goDir = "güneybatı";
        else if (yaw < 135)               goDir = "batı";
        else if (yaw < 180)               goDir = "kuzeybatı";
        else if (yaw < 225)               goDir = "kuzey";
        else if (yaw < 270)               goDir = "kuzeydoğu";
        else if (yaw < 315)               goDir = "doğu";
        else                              goDir = "güneydoğu";

        return " — devam et ya da §e" + goDir + "§7ya git";
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

        boolean exists = false;
        try { exists = storage.levelExists(WORLD_NAME); } catch (Exception ignored) {}

        if (!exists) {
            try (LevelStorage.Session session = storage.createSession(WORLD_NAME)) {
                writeFlatLevelDat(session);
                client.execute(() -> localMsg(client, "§7'ChunkCopierExport' oluşturuldu."));
            } catch (Exception e) {
                client.execute(() -> localMsg(client, "§cDünya oluşturulamadı: " + e.getMessage()));
                return;
            }
        }

        client.execute(() -> {
            localMsg(client, "§a→ §fChunkCopierExport açılıyor...");
            client.createIntegratedServerLoader().start(WORLD_NAME, () -> {});
        });
    }

    private void writeFlatLevelDat(LevelStorage.Session session) throws IOException {
        NbtCompound root = new NbtCompound();
        NbtCompound d    = new NbtCompound();

        // DATA_VERSION sabit: 4189 = Minecraft 1.21.4
        d.putInt("DataVersion", DATA_VERSION);
        d.putString("LevelName", WORLD_NAME);
        d.putInt("GameType", 1);           // Yaratıcı
        d.putByte("Difficulty", (byte) 1); // Kolay
        d.putByte("allowCommands", (byte) 1);
        d.putByte("hardcore", (byte) 0);
        d.putLong("RandomSeed", 0L);
        d.putLong("LastPlayed", System.currentTimeMillis());
        d.putInt("SpawnX", 0);
        d.putInt("SpawnY", 65);
        d.putInt("SpawnZ", 0);
        d.putFloat("SpawnAngle", 0f);
        d.putLong("Time", 0L);
        d.putLong("DayTime", 6000L);
        d.putByte("initialized", (byte) 0);

        NbtCompound ver = new NbtCompound();
        ver.putInt("Id", DATA_VERSION);
        ver.putString("Name", "1.21.4");
        ver.putString("Series", "main");
        ver.putByte("Snapshot", (byte) 0);
        d.put("Version", ver);

        NbtCompound gr = new NbtCompound();
        gr.putString("doMobSpawning", "false");
        gr.putString("doDaylightCycle", "false");
        gr.putString("doWeatherCycle", "false");
        gr.putString("keepInventory", "true");
        d.put("GameRules", gr);

        NbtCompound dp = new NbtCompound();
        NbtList dpOn   = new NbtList();
        dpOn.add(NbtString.of("vanilla"));
        dp.put("Enabled", dpOn);
        dp.put("Disabled", new NbtList());
        d.put("DataPacks", dp);

        // Dünya oluşturucu: düz dünya
        NbtCompound wgs  = new NbtCompound();
        wgs.putLong("seed", 0L);
        wgs.putByte("bonus_chest", (byte) 0);
        NbtCompound dims = new NbtCompound();

        NbtCompound ow    = new NbtCompound();
        ow.putString("type", "minecraft:overworld");
        NbtCompound owGen = new NbtCompound();
        owGen.putString("type", "minecraft:flat");
        NbtCompound flatCfg = new NbtCompound();
        flatCfg.putString("biome", "minecraft:plains");
        flatCfg.putByte("features", (byte) 0);
        flatCfg.putByte("lakes", (byte) 0);
        NbtList layers = new NbtList();
        layers.add(layer("minecraft:bedrock", 1));
        layers.add(layer("minecraft:dirt", 2));
        layers.add(layer("minecraft:grass_block", 1));
        flatCfg.put("layers", layers);
        owGen.put("settings", flatCfg);
        ow.put("generator", owGen);
        dims.put("minecraft:overworld", ow);
        wgs.put("dimensions", dims);
        d.put("WorldGenSettings", wgs);

        root.put("Data", d);
        NbtIo.writeCompressed(root, session.getDirectory(WorldSavePath.ROOT).resolve("level.dat"));
    }

    private static NbtCompound layer(String block, int height) {
        NbtCompound c = new NbtCompound();
        c.putString("block", block);
        c.putInt("height", height);
        return c;
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
            List<Map.Entry<String, String>> entries = new ArrayList<>(blocks.getKeys().stream()
                    .map(k -> Map.entry(k, blocks.getString(k))).toList());

            // 500'er blok partiler halinde ana thread'de yapıştır
            int batchSize = 500;
            for (int i = 0; i < entries.size(); i += batchSize) {
                int from = i;
                int to   = Math.min(i + batchSize, entries.size());
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
