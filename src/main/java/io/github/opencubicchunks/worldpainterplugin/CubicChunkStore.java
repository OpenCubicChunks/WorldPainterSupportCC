package io.github.opencubicchunks.worldpainterplugin;

import cubicchunks.regionlib.api.region.key.IKey;
import cubicchunks.regionlib.api.region.key.IKeyProvider;
import cubicchunks.regionlib.api.region.key.RegionKey;
import cubicchunks.regionlib.api.storage.SaveSection;
import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.impl.save.SaveSection2D;
import cubicchunks.regionlib.impl.save.SaveSection3D;
import cubicchunks.regionlib.lib.ExtRegion;
import cubicchunks.regionlib.lib.factory.SimpleRegionFactory;
import cubicchunks.regionlib.lib.provider.SharedCachedRegionProvider;
import org.jnbt.ByteTag;
import org.jnbt.CompoundTag;
import org.jnbt.NBTInputStream;
import org.jnbt.NBTOutputStream;
import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.ChunkStore;
import org.pepsoft.minecraft.MinecraftCoords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class CubicChunkStore implements ChunkStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("CubicChunkStore");
    // Workaround for WP re-creating a chunk store for every region
    // note: we don't care about race conditions on this field, they shouldn't cause any issues,
    // beyond possibly keeping the wrong value in cache, which is not an issue
    private static volatile ChunkListHolder LAST_CHUNK_LIST;
    private final Path path;
    private SaveSection2D section2d;
    private SaveSection3D section3d;
    private final int minHeight, maxHeight;
    private ChunkListHolder chunks;

    public CubicChunkStore(File worldDir, int dimension, int minHeight, int maxHeight) throws IOException {
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        Path path = worldDir.toPath();
        if (dimension != 0) {
            path = path.resolve("DIM" + dimension);
        }
        this.path = path;
        init();
    }

    private void init() throws IOException {
        Files.createDirectories(path);
        Path part2d = path.resolve("region2d");
        Files.createDirectories(part2d);
        Path part3d = path.resolve("region3d");
        Files.createDirectories(part3d);
        section2d = SaveSection2D.createAt(part2d);
        section3d = new SaveSection3D(
                new SharedCachedRegionProvider<>(
                        SimpleRegionFactory.createDefault(new CubePosProvider(), part3d, 512)
                ),
                new SharedCachedRegionProvider<>(
                        new SimpleRegionFactory<>(new CubePosProvider(), part3d,
                                (keyProvider, regionKey) -> new ExtRegion<>(part3d, Collections.emptyList(), keyProvider, regionKey),
                                (keyProvider, regionKey) -> Files.exists(part3d.resolve(regionKey.getName() + ".ext"))
                        )
                ));
        ChunkListHolder lastChunkList = LAST_CHUNK_LIST;
        if (lastChunkList != null && lastChunkList.path.equals(path)) {
            LOGGER.info("Using cached chunk map for path " + path);
            chunks = lastChunkList;
        } else {
            LOGGER.info("No cached chunk map for this world, new chunk map will be loaded for " + path);
            chunks = null;
        }
    }

    private void chunksLazyInit() {
        if (chunks == null) {
            try {
                Map<MinecraftCoords, ArrayList<Integer>> map = new ConcurrentHashMap<>(8192);
                List<MinecraftCoords> chunkOrder = new ArrayList<>();
                section3d.forAllKeys(p -> {
                    MinecraftCoords coords = new MinecraftCoords(p.getEntryX(), p.getEntryZ());
                    boolean alreadyExists = map.containsKey(coords);
                    map.computeIfAbsent(coords, k -> new ArrayList<>()).add(p.getEntryY());
                    if (!alreadyExists) {
                        chunkOrder.add(coords);
                    }
                });
                this.chunks = new ChunkListHolder(map, chunkOrder, path);
                LAST_CHUNK_LIST = this.chunks;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private synchronized Map<MinecraftCoords, ArrayList<Integer>> getChunks() {
        chunksLazyInit();
        return chunks.map;
    }

    private synchronized List<MinecraftCoords> getChunkOrder() {
        chunksLazyInit();
        return chunks.chunkOrder;
    }

    @Override
    public int getChunkCount() {
        return getChunkCoords().size();
    }

    @Override
    public Set<MinecraftCoords> getChunkCoords() {
        return getChunks().keySet();
    }

    @Override
    public boolean visitChunks(ChunkVisitor chunkVisitor) {
        return visitChunks(chunkVisitor, EditMode.READONLY);
    }

    @Override
    public boolean visitChunksForEditing(ChunkVisitor chunkVisitor) {
        return visitChunks(chunkVisitor, EditMode.EDITABLE);
    }

    private boolean visitChunks(ChunkVisitor chunkVisitor, EditMode editMode) {
        for (MinecraftCoords pos : getChunkOrder()) {
            Chunk16Virtual chunk = loadChunk(pos.x, pos.z, editMode);
            if (chunk != null) {
                try {
                    if (!chunkVisitor.visitChunk(chunk)) {
                        return false;
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return true;
    }

    @Override
    public void saveChunk(Chunk chunk) {
        Chunk16Virtual.SerializedColumn serialized = ((Chunk16Virtual) chunk).serialize();
        int x = chunk.getxPos();
        int z = chunk.getzPos();
        for (Map.Entry<Integer, CompoundTag> data : serialized.cubeTags.entrySet()) {
            int y = data.getKey();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (NBTOutputStream out = new NBTOutputStream(new BufferedOutputStream(new GZIPOutputStream(baos)))) {
                out.writeTag(data.getValue());
            } catch (IOException e) {
                throw new RuntimeException("I/O error saving chunk", e);
            }
            try {
                ByteBuffer buffer = ByteBuffer.wrap(baos.toByteArray());
                section3d.save(new EntryLocation3D(x, y, z), buffer);
            } catch (IOException e) {
                throw new RuntimeException("I/O error saving chunk", e);
            }
            // TODO: is this thread safe?
            synchronized (this) {
                getChunks().computeIfAbsent(new MinecraftCoords(x, z), p -> new ArrayList<>()).add(y);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (NBTOutputStream out = new NBTOutputStream(new BufferedOutputStream(new GZIPOutputStream(baos)))) {
            out.writeTag(serialized.columnTag);
        } catch (IOException e) {
            throw new RuntimeException("I/O error saving chunk", e);
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(baos.toByteArray());
            section2d.save(new EntryLocation2D(x, z), buffer);
        } catch (IOException e) {
            throw new RuntimeException("I/O error saving chunk", e);
        }
    }

    @Override
    public void doInTransaction(Runnable task) {
        task.run();
    }

    @Override
    public void flush() {
        close();
        try {
            init();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isChunkPresent(int x, int z) {
        return getChunks().containsKey(new MinecraftCoords(x, z));
    }

    @Override
    public Chunk getChunk(int x, int z) {
        return loadChunk(x, z, EditMode.EDITABLE);
    }

    public Chunk16Virtual loadChunk(int x, int z, EditMode editMode) {
        Map<Integer, CompoundTag> cubeTags = Optional.ofNullable(getChunks().get(new MinecraftCoords(x, z))).map(list ->
                list.stream()
                        .map(y -> new EntryLocation3D(x, y, z))
                        .map(pos -> new SimpleEntry<>(pos.getEntryY(), load(section3d, pos)))
                        .filter(e -> e.getValue().isPresent())
                        .map(e -> new SimpleEntry<>(e.getKey(), e.getValue().get()))
                        .map(e -> new SimpleEntry<>(e.getKey(), readNbt(e.getValue())))
                        .collect(Collectors.toMap(SimpleEntry::getKey, SimpleEntry::getValue))
        ).orElse(new HashMap<>());

        if (cubeTags.isEmpty()) {
            return null;
        }
        CompoundTag columnTag = load(section2d, new EntryLocation2D(x, z))
                .map(this::readNbt)
                .orElseGet(() -> makeFakeColumnNBT(cubeTags.values().iterator().next()));


        return new Chunk16Virtual(new Chunk16Virtual.SerializedColumn(columnTag, cubeTags), x, z, minHeight, maxHeight, editMode);
    }

    private <T extends IKey<T>> Optional<ByteBuffer> load(SaveSection<?, T> save, T loc) {
        try {
            return save.load(loc, true); // TODO: regionlib bug
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CompoundTag readNbt(ByteBuffer buf) {
        try (NBTInputStream in = new NBTInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(buf.array()))))) {
            return (CompoundTag) in.readTag();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CompoundTag makeFakeColumnNBT(CompoundTag tag) {
        CompoundTag srcLevel = (CompoundTag) tag.getTag("Level");
        CompoundTag level = new CompoundTag("Level", new HashMap<>());
        level.setTag("v", new ByteTag("v", (byte) 1));
        level.setTag("x", srcLevel.getTag("x"));
        level.setTag("z", srcLevel.getTag("z"));

        CompoundTag out = new CompoundTag(tag.getName(), new HashMap<>());
        out.setTag("Level", level);
        return out;
    }

    @Override
    public Chunk getChunkForEditing(int x, int z) {
        return getChunk(x, z);
    }

    @Override
    public void close() {
        try {
            section2d.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            section3d.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ChunkListHolder {
        Map<MinecraftCoords, ArrayList<Integer>> map;
        List<MinecraftCoords> chunkOrder;

        Path path;

        public ChunkListHolder(Map<MinecraftCoords, ArrayList<Integer>> chunks, List<MinecraftCoords> chunkOrder, Path path) {
            this.map = chunks;
            this.chunkOrder = chunkOrder;
            this.path = path;
        }
    }


    public static class CubePosProvider implements IKeyProvider<EntryLocation3D> {
        private static final int LOC_BITS = 4;
        private static final int LOC_BITMASK = (1 << LOC_BITS) - 1;
        public static final int ENTRIES_PER_REGION = (1 << LOC_BITS) * (1 << LOC_BITS) * (1 << LOC_BITS);

        @Override
        public EntryLocation3D fromRegionAndId(RegionKey regionKey, int id) throws IllegalArgumentException {

            int[] pos = new int[3];
            String s = regionKey.getName();

            int len = s.length();

            int i = 0;
            for (int part = 0; part < 3; part++) {
                if (i >= len) {
                    throw new IllegalArgumentException("Invalid name " + regionKey.getName());
                }

                int numberStartIdx = i;

                // optional '-'
                if (s.charAt(i) == '-') {
                    i++;
                }

                // at least one digit
                int start = i;
                while (i < len && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                if (i == start) {
                    throw new IllegalArgumentException("Invalid name " + regionKey.getName());
                }

                int numberEndIdx = i;
                pos[part] = Integer.parseInt(s, numberStartIdx, numberEndIdx, 10);

                // dot separator (except last number)
                if (part < 2) {
                    if (i >= len || s.charAt(i) != '.') {
                        throw new IllegalArgumentException("Invalid name " + regionKey.getName());
                    }
                    i++;
                }
            }

            // after third number we should be at ".3dr"
            if (!s.startsWith(".3dr", i)) {
                throw new IllegalArgumentException("Invalid name " + regionKey.getName());
            }

            int relativeX = id >>> LOC_BITS * 2;
            int relativeY = (id >>> LOC_BITS) & LOC_BITMASK;
            int relativeZ = id & LOC_BITMASK;
            return new EntryLocation3D(
                    pos[0] << LOC_BITS | relativeX,
                    pos[1] << LOC_BITS | relativeY,
                    pos[2] << LOC_BITS | relativeZ);
        }

        @Override
        public int getKeyCount(RegionKey key) {
            return ENTRIES_PER_REGION;
        }

        @Override
        public boolean isValid(RegionKey key) {
            String s = key.getName();

            int len = s.length();

            int i = 0;
            for (int part = 0; part < 3; part++) {
                if (i >= len) {
                    return false;
                }

                // optional '-'
                if (s.charAt(i) == '-') {
                    i++;
                }

                // at least one digit
                int start = i;
                while (i < len && Character.isDigit(s.charAt(i))) {
                    i++;
                }
                if (i == start) {
                    return false;
                }

                // dot separator (except last number)
                if (part < 2) {
                    if (i >= len || s.charAt(i) != '.') {
                        return false;
                    }
                    i++;
                }
            }

            // after third number we should be at ".3dr"
            return s.startsWith(".3dr", i);
        }
    }
}