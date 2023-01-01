package io.github.opencubicchunks.worldpainterplugin;

import static java.util.stream.Collectors.toCollection;
import static org.pepsoft.minecraft.Constants.TAG_LEVEL;

import com.carrotsearch.hppc.ObjectIntIdentityHashMap;
import org.jnbt.ByteTag;
import org.jnbt.CompoundTag;
import org.jnbt.DoubleTag;
import org.jnbt.FloatTag;
import org.jnbt.IntArrayTag;
import org.jnbt.IntTag;
import org.jnbt.ListTag;
import org.jnbt.LongTag;
import org.jnbt.ShortTag;
import org.jnbt.Tag;
import org.pepsoft.minecraft.AbstractNBTItem;
import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.Entity;
import org.pepsoft.minecraft.Material;
import org.pepsoft.minecraft.MinecraftCoords;
import org.pepsoft.minecraft.TileEntity;
import org.pepsoft.minecraft.exception.IncompatibleMaterialException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

public class Chunk16Virtual extends AbstractNBTItem implements Chunk {

    private static final boolean DEBUG = System.getProperty("cubicchunks.debug", "false").equalsIgnoreCase("true");
    private static final Logger LOGGER = LoggerFactory.getLogger(Chunk16Virtual.class);

    private int columnX;
    private int columnZ;
    private final CubeMap cubes;

    private int[] yMax = new int[Coords.CUBE_SIZE * Coords.CUBE_SIZE];

    private byte[] biomes = new byte[Coords.CUBE_SIZE * Coords.CUBE_SIZE];

    private final List<Entity> entities = new ArrayList<>();
    private final List<TileEntity> tileEntities = new ArrayList<>();
    private final int maxHeight;

    private final boolean readOnly;
    private boolean forcePopulated;
    private boolean forceLightPopulated;
    private long inhabitedTime;

    public Chunk16Virtual(int columnX, int columnZ, int maxHeight, EditMode editMode) {
        super(new CompoundTag(TAG_LEVEL, new HashMap<>()));
        this.columnX = columnX;
        this.columnZ = columnZ;
        this.cubes = new CubeMap();
        this.maxHeight = maxHeight;
        this.readOnly = editMode == EditMode.READONLY;
    }

    public Chunk16Virtual(SerializedColumn serialized, int columnX, int columnZ, int maxHeight, EditMode editMode) {
        super(serialized.getColumnLevel());
        this.columnX = columnX;
        this.columnZ = columnZ;
        this.cubes = new CubeMap();
        this.maxHeight = maxHeight;
        this.readOnly = editMode == EditMode.READONLY;

        loadColumnData();
        serialized.cubeTags.forEach((cubeY, tag) -> loadCube(new Cube16(this, tag)));
    }

    private void loadCube(Cube16 cube) {
        cubes.put(cube);
    }

    //======================================
    //              NBT IO
    //======================================

    private void loadColumnData() {
        Tag v = super.toNBT().getTag("v");
        int version = (v instanceof IntTag) ? ((IntTag) v).getValue() : ((ByteTag) v).getValue();
        if (version != 1) {
            throw new IllegalArgumentException(String.format("Column has wrong version: %d", version));
        }

        int xCheck = getInt("x");
        int zCheck = getInt("z");
        if (xCheck != getxPos() || zCheck != getzPos()) {
            LOGGER.error("Column or region file is corrupted! Expected ({},{}) but got ({},{}). Column will be relocated to ({}, {})",
                    getxPos(), getzPos(), xCheck, zCheck, xCheck, zCheck);
        }

        this.inhabitedTime = getLong("InhabitedTime");

        // check for these tags because we may be dealing with what is actually cube NBT here in case column doesn't exist
        byte[] biomesArray = getByteArray("Biomes");
        if (biomesArray != null) {
            System.arraycopy(biomesArray, 0, biomes, 0, Coords.CUBE_SIZE * Coords.CUBE_SIZE);
        }

        byte[] opIndexRawData = getByteArray("OpacityIndex");
        if (opIndexRawData != null) {
            ByteArrayInputStream buf = new ByteArrayInputStream(opIndexRawData);
            try (DataInputStream in = new DataInputStream(buf)) {
                for (int i = 0; i < yMax.length; i++) {
                    in.skipBytes(Integer.BYTES);// skip yMin
                    this.yMax[i] = in.readInt() + 1;
                    // skip the opacity index, we don't need it, and we are going to set the isSurfaceTracked
                    // bit in cubes to false to rebuild it
                    int segmentCount = in.readUnsignedShort();
                    in.skipBytes(segmentCount * Integer.BYTES);
                }
            } catch (IOException e) {
                LOGGER.error("Error reading OpacityIndex, data will be removed", e);
            }
        }
    }

    public SerializedColumn serialize() {
        Map<Integer, CompoundTag> tags = new HashMap<>(cubes.array().length * 2);
        for (Cube16 cube : cubes.array()) {
            if (cube != null) {
                tags.put(cube.getY(), cube.toNBT());
            }
        }
        for (int i = 0; i < 16; i++) {
            if (!tags.containsKey(i)) {
                tags.put(i, new Cube16(this, i).toNBT());
            }
        }
        return new SerializedColumn(toNBT(), tags);
    }

    @Override
    public CompoundTag toNBT() {
        setInt("x", getxPos());
        setInt("z", getzPos());
        setInt("v", 1);

        setLong("InhabitedTime", getInhabitedTime());

        if (biomes != null) {
            setByteArray("Biomes", biomes);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        try (DataOutputStream out = new DataOutputStream(baos)) {
            for (int height : yMax) {
                // yMin 0, we can be almost sure about that. TODO: find a way to generate the right value here
                out.writeInt(0);
                out.writeInt(height);
                // segment count 0, let them be regenerated
                out.writeShort(0);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error writing column", e);
        }
        setByteArray("OpacityIndex", baos.toByteArray());

        CompoundTag columnNbt = new CompoundTag("", new HashMap<>());
        columnNbt.setTag("Level", super.toNBT());
        return columnNbt;
    }

    //======================================
    //             Chunk Impl
    //======================================

    private Cube16 getOrMakeSection(int blockY) {
        int cubeY = Coords.blockToCube(blockY);
        Cube16 section = cubes.get(cubeY);
        if (section == null) {
            loadCube(section = new Cube16(this, cubeY));
        }
        return section;
    }

    private int ifSectionExists(int blockY, int def, ToIntFunction<Cube16> cons) {
        int cubeY = Coords.blockToCube(blockY);
        Cube16 section = cubes.get(cubeY);
        if (section == null) {
            return def;
        }
        return cons.applyAsInt(section);
    }

    private <T> T ifSectionExists(int blockY, Supplier<T> defaultValue, Function<Cube16, T> cons) {
        int cubeY = Coords.blockToCube(blockY);
        Cube16 section = cubes.get(cubeY);
        if (section == null) {
            return defaultValue.get();
        }
        return cons.apply(section);
    }

    @Override
    public int getBlockLightLevel(int blockX, int blockY, int blockZ) {
        return ifSectionExists(blockY, 15, section -> section.getBlockLight(blockX, blockY, blockZ));
    }

    @Override
    public void setBlockLightLevel(int blockX, int blockY, int blockZ, int level) {
        if (readOnly) {
            return;
        }
        getOrMakeSection(blockY).setBlockLight(blockX, blockY, blockZ, level);
    }

    @Override
    @Deprecated
    public int getBlockType(int blockX, int blockY, int blockZ) {
        return ifSectionExists(blockY, 0, section -> section.getMaterial(blockX, blockY, blockZ).blockType);
    }

    @Override
    @Deprecated
    public void setBlockType(int blockX, int blockY, int blockZ, int id) {
        if (readOnly) {
            return;
        }
        getOrMakeSection(blockY).setMaterial(blockX, blockY, blockZ, Material.get(id));
    }

    @Override
    @Deprecated
    public int getDataValue(int blockX, int blockY, int blockZ) {
        return ifSectionExists(blockY, 0, section -> section.getMaterial(blockX, blockY, blockZ).data);
    }

    @Override
    @Deprecated
    public void setDataValue(int blockX, int blockY, int blockZ, int val) {
        if (readOnly) {
            return;
        }
        getOrMakeSection(blockY).setMaterial(blockX, blockY, blockZ, Material.get(getBlockType(blockX, blockY, blockZ), val));
    }

    @Override
    public int getHeight(int blockX, int blockZ) {
        return yMax[Coords.index(blockX, blockZ)];
    }

    @Override
    public void setHeight(int blockX, int blockZ, int height) {
        if (readOnly) {
            return;
        }
        yMax[Coords.index(blockX, blockZ)] = height;
    }

    @Override
    public int getSkyLightLevel(int blockX, int blockY, int blockZ) {
        return ifSectionExists(blockY, 15, section -> section.getSkyLight(blockX, blockY, blockZ));
    }

    @Override
    public void setSkyLightLevel(int blockX, int blockY, int blockZ, int val) {
        if (readOnly) {
            return;
        }
        getOrMakeSection(blockY).setSkyLight(blockX, blockY, blockZ, val);
    }

    @Override
    public int getxPos() {
        return this.columnX;
    }

    @Override
    public int getzPos() {
        return this.columnZ;
    }

    @Override
    public MinecraftCoords getCoords() {
        return new MinecraftCoords(getxPos(), getzPos());
    }

    @Override
    public boolean isTerrainPopulated() {
        throw new UnsupportedOperationException("This was never used at the time I was writing this and this can't be implemented in cubic chunks");
    }

    @Override
    public void setTerrainPopulated(boolean terrainPopulated) {
        if (readOnly) {
            return;
        }
        this.forcePopulated = terrainPopulated;
    }

    @Override
    public Material getMaterial(int blockX, int blockY, int blockZ) {
        return ifSectionExists(blockY, () -> Material.AIR, c -> c.getMaterial(blockX, blockY, blockZ));
    }

    @Override
    public void setMaterial(int blockX, int blockY, int blockZ, Material material) {
        if (material.blockType == -1) {
            throw new IncompatibleMaterialException(material);
        }
        if (readOnly) {
            return;
        }
        getOrMakeSection(blockY).setMaterial(blockX, blockY, blockZ, material);
    }

    @Override
    public List<Entity> getEntities() {
        return entities;
    }

    @Override
    public List<TileEntity> getTileEntities() {
        return tileEntities;
    }

    @Override
    public int getMaxHeight() {
        return this.maxHeight;
    }

    @Override
    public boolean isBiomesSupported() {
        return true;
    }

    @Override
    public boolean isBiomesAvailable() {
        return biomes != null;
    }

    @Override
    public boolean is3DBiomesSupported() {
        return false; // NOTE: 3d biomes are non-trivial to support with how CC uses them, as 2d biomes are also required
    }

    /**
     * Indicates whether 3D biomes are available. See {@link #get3DBiome(int, int, int)} and
     * {@link #set3DBiome(int, int, int, int)}.
     *
     * <p>The default implemenation returns {@code false}.
     */
    @Override
    public boolean is3DBiomesAvailable() {
        return false;
    }

    @Override
    public int getBiome(int blockX, int blockZ) {
        return biomes == null ? 0 : biomes[Coords.index(blockX, blockZ)] & 0xFF;
    }

    @Override
    public void setBiome(int blockX, int blockZ, int biome) {
        if (readOnly) {
            return;
        }
        if (biomes == null) {
            biomes = new byte[Coords.CUBE_SIZE * Coords.CUBE_SIZE];
        }
        biomes[Coords.index(blockX, blockZ)] = (byte) biome;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public boolean isLightPopulated() {
        throw new UnsupportedOperationException("This was never used at the time I was writing this and this can't be implemented in cubic chunks");
    }

    @Override
    public void setLightPopulated(boolean lightPopulated) {
        if (readOnly) {
            return;
        }
        this.forceLightPopulated = lightPopulated;
    }

    @Override
    public long getInhabitedTime() {
        return this.inhabitedTime;
    }

    @Override
    public void setInhabitedTime(long inhabitedTime) {
        if (readOnly) {
            return;
        }
        this.inhabitedTime = inhabitedTime;
    }

    @Override
    public int getHighestNonAirBlock(int blockX, int blockZ) {
        int height = getHeight(blockX, blockZ);
        // start with opacity height and try to find anything up
        int maxCube = Coords.blockToCube(height);

        Cube16[] array = cubes.array();
        int minIdx = cubes.indexOfY(maxCube);
        int startIdx = array.length - 1;
        // iterate over all cubes and only check ones that are above and aren't empty
        for (int idx = startIdx; idx >= minIdx; idx--) {
            Cube16 cube = array[idx];
            // this is expected to be the case most of the time
            if (cube == null || cube.isEmpty()) {
                continue;
            }
            // TODO: is it better fo leave this here or return approximation?
            for (int dy = 15; dy >= 0; dy--) {
                if (cube.getMaterial(blockX, dy, blockZ) != Material.AIR) {
                    return Coords.localToBlock(cube.getY(), dy);
                }
            }
        }
        return height;
    }

    @Override
    public int getHighestNonAirBlock() {
        Cube16[] array = cubes.array();
        for (int i = array.length - 1; i >= 0 ; i--) {
            Cube16 cube = array[i];
            if (cube == null) {
                continue;
            }
            if (!cube.isEmpty()) {
                return Coords.cubeToMaxBlock(cube.getY());
            }
        }
        return org.pepsoft.worldpainter.Constants.MIN_HEIGHT;
    }

    public static class Cube16 extends AbstractNBTItem {

        private static final int BLOCK_COUNT = Coords.CUBE_SIZE * Coords.CUBE_SIZE * Coords.CUBE_SIZE;
        // placeholder for writing to disk when original is empty, nibble version
        private static final byte[] PLACEHOLDER_WRITE = new byte[BLOCK_COUNT >> 1];
        // placeholder for writing to disk when original is empty, full byte version, filled with skylight 15
        private static final byte[] PLACEHOLDER_WRITE_SKYLIGHT = new byte[BLOCK_COUNT >> 1];

        static {
            Arrays.fill(PLACEHOLDER_WRITE_SKYLIGHT, (byte) 0xFF);
        }

        private Chunk16Virtual parent;
        private final int yPos;
        private long[] blocks;
        private int bits = 0;
        private final ArrayList<Material> id2material = new ArrayList<>();
        private final ObjectIntIdentityHashMap<Material> material2id = new ObjectIntIdentityHashMap<>();
        private byte[] skyLight;
        private byte[] blockLight;

        // a hack because of this NBT library works
        // it doesn't agree with nesting that doesn't directly correspond to in-memory nesting
        private AbstractNBTItem sectionNbtPlaceholder;

        private static final long serialVersionUID = 1L;

        {
            id2material.add(Material.AIR);
            material2id.put(Material.AIR, 0);
        }

        Cube16(Chunk16Virtual parent, CompoundTag tag) {
            super((CompoundTag) tag.getTag("Level"));
            this.parent = parent;

            int version = getNumber("v").byteValue() & 0xFF;
            if (version != 1) {
                throw new IllegalArgumentException("Cube has wrong version! " + version);
            }
            yPos = getNumber("y").intValue();
            sectionNbtPlaceholder = new PlaceholderNBT(true);

            List<CompoundTag> entityTags = getListSafe("Entities");
            parent.entities.addAll(entityTags.stream().map(Entity::fromNBT).collect(toCollection(ArrayList::new)));

            List<CompoundTag> tileEntityTags = getListSafe("TileEntities");
            parent.tileEntities.addAll(tileEntityTags.stream().map(TileEntity::fromNBT).collect(toCollection(ArrayList::new)));
        }

        @SuppressWarnings("unchecked")
        private <T extends Tag> List<T> getListSafe(String name) {
            Tag tag = getTag(name);
            if (tag instanceof ListTag<?>) {
                return ((ListTag<T>) tag).getValue();
            }
            return new ArrayList<>();
        }

        private Number getNumber(String v) {
            Tag tag = getTag(v);
            if (tag instanceof ByteTag) {
                return ((ByteTag) tag).getValue();
            } else if (tag instanceof ShortTag) {
                return ((ShortTag) tag).getValue();
            } else if (tag instanceof IntTag) {
                return ((IntTag) tag).getValue();
            } else if (tag instanceof LongTag) {
                return ((LongTag) tag).getValue();
            } else if (tag instanceof FloatTag) {
                return ((FloatTag) tag).getValue();
            } else if (tag instanceof DoubleTag) {
                return ((DoubleTag) tag).getValue();
            }
            throw new RuntimeException("Unexpected number tag type " + tag.getClass() + ", value=" + tag);
        }

        private CompoundTag getSectionTag() {
            List<Tag> sectionsTag = getList("Sections");
            if (sectionsTag == null) {
                return new CompoundTag("", new HashMap<>());
            }
            return (CompoundTag) sectionsTag.get(0);
        }

        Cube16(Chunk16Virtual parent, int cubeY) {
            super(new CompoundTag("Level", new HashMap<>()));
            this.parent = parent;
            yPos = cubeY;
            sectionNbtPlaceholder = new PlaceholderNBT(false);
        }

        @Override
        public CompoundTag toNBT() {
            setByte("v", (byte) 1);

            // coords
            setInt("x", parent.getxPos());
            setInt("y", getY());
            setInt("z", parent.getzPos());

            // save the worldgen stage and the target stage
            setBoolean("populated", parent.forcePopulated);
            setBoolean("isSurfaceTracked", false);
            // we can't know that one, but in the worst case, setting it incorrectly will cause cube to be sent to client before it's fully populated
            setBoolean("fullyPopulated", true);

            setBoolean("initLightDone", parent.forceLightPopulated);

            setList("Sections", CompoundTag.class, Collections.singletonList(sectionNbtPlaceholder.toNBT()));

            setList("Entities", CompoundTag.class,
                    parent.entities.stream().filter(this::isInCube).map(Entity::toNBT).collect(Collectors.toList()));
            setList("TileEntities", CompoundTag.class,
                    parent.tileEntities.stream().filter(this::isInCube).map(TileEntity::toNBT).collect(Collectors.toList()));

            setMap("LightingInfo", new HashMap<String, Tag>() {{
                put("LastHeightMap", new IntArrayTag("LastHeightMap", parent.yMax));
                put("EdgeNeedSkyLightUpdate", new ByteTag("EdgeNeedSkyLightUpdate", (byte) 0));
            }});
            CompoundTag cubeNbt = new CompoundTag("", new HashMap<>());
            cubeNbt.setTag("Level", super.toNBT());
            return cubeNbt;
        }

        private boolean isInCube(TileEntity te) {
            return (te.getY() >> 4) == this.yPos;
        }

        private boolean isInCube(Entity e) {
            double[] pos = e.getPos();
            int cubeY = (int) Math.floor(pos[1] / 16.0);
            return cubeY == this.yPos;
        }

        boolean isEmpty() {
            // data must be empty is blocks and add are null
            return blocks == null;
        }

        int getBlockLight(int x, int y, int z) {
            return getDataByte(blockLight, x, y, z, 0);
        }

        int getSkyLight(int x, int y, int z) {
            return getDataByte(skyLight, x, y, z, 15);
        }

        void setBlockLight(int x, int y, int z, int val) {
            blockLight = setDataByte(blockLight, x, y, z, val, 0);
        }

        void setSkyLight(int x, int y, int z, int val) {
            skyLight = setDataByte(skyLight, x, y, z, val, 15);
        }

        void setMaterial(int x, int y, int z, Material mat) {
            setMaterial(Coords.index(x, y, z), mat);
        }

        void setMaterial(int idx, Material mat) {
            int id = material2id.getOrDefault(mat, -1);
            if (id < 0) {
                int newId = id2material.size();
                id2material.add(mat);
                material2id.put(mat, newId);
                if (newId >= (1 << bits)) {
                    resize(Integer.SIZE - Integer.numberOfLeadingZeros(newId));
                }
                id = newId;
            }
            setId(idx, id);
        }

        private void resize(int newBits) {
            int[] ids = new int[4096];
            for (int i = 0; i < 4096; i++) {
                ids[i] = getId(i);
            }
            bits = newBits;
            blocks = new long[bits * (4096 / 64)];
            for (int i = 0; i < 4096; i++) {
                setId(i, ids[i]);
            }
        }

        Material getMaterial(int x, int y, int z) {
            return getMaterial(Coords.index(x, y, z));
        }

        Material getMaterial(int idx) {
            return id2material.get(getId(idx));
        }

        private int getId(int idx) {
            if (blocks == null) {
                return 0;
            }
            final int startBit = idx * bits;
            final int mask = (1 << bits) - 1;
            final int bitOffset = startBit & 63;
            final int arrayIndex = startBit >>> 6;

            if (bitOffset + bits <= 64) {
                // |-----------------------xxxxxxxxxxxxxx---------------------------|
                //  ^------bitOffset------^^----size----^^---(64-bits-bitOffset)---^
                final int offsetFromEnd = 64 - bits - bitOffset;
                long value = blocks[arrayIndex];
                return ((int) (value >>> offsetFromEnd)) & mask;
            } else {
                // split across 2 longs
                long v1 = blocks[arrayIndex];
                long v2 = blocks[arrayIndex + 1];
                //                                                   bitOffset+bits-64
                //                                                     v============v
                // |---------------------------------------xxxxxxxxxxx|xxxxxxxxxxxxxx------------------------------------|
                //  ^--------------bitOffset--------------^^----------bits----------^^-------(128-bitOffset-bits)-------^
                int off1 = bitOffset + bits - 64;
                int off2 = 128 - bitOffset - bits;
                long part1 = v1 << off1;
                long part2 = v2 >>> off2;
                return ((int) (part1 | part2)) & mask;
            }
        }

        private void setId(int idx, int id) {
            if (blocks == null && id == 0) {
                return;
            }
            assert blocks != null;

            int prevIdBefore = 0, prevIdAfter = 0;
            if (DEBUG) {
                if (idx > 0) {
                    prevIdBefore = getId(idx - 1);
                }
                if (idx < 4095) {
                    prevIdAfter = getId(idx + 1);
                }
            }

            final int startBit = idx * bits;
            final long mask = -(1L << bits);
            final int bitOffset = startBit & 63;
            final int arrayIndex = startBit >>> 6;

            if (bitOffset + bits <= 64) {
                // |-----------------------xxxxxxxxxxxxxx---------------------------|
                //  ^------bitOffset------^^----size----^^---(64-bits-bitOffset)---^
                final int offsetFromEnd = 64 - bits - bitOffset;
                long value = blocks[arrayIndex];
                value &= Long.rotateLeft(mask, offsetFromEnd);
                value |= ((long) id) << offsetFromEnd;
                blocks[arrayIndex] = value;
            } else {
                // split across 2 longs
                long v1 = blocks[arrayIndex];
                long v2 = blocks[arrayIndex + 1];
                //                                                   bitOffset+bits-64
                //                                                     v============v
                // |---------------------------------------xxxxxxxxxxx|xxxxxxxxxxxxxx------------------------------------|
                //  ^--------------bitOffset--------------^^----------bits----------^^-------(128-bitOffset-bits)-------^
                int off1 = bitOffset + bits - 64;
                int off2 = 128 - bitOffset - bits;
                v1 &= mask >> off1;
                v1 |= ((long) id) >>> off1;
                blocks[arrayIndex] = v1;
                v2 &= ~((~mask) << off2);
                v2 |= ((long) id) << off2;
                blocks[arrayIndex + 1] = v2;
            }
            assert getId(idx) == id;
            if (DEBUG) {
                if (idx > 0) {
                    int idBefore = getId(idx - 1);
                    assert idBefore == prevIdBefore : "Id at block before got changed after setId, old: " + prevIdBefore + " new: " + idBefore;
                }
                if (idx < 4095) {
                    int idAfter = getId(idx + 1);
                    assert idAfter == prevIdAfter : "Id at block after got changed after setId, old: " + prevIdAfter + " new: " + idAfter;
                }
            }
        }

        private int getDataByte(byte[] array, int x, int y, int z, int def) {
            if (array == null) {
                return def;
            }
            int blockOffset = Coords.index(x, y, z);
            byte dataByte = array[blockOffset >> 1];
            // Even byte -> least significant bits
            // Odd byte -> most significant bits
            return (blockOffset & 1) == 0 ? dataByte & 0x0F : (dataByte & 0xF0) >> 4;
        }

        private byte[] setDataByte(byte[] array, int x, int y, int z, int val, int def) {
            if (array == null) {
                if (val == def) {
                    return null;
                }
                array = new byte[BLOCK_COUNT >> 1];
                if (def != 0) {
                    Arrays.fill(array, (byte) ((def & 0xF) | ((def & 0xF) << 4)));
                }
            }
            int blockOffset = Coords.index(x, y, z);
            int offset = blockOffset >> 1;
            byte dataByte = array[offset];
            // Even byte -> least significant bits
            // Odd byte -> most significant bits
            array[offset] = (blockOffset & 1) == 0 ?
                    (byte) ((dataByte & 0xF0) | (val & 0x0F)) :
                    (byte) ((dataByte & 0x0F) | ((val & 0x0F) << 4));
            return array;
        }

        int getY() {
            return yPos;
        }

        private class PlaceholderNBT extends AbstractNBTItem {
            PlaceholderNBT(boolean load) {
                super(Cube16.this.getSectionTag());
                if (!load) {
                    return;
                }
                if (!this.containsTag("Blocks")) {
                    return;
                }
                byte[] blocks = getByteArray("Blocks");
                byte[] data = getByteArray("Data");
                byte[] add = null;
                if (containsTag("Add")) {
                    add = getByteArray("Add");
                }
                for (int i = 0; i < 2048; i++) {
                    // Even byte -> least significant bits
                    // Odd byte -> most significant bits
                    int id1 = (blocks[i * 2] & 0xFF);
                    if (add != null) {
                        id1 |= (add[i] & 0xF) << 8;
                    }
                    int data1 = data[i] & 0xF;

                    setMaterial(i * 2, Material.get(id1, data1));

                    int id2 = (blocks[i * 2 + 1] & 0xFF);
                    if (add != null) {
                        id2 |= (add[i] & 0xF0) << 4;
                    }
                    int data2 = (data[i] & 0xF0) >> 4;
                    setMaterial(i * 2 + 1, Material.get(id2, data2));
                }

                skyLight = getByteArray("SkyLight");
                blockLight = getByteArray("BlockLight");
            }

            @Override
            public CompoundTag toNBT() {
                byte[] blocks = new byte[4096];
                byte[] data = new byte[2048];
                byte[] add = null;
                for (int i = 0; i < 2048; i++) {
                    // Even byte -> least significant bits
                    // Odd byte -> most significant bits
                    Material mat1 = getMaterial(i * 2);
                    Material mat2 = getMaterial(i * 2 + 1);
                    int idLSB1 = mat1.blockType & 0xFF;
                    int idLSB2 = mat2.blockType & 0xFF;
                    int idMSB1 = mat1.blockType >>> 8;
                    int idMSB2 = (mat2.blockType >>> 8) << 4;
                    int data1 = mat1.data;
                    int data2 = mat2.data << 4;
                    blocks[i * 2] = (byte) idLSB1;
                    blocks[i * 2 + 1] = (byte) idLSB2;
                    data[i] |= data1 | data2;
                    if (idMSB1 != 0 || idMSB2 != 0) {
                        if (add == null) {
                            add = new byte[2048];
                        }
                        add[i] |= idMSB1 | idMSB2;
                    }
                }

                setByteArray("Blocks", blocks);
                setByteArray("Data", data);

                if (add != null) {
                    setByteArray("Add", add);
                }

                setByteArray("SkyLight", skyLight == null ? PLACEHOLDER_WRITE_SKYLIGHT : skyLight);
                setByteArray("BlockLight", blockLight == null ? PLACEHOLDER_WRITE : blockLight);
                return super.toNBT();
            }
        }
    }

    static class SerializedColumn {
        final CompoundTag columnTag;
        final Map<Integer, CompoundTag> cubeTags;

        SerializedColumn(CompoundTag columnTag, Map<Integer, CompoundTag> sectionTags) {
            this.columnTag = columnTag;
            this.cubeTags = sectionTags;
        }

        CompoundTag getColumnLevel() {
            return (CompoundTag) this.columnTag.getTag("Level");
        }
    }
}