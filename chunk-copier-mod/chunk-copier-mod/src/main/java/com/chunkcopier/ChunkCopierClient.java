package com.chunkcopier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.SharedConstants;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ChunkCopierClient implements ClientModInitializer {

    // ─── Durum makinesi ──────────────────────────────────────────────────────
    private enum State { IDLE, RECORDING, PENDING_PASTE }
    private State state = State.IDLE;

    // ─── Kopyalanan bloklar (relX,y,relZ → blockId) ───────────────────────────
    private final Map<String, String> copiedBlocks = new HashMap<>();
    /** Hangi chunklar zaten tarandı — asla iki kez taranmaz. */
    private final Set<Long> scannedChunks = new HashSet<>();
    private int refX = 0, refZ = 0;

    // ─── Tuş & zamanlama ─────────────────────────────────────────────────────
    private static KeyBinding gKey;
    private boolean keyWasDown = false;
    private int scanTick = 0;

    private static final String PENDING_FILE = "chunk_copier_pending.nbt";
    private static final String WORLD_NAME   = "ChunkCopierExport";

    // ─── Başlangıç ───────────────────────────────────────────────────────────
    @Override
    public void onInitializeClient() {
        gKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chunkcopier.g",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.chunkcopier"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // Singleplayer dünyaya girilince bekleyen chunk varsa yapıştır
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            Thread t = new Thread(() -> {
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                client.execute(() -> tryPastePending(client));
            });
            t.setDaemon(true);
            t.start();
        });
    }

    // ─── Tick ────────────────────────────────────────────────────────────────
    private void onTick(MinecraftClient client) {
        boolean isDown = gKey.isPressed();
        if (isDown && !keyWasDown) handleG(client);
        keyWasDown = isDown;

        if (state == State.RECORDING) {
            // Her 40 tick'te (≈2 sn) yeni chunk taraması yap
            // Zaten tarananlar scannedChunks seti sayesinde atlanır
            if (++scanTick >= 40) {
                scanTick = 0;
                scanNearbyChunks(client);
            }
        }
    }

    // ─── G tuşu ──────────────────────────────────────────────────────────────
    private void handleG(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        switch (state) {
            case IDLE -> {
                copiedBlocks.clear();
                scannedChunks.clear();
                scanTick = 0;

                ChunkPos start = new ChunkPos(client.player.getBlockPos());
                refX = start.getStartX();
                refZ = start.getStartZ();

                state = State.RECORDING;
                localMsg(client, "§a● §fKayıt başladı! Gez — etraftaki chunklar kopyalanıyor...");
                localMsg(client, "§7G'ye tekrar bas → durdur ve 'ChunkCopierExport' dünyasına yapıştır.");
                scanNearbyChunks(client); // ilk taramayı hemen yap
            }
            case RECORDING -> {
                state = State.PENDING_PASTE;

                if (copiedBlocks.isEmpty()) {
                    localMsg(client, "§cHiç blok bulunamadı — tekrar dene.");
                    state = State.IDLE;
                    return;
                }

                localMsg(client, "§a✔ §f" + copiedBlocks.size() + " blok, " + scannedChunks.size()
                        + " chunk kopyalandı. Dünya oluşturuluyor/açılıyor...");

                savePending(client);

                // Arka planda dünyayı bul/oluştur/aç
                Thread t = new Thread(() -> openOrCreateWorld(client));
                t.setDaemon(true);
                t.start();
            }
            case PENDING_PASTE -> {
                localMsg(client, "§6Zaten bir yapıştırma bekliyor! Dünyaya gir.");
            }
        }
    }

    // ─── Yakın chunkları tara (sadece yeni olanları) ─────────────────────────
    private void scanNearbyChunks(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        int viewDist = client.options.getViewDistance().getValue();
        ChunkPos center = new ChunkPos(client.player.getBlockPos());
        int minY = client.world.getBottomY();
        int maxY = client.world.getTopY();
        int added = 0;

        for (int cx = center.x - viewDist; cx <= center.x + viewDist; cx++) {
            for (int cz = center.z - viewDist; cz <= center.z + viewDist; cz++) {
                long key = ChunkPos.toLong(cx, cz);

                // Bu chunk daha önce tarandıysa kesinlikle atla
                if (scannedChunks.contains(key)) continue;

                WorldChunk chunk = client.world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk == null) continue; // yüklü değil, sonra taranacak

                // Taranan olarak işaretle — bir daha girilmeyecek
                scannedChunks.add(key);

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        int wx = cx * 16 + lx;
                        int wz = cz * 16 + lz;
                        for (int y = minY; y < maxY; y++) {
                            var bs = chunk.getBlockState(new BlockPos(wx, y, wz));
                            if (!bs.isAir()) {
                                String bkey  = (wx - refX) + "," + y + "," + (wz - refZ);
                                String bname = Registries.BLOCK.getId(bs.getBlock()).toString();
                                copiedBlocks.put(bkey, bname);
                                added++;
                            }
                        }
                    }
                }
            }
        }

        if (added > 0) {
            localMsg(client, "§7+" + added + " blok eklendi — toplam §f" + copiedBlocks.size()
                    + "§7 blok / §f" + scannedChunks.size() + "§7 chunk");
        }
    }

    // ─── NBT dosyasına kaydet ─────────────────────────────────────────────────
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
            localMsg(client, "§cDosya kaydedilemedi: " + e.getMessage());
        }
    }

    // ─── Dünyayı bul / oluştur / aç ──────────────────────────────────────────
    private void openOrCreateWorld(MinecraftClient client) {
        LevelStorage storage = client.getLevelStorage();

        // 1. Dünya yoksa oluştur
        boolean exists = false;
        try { exists = storage.levelExists(WORLD_NAME); } catch (Exception ignored) {}

        if (!exists) {
            try (LevelStorage.Session session = storage.createSession(WORLD_NAME)) {
                writeFlatLevelDat(session);
                client.execute(() -> localMsg(client, "§7'ChunkCopierExport' dünyası oluşturuldu."));
            } catch (Exception e) {
                client.execute(() -> localMsg(client, "§cDünya oluşturulamadı: " + e.getMessage()));
                return;
            }
        }

        // 2. Özet listesinde bul ve yükle
        try {
            var summaries = storage.loadSummaries(storage.getLevelList()).get();
            for (var s : summaries) {
                if (s.getLevelId().equals(WORLD_NAME)) {
                    var summary = s;
                    client.execute(() -> {
                        localMsg(client, "§a→ §fChunkCopierExport açılıyor...");
                        client.createIntegratedServerLoader().start(null, summary);
                    });
                    return;
                }
            }
            client.execute(() -> localMsg(client, "§cDünya özeti bulunamadı — beklenmedik hata."));
        } catch (Exception e) {
            client.execute(() -> localMsg(client, "§cDünya yüklenemedi: " + e.getMessage()));
        }
    }

    /**
     * Düz (flat) bir dünya için minimal level.dat yazar.
     * Creative mod, Peaceful, hile açık.
     */
    private void writeFlatLevelDat(LevelStorage.Session session) throws IOException {
        int dataVersion = SharedConstants.getGameVersion().getSaveVersion().getId();

        NbtCompound root = new NbtCompound();
        NbtCompound d    = new NbtCompound();

        d.putInt("DataVersion", dataVersion);
        d.putString("LevelName", WORLD_NAME);
        d.putInt("GameType", 1);          // 1 = Creative
        d.putByte("Difficulty", (byte)1); // 1 = Easy
        d.putByte("allowCommands", (byte)1);
        d.putByte("hardcore", (byte)0);
        d.putLong("RandomSeed", 0L);
        d.putLong("LastPlayed", System.currentTimeMillis());
        d.putInt("SpawnX", 0);
        d.putInt("SpawnY", 65);
        d.putInt("SpawnZ", 0);
        d.putFloat("SpawnAngle", 0f);
        d.putLong("Time", 0L);
        d.putLong("DayTime", 6000L);
        d.putByte("initialized", (byte)0);

        // Versiyon
        NbtCompound ver = new NbtCompound();
        ver.putInt("Id", dataVersion);
        ver.putString("Name", SharedConstants.getGameVersion().getName());
        ver.putString("Series", "main");
        ver.putByte("Snapshot", SharedConstants.getGameVersion().isStable() ? (byte)0 : (byte)1);
        d.put("Version", ver);

        // Oyun kuralları — mob spawn kapalı, temiz bir ortam
        NbtCompound gr = new NbtCompound();
        gr.putString("doMobSpawning", "false");
        gr.putString("doDaylightCycle", "false");
        gr.putString("doWeatherCycle", "false");
        gr.putString("keepInventory", "true");
        d.put("GameRules", gr);

        // DataPacks
        NbtCompound dp  = new NbtCompound();
        NbtList dpOn = new NbtList();
        dpOn.add(NbtString.of("vanilla"));
        dp.put("Enabled", dpOn);
        dp.put("Disabled", new NbtList());
        d.put("DataPacks", dp);

        // WorldGenSettings → düz dünya jeneratörü
        NbtCompound wgs  = new NbtCompound();
        wgs.putLong("seed", 0L);
        wgs.putByte("bonus_chest", (byte)0);

        NbtCompound dims = new NbtCompound();

        // Overworld = flat
        NbtCompound ow = new NbtCompound();
        ow.putString("type", "minecraft:overworld");
        NbtCompound owGen = new NbtCompound();
        owGen.putString("type", "minecraft:flat");
        NbtCompound flatCfg = new NbtCompound();
        flatCfg.putString("biome", "minecraft:plains");
        flatCfg.putByte("features", (byte)0);
        flatCfg.putByte("lakes", (byte)0);
        NbtList flatLayers = new NbtList();
        flatLayers.add(layer("minecraft:bedrock", 1));
        flatLayers.add(layer("minecraft:dirt", 2));
        flatLayers.add(layer("minecraft:grass_block", 1));
        flatCfg.put("layers", flatLayers);
        owGen.put("settings", flatCfg);
        ow.put("generator", owGen);
        dims.put("minecraft:overworld", ow);

        // Nether = noise (varsayılan)
        NbtCompound neth = new NbtCompound();
        neth.putString("type", "minecraft:the_nether");
        NbtCompound nethGen = new NbtCompound();
        nethGen.putString("type", "minecraft:noise");
        nethGen.putString("settings", "minecraft:nether");
        NbtCompound nethBiome = new NbtCompound();
        nethBiome.putString("type", "minecraft:multi_noise");
        nethBiome.putString("preset", "minecraft:nether");
        nethGen.put("biome_source", nethBiome);
        neth.put("generator", nethGen);
        dims.put("minecraft:the_nether", neth);

        // End = noise (varsayılan)
        NbtCompound end = new NbtCompound();
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
        d.put("WorldGenSettings", wgs);

        root.put("Data", d);

        // Yaz (GZIP sıkıştırmalı — Minecraft level.dat formatı)
        Path levelDat = session.getDirectory(WorldSavePath.ROOT).resolve("level.dat");
        NbtIo.writeCompressed(root, levelDat);
    }

    /** Düz dünya katmanı için yardımcı */
    private static NbtCompound layer(String block, int height) {
        NbtCompound c = new NbtCompound();
        c.putString("block", block);
        c.putInt("height", height);
        return c;
    }

    // ─── Singleplayer dünyaya girilince yapıştır ──────────────────────────────
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

        int pasted = 0;
        for (String key : blocks.getKeys()) {
            String[] p = key.split(",");
            int relX = Integer.parseInt(p[0]);
            int y    = Integer.parseInt(p[1]);
            int relZ = Integer.parseInt(p[2]);

            Identifier id = Identifier.of(blocks.getString(key));
            if (Registries.BLOCK.containsId(id)) {
                Block block = Registries.BLOCK.get(id);
                sw.setBlockState(new BlockPos(originX + relX, y, originZ + relZ),
                        block.getDefaultState(), 3);
                pasted++;
            }
        }

        saveFile.delete();
        state = State.IDLE;
        localMsg(client, "§a✔ §fTamamlandı! §e" + pasted + " §fblok yapıştırıldı.");
    }

    // ─── Yardımcılar ─────────────────────────────────────────────────────────
    private Path getPendingPath(MinecraftClient client) {
        return client.runDirectory.toPath().resolve(PENDING_FILE);
    }

    private void localMsg(MinecraftClient client, String msg) {
        if (client.player != null) client.player.sendMessage(Text.literal(msg));
    }
}
