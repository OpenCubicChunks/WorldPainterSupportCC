package io.github.opencubicchunks.worldpainterplugin;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.procedures.IntProcedure;
import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.impl.save.SaveSection2D;
import cubicchunks.regionlib.impl.save.SaveSection3D;
import org.jnbt.ByteTag;
import org.jnbt.CompoundTag;
import org.jnbt.NBTInputStream;
import org.jnbt.NBTOutputStream;
import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.ChunkStore;
import org.pepsoft.minecraft.MinecraftCoords;

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
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class CubicChunkStore implements ChunkStore {
    private final Path path;
    private SaveSection2D section2d;
    private SaveSection3D section3d;
    private int maxHeight;

    private volatile ConcurrentHashMap<MinecraftCoords, IntSet> chunks;

    public CubicChunkStore(File worldDir, int dimension, int maxHeight) throws IOException {
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
        section3d = SaveSection3D.createAt(part3d);
        chunks = null;
    }

    private synchronized Map<MinecraftCoords, IntSet> getChunks() {
        if (chunks == null) {
            try {
                chunks = computeChunks();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return chunks;
    }

    private ConcurrentHashMap<MinecraftCoords, IntSet> computeChunks() throws IOException {
        ConcurrentHashMap<MinecraftCoords, IntSet> map = new ConcurrentHashMap<>(8192);
        section3d.forAllKeys(p -> map
            .computeIfAbsent(new MinecraftCoords(p.getEntryX(), p.getEntryZ()), k -> new IntHashSet())
            .add(p.getEntryY())
        );
        return map;
    }

    @Override public int getChunkCount() {
        return getChunkCoords().size();
    }

    @Override public Set<MinecraftCoords> getChunkCoords() {
        return getChunks().keySet();
    }

    @Override public boolean visitChunks(ChunkVisitor chunkVisitor) {
        return visitChunks(chunkVisitor, EditMode.READONLY);
    }

    @Override public boolean visitChunksForEditing(ChunkVisitor chunkVisitor) {
        return visitChunks(chunkVisitor, EditMode.EDITABLE);
    }

    private boolean visitChunks(ChunkVisitor chunkVisitor, EditMode editMode) {
        for (MinecraftCoords pos : getChunkCoords()) {
            if (!chunkVisitor.visitChunk(loadChunk(pos.x, pos.z, editMode))) {
                return false;
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
            getChunks().computeIfAbsent(new MinecraftCoords(x, z), p -> new IntHashSet()).add(y);
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
        try {
            close();
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
        try {
            CompoundTag columnTag = section2d.load(new EntryLocation2D(x, z)).map(buf -> {
                try (NBTInputStream in = new NBTInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(buf.array()))))) {
                    return (CompoundTag) in.readTag();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }).orElse(null);

            Map<Integer, CompoundTag> cubeTags = new HashMap<>();

            getChunks().get(new MinecraftCoords(x, z)).forEach((IntProcedure) y-> {
                try {
                    section3d.load(new EntryLocation3D(x, y, z)).map(b -> {
                        try (NBTInputStream cubeIn = new NBTInputStream(
                            new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(b.array()))))) {
                            return new AbstractMap.SimpleEntry<>(y, (CompoundTag) cubeIn.readTag());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }).ifPresent(e -> cubeTags.put(e.getKey(), e.getValue()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (cubeTags.isEmpty()) {
                return null;
            }
            if (columnTag == null) {
                columnTag = makeFakeColumnNBT(cubeTags.values().iterator().next());
            }

            return new Chunk16Virtual(new Chunk16Virtual.SerializedColumn(columnTag, cubeTags), x, z, maxHeight, editMode);
        } catch (IOException e) {
            throw new RuntimeException("I/O error loading chunk", e);
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
            section3d.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}