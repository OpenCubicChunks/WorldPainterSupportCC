package io.github.opencubicchunks.worldpainterplugin.io;

import cubicchunks.regionlib.impl.EntryLocation2D;
import cubicchunks.regionlib.impl.EntryLocation3D;
import cubicchunks.regionlib.impl.save.SaveSection2D;
import cubicchunks.regionlib.impl.save.SaveSection3D;
import org.jnbt.CompoundTag;
import org.jnbt.NBTInputStream;
import org.jnbt.NBTOutputStream;
import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.ChunkStore;
import io.github.opencubicchunks.worldpainterplugin.Chunk16Virtual;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class CubicChunkStore implements ChunkStore {
    private final Path path;
    private final IONbtReader reader = new IONbtReader();
    private final IONbtWriter writer = new IONbtWriter();
    private SaveSection2D section2d;
    private SaveSection3D section3d;

    public CubicChunkStore(File worldDir, int dimension) throws IOException {
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
        Map<Integer, Chunk16Virtual.Section> sections = ((Chunk16Virtual) chunk).getSections();
        Map<Integer, CompoundTag> cubeTag = new HashMap<>();
        for (Map.Entry<Integer, Chunk16Virtual.Section> e : sections.entrySet()) {
            cubeTag.put(e.getKey(), writer.write((Chunk16Virtual) chunk, e.getValue()));
        }
        int x = chunk.getxPos();
        int z = chunk.getzPos();
        for (Map.Entry<Integer, CompoundTag> data : cubeTag.entrySet()) {
            int y = data.getKey();
            ByteBuffer buffer;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (NBTOutputStream out = new NBTOutputStream(new BufferedOutputStream(new GZIPOutputStream(baos)))) {
                out.writeTag(data.getValue());
            } catch (IOException e) {
                throw new RuntimeException("I/O error saving chunk", e);
            }
            try {
                buffer = ByteBuffer.wrap(baos.toByteArray());
                section3d.save(new EntryLocation3D(x, y, z), buffer);
            } catch (IOException e) {
                throw new RuntimeException("I/O error saving chunk", e);
            }
        }
        ByteBuffer buffer;
        CompoundTag columnTag = writer.write((Chunk16Virtual) chunk);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (NBTOutputStream out = new NBTOutputStream(new BufferedOutputStream(new GZIPOutputStream(baos)))) {
            out.writeTag(columnTag);
        } catch (IOException e) {
            throw new RuntimeException("I/O error saving chunk", e);
        }
        try {
            buffer = ByteBuffer.wrap(baos.toByteArray());
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
                    CompoundTag tag = (CompoundTag) in.readTag();
                    Chunk16Virtual chunk = reader.readColumn(x, z, tag);

                    Set<EntryLocation3D> cubes = new HashSet<>();
                    // inefficient but there is no other way
                    section3d.forAllKeys(entry -> {
                        if (entry.getEntryX() == x && entry.getEntryZ() == z) {
                            cubes.add(entry);
                        }
                    });

                    for (EntryLocation3D loc : cubes) {
                        section3d.load(loc).ifPresent(b -> {
                            try (NBTInputStream cubeIn = new NBTInputStream(new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(b.array()))))) {
                                CompoundTag cubeTag = (CompoundTag) cubeIn.readTag();
                                reader.readCube(chunk, x, loc.getEntryY(), z, cubeTag);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
                    }

                    return chunk;
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