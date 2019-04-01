package io.github.opencubicchunks.worldpainterplugin;

import static java.util.stream.Collectors.toCollection;
import static org.pepsoft.minecraft.Constants.TAG_LEVEL;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import org.jnbt.ByteTag;
import org.jnbt.CompoundTag;
import org.jnbt.IntArrayTag;
import org.jnbt.IntTag;
import org.jnbt.Tag;
import org.pepsoft.minecraft.AbstractNBTItem;
import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.Entity;
import org.pepsoft.minecraft.Material;
import org.pepsoft.minecraft.MinecraftCoords;
import org.pepsoft.minecraft.TileEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * This API's coordinate system is the Minecraft coordinate system (W <- x -> E,
 * down <- y -> up, N <- z -> S).
 *
 * <p>This implementation maintains the invariant that the map block at y == 0
 * ALWAYS exists, and that any blocks above and below it are contiguous; that
 * is: there are no gaps in the column other than below the lowest and above the
 * highest map block.
 *
 * <p>Created by Pepijn on 12-2-2017.
 */
public class Chunk16Virtual extends AbstractNBTItem implements Chunk {

    private static final Logger LOGGER = LoggerFactory.getLogger(Chunk16Virtual.class);

    private int columnX;
    private int columnZ;
    private final IntObjectMap<Cube16> cubes;

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
        this.cubes = new IntObjectHashMap<>(64);
        this.maxHeight = maxHeight;
        this.readOnly = editMode == EditMode.READONLY;
    }

    public Chunk16Virtual(SerializedColumn serialized, int columnX, int columnZ, int maxHeight, EditMode editMode) {
        super(serialized.getColumnLevel());
        this.columnX = columnX;
        this.columnZ = columnZ;
        this.cubes = new IntObjectHashMap<>(64);
        this.maxHeight = maxHeight;
        this.readOnly = editMode == EditMode.READONLY;

        loadColumnData();
        serialized.cubeTags.forEach((cubeY, tag) -> cubes.put(cubeY, new Cube16(this, tag)));
    }

    //======================================
    //              NBT IO
    //======================================

    private void loadColumnData() {
        Tag v = ((CompoundTag) super.toNBT()).getTag("v");
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
        if (containsTag("Biomes")) {
            System.arraycopy(getByteArray("Biomes"), 0, biomes, 0, Coords.CUBE_SIZE * Coords.CUBE_SIZE);
        }

        if (containsTag("OpacityIndex")) {
            ByteArrayInputStream buf = new ByteArrayInputStream(getByteArray("OpacityIndex"));
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
        Map<Integer, CompoundTag> tags = new HashMap<>(cubes.size() * 2);
        for (IntObjectCursor<Cube16> cursor : cubes) {
            tags.put(cursor.key, (CompoundTag) cursor.value.toNBT());
        }
        return new SerializedColumn((CompoundTag) toNBT(), tags);
    }

    @Override
    public Tag toNBT() {
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
            cubes.put(cubeY, section = new Cube16(this, cubeY));
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
    public int getBlockType(int blockX, int blockY, int blockZ) {
        return ifSectionExists(blockY, 0, section -> section.getId(blockX, blockY, blockZ));
    }

    @Override
    public void setBlockType(int blockX, int blockY, int blockZ, int id) {
        if (readOnly) {
            return;
        }
        getOrMakeSection(blockY).setId(blockX, blockY, blockZ, id);
    }

    @Override
    public int getDataValue(int blockX, int blockY, int blockZ) {
        return ifSectionExists(blockY, 0, section -> section.getMeta(blockX, blockY, blockZ));
    }

    @Override
    public void setDataValue(int blockX, int blockY, int blockZ, int val) {
        if (readOnly) {
            return;
        }
        getOrMakeSection(blockY).setMeta(blockX, blockY, blockZ, val);
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
        int id = getBlockType(blockX, blockY, blockZ) | ifSectionExists(blockY, 0, s -> s.getExtId(blockX, blockY, blockZ)) << 8;
        return Material.get(id, getDataValue(blockX, blockY, blockZ));
    }

    @Override
    public void setMaterial(int blockX, int blockY, int blockZ, Material material) {
        if (readOnly) {
            return;
        }
        int id = material.blockType & 0xFF;
        int extId = material.blockType >> 8;
        Cube16 cube = getOrMakeSection(blockY);
        cube.setId(blockX, blockY, blockZ, id);
        cube.setExtId(blockX, blockY, blockZ, extId);
        cube.setMeta(blockX, blockY, blockZ, material.data);
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
    public boolean isBiomesAvailable() {
        return biomes != null;
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
        // start with opacity height and try to find anything up
        int maxCube = Coords.blockToCube(getHeight(blockX, blockZ));
        // iterate over all cubes and only check ones that are above and aren't empty
        for (ObjectCursor<Cube16> obj : cubes.values()) {
            Cube16 cube = obj.value;
            int cubeY = cube.getY();
            // this is expected to be the case most of the time
            if (cubeY <= maxCube || cube.isEmpty()) {
                continue;
            }
            for (int dy = 15; dy >= 0; dy--) {
                if ((cube.getId(blockX, dy, blockZ) | cube.getExtId(blockX, dy, blockZ)) != 0) {
                    maxCube = cubeY;
                    break;
                }
            }
        }
        return Coords.cubeToMaxBlock(maxCube);
    }

    @Override
    public int getHighestNonAirBlock() {
        int max = Coords.blockToCube(getHeight(0, 0));
        for (ObjectCursor<Cube16> obj : cubes.values()) {
            Cube16 cube = obj.value;
            if (cube.isEmpty()) {
                continue;
            }
            int newY = cube.getY();
            if (newY > max) {
                max = newY;
            }
        }
        return Coords.cubeToMaxBlock(max);
    }

    public static class Cube16 extends AbstractNBTItem {

        private static final int BLOCK_COUNT = Coords.CUBE_SIZE * Coords.CUBE_SIZE * Coords.CUBE_SIZE;
        // placeholder for writing to disk when original is empty, nibble version
        private static final byte[] PLACEHOLDER_WRITE = new byte[BLOCK_COUNT >> 1];
        // placeholder for writing to disk when original is empty, full byte version, filled with skylight 15
        private static final byte[] PLACEHOLDER_WRITE_SKYLIGHT = new byte[BLOCK_COUNT >> 1];
        // placeholder for writing to disk when original is empty, full byte version
        private static final byte[] PLACEHOLDER_WRITE_FULL = new byte[BLOCK_COUNT];

        static {
            Arrays.fill(PLACEHOLDER_WRITE_SKYLIGHT, (byte) 0xFF);
        }

        private Chunk16Virtual parent;
        private final int yPos;
        private final List<Entity> entities;
        private final List<TileEntity> tileEntities;
        private byte[] blocks;
        private byte[] data;
        private byte[] skyLight;
        private byte[] blockLight;
        private byte[] add;

        // a hack because of this NBT library works
        // it doesn't agree with nesting that doesn't directly correspond to in-memory nesting
        private AbstractNBTItem sectionNbtPlaceholder;

        private static final long serialVersionUID = 1L;

        Cube16(Chunk16Virtual parent, CompoundTag tag) {
            super((CompoundTag) tag.getTag("Level"));
            this.parent = parent;

            int version = getByte("v") & 0xFF;
            if (version != 1) {
                throw new IllegalArgumentException("Cube has wrong version! " + version);
            }
            yPos = getInt("y");
            sectionNbtPlaceholder = new PlaceholderNBT(true);

            List<CompoundTag> entityTags = getList("Entities");
            entities = entityTags.stream().map(Entity::fromNBT).collect(toCollection(ArrayList::new));

            List<CompoundTag> tileEntityTags = getList("TileEntities");
            tileEntities = tileEntityTags.stream().map(TileEntity::fromNBT).collect(toCollection(ArrayList::new));
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
            entities = new ArrayList<>();
            tileEntities = new ArrayList<>();
            sectionNbtPlaceholder = new PlaceholderNBT(false);
        }

        @Override
        public Tag toNBT() {
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

            setList("Sections", CompoundTag.class, Arrays.asList(sectionNbtPlaceholder.toNBT()));

            setList("Entities", CompoundTag.class, entities.stream().map(Entity::toNBT).collect(Collectors.toList()));
            setList("TileEntities", CompoundTag.class, tileEntities.stream().map(TileEntity::toNBT).collect(Collectors.toList()));

            setMap("LightingInfo", new HashMap<String, Tag>() {{
                put("LastHeightMap", new IntArrayTag("LastHeightMap", parent.yMax));
                put("EdgeNeedSkyLightUpdate", new ByteTag("EdgeNeedSkyLightUpdate", (byte) 0));
            }});
            CompoundTag cubeNbt = new CompoundTag("", new HashMap<>());
            cubeNbt.setTag("Level", super.toNBT());
            return cubeNbt;
        }

        boolean isEmpty() {
            // data must be empty is blocks and add are null
            return blocks == null && add == null;
        }

        int getBlockLight(int x, int y, int z) {
            return getDataByte(blockLight, x, y, z, 0);
        }

        int getSkyLight(int x, int y, int z) {
            return getDataByte(skyLight, x, y, z, 15);
        }

        int getMeta(int x, int y, int z) {
            return getDataByte(data, x, y, z, 0);
        }

        int getExtId(int x, int y, int z) {
            return getDataByte(add, x, y, z, 0);
        }

        int getId(int x, int y, int z) {
            if (blocks == null) {
                return 0;
            }
            return blocks[Coords.index(x, y, z)] & 0xFF;
        }

        void setBlockLight(int x, int y, int z, int val) {
            blockLight = setDataByte(blockLight, x, y, z, val, 0);
        }

        void setSkyLight(int x, int y, int z, int val) {
            skyLight = setDataByte(skyLight, x, y, z, val, 15);
        }

        void setMeta(int x, int y, int z, int val) {
            data = setDataByte(data, x, y, z, val, 0);
        }

        void setExtId(int x, int y, int z, int val) {
            add = setDataByte(add, x, y, z, val, 0);
        }

        void setId(int x, int y, int z, int val) {
            if (blocks == null) {
                if (val == 0) {
                    return;
                }
                blocks = new byte[BLOCK_COUNT];
            }
            blocks[Coords.index(x, y, z)] = (byte) val;
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
                blocks = getByteArray("Blocks");
                data = getByteArray("Data");
                if (containsTag("Add")) {
                    add = getByteArray("Add");
                }

                skyLight = getByteArray("SkyLight");
                blockLight = getByteArray("BlockLight");
            }

            @Override
            public Tag toNBT() {
                setByteArray("Blocks", blocks == null ? PLACEHOLDER_WRITE_FULL : blocks);
                setByteArray("Data", data == null ? PLACEHOLDER_WRITE : data);

                if (add != null) {
                    for (byte b : add) {
                        if (b != 0) {
                            setByteArray("Add", add);
                            break;
                        }
                    }
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