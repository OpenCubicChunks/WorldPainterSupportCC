package io.github.opencubicchunks.worldpainterplugin;

import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.impl.save.SaveSection2D;
import cubicchunks.regionlib.impl.save.SaveSection3D;
import org.jnbt.CompoundTag;
import org.jnbt.NBTInputStream;
import org.jnbt.NBTOutputStream;
import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.ChunkStore;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class CubicChunkStore implements ChunkStore {
    private final Path path;
    private SaveSection2D section2d;
    private SaveSection3D section3d;
    private int maxHeight;

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
        try {
            return section2d.hasEntry(new EntryLocation2D(x, z));
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public Chunk getChunk(int x, int z) {
        try {
            return section2d.load(new EntryLocation2D(x, z)).map(buf -> {
                try (NBTInputStream in = new NBTInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(buf.array()))))) {
                    Map<Integer, CompoundTag> cubeTags = new HashMap<>();
                    Set<EntryLocation3D> cubes = new HashSet<>();
                    // inefficient but there is no other way
                    section3d.forAllKeys(entry -> {
                        if (entry.getEntryX() != x || entry.getEntryZ() != z) {
                            return;
                        }
                        section3d.load(entry).map(b -> {
                            try (NBTInputStream cubeIn = new NBTInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(b.array()))))) {
                                return new AbstractMap.SimpleEntry<>(entry.getEntryY(), (CompoundTag) cubeIn.readTag());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }).ifPresent(e -> cubeTags.entrySet().add(e));
                    });
                    CompoundTag columnTag = (CompoundTag) in.readTag();

                    return new Chunk16Virtual(new Chunk16Virtual.SerializedColumn(columnTag, cubeTags), x, z, maxHeight);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).orElse(null);
        } catch (IOException e) {
            throw new RuntimeException("I/O error loading chunk", e);
        }
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