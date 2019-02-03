package io.github.opencubicchunks.worldpainterplugin;

import io.github.opencubicchunks.worldpainterplugin.util.Coords;
import org.jnbt.CompoundTag;
import org.jnbt.Tag;
import org.pepsoft.minecraft.*;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.ToIntFunction;

import static org.pepsoft.minecraft.Constants.*;

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
public class Chunk16Virtual implements Chunk {

    private final int columnX;
    private final int columnZ;
    private final Map<Integer, Section> sections;
    private final int[] heightMap = new int[Coords.CUBE_SIZE * Coords.CUBE_SIZE];
    private byte[] biomes = new byte[Coords.CUBE_SIZE * Coords.CUBE_SIZE];

    private final List<Entity> entities = new ArrayList<>();
    private final List<TileEntity> tileEntities = new ArrayList<>();
    private final int maxHeight;

    private final boolean readOnly;
    private boolean forcePopulated;
    private boolean forceLightPopulated;
    private long inhabitedTime;

    public Chunk16Virtual(int columnX, int columnZ, int maxHeight) {
        this(columnX, columnZ, new HashMap<>(), maxHeight);
    }

    public Chunk16Virtual(int columnX, int columnZ, Map<Integer, Section> sections, int maxHeight) {
        this.columnX = columnX;
        this.columnZ = columnZ;
        this.sections = sections;
        this.maxHeight = maxHeight;
        this.readOnly = false;
    }

    public Section getOrMakeSection(int blockY) {
        int cubeY = Coords.blockToCube(blockY);
        Section section = sections.get(cubeY);
        if (section == null) {
            sections.put(cubeY, section = new Section(cubeY));
        }
        return section;
    }

    private int ifSectionExists(int blockY, int def, ToIntFunction<Section> cons) {
        int cubeY = Coords.blockToCube(blockY);
        Section section = sections.get(cubeY);
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
        getOrMakeSection(blockY).setBlockLight(blockX, blockY, blockZ, level);
    }

    @Override
    public int getBlockType(int blockX, int blockY, int blockZ) {
        return ifSectionExists(blockY, 0, section -> section.getId(blockX, blockY, blockZ));
    }

    @Override
    public void setBlockType(int blockX, int blockY, int blockZ, int id) {
        getOrMakeSection(blockY).setId(blockX, blockY, blockZ, id);
    }

    @Override
    public int getDataValue(int blockX, int blockY, int blockZ) {
        return ifSectionExists(blockY, 0, section -> section.getMeta(blockX, blockY, blockZ));
    }

    @Override
    public void setDataValue(int blockX, int blockY, int blockZ, int val) {
        getOrMakeSection(blockY).setMeta(blockX, blockY, blockZ, val);
    }

    @Override
    public int getHeight(int blockX, int blockZ) {
        return heightMap[Coords.index(blockX, blockZ)];
    }

    @Override
    public void setHeight(int blockX, int blockZ, int height) {
        heightMap[Coords.index(blockX, blockZ)] = height;
    }

    @Override
    public int getSkyLightLevel(int blockX, int blockY, int blockZ) {
        return ifSectionExists(blockY, 15, section -> section.getSkyLight(blockX, blockY, blockZ));
    }

    @Override
    public void setSkyLightLevel(int blockX, int blockY, int blockZ, int val) {
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
    public Point getCoords() {
        return new Point(getxPos(), getzPos());
    }

    @Override
    public boolean isTerrainPopulated() {
        throw new UnsupportedOperationException("This was never used at the time I was writing this and this can't be implemented in cubic chunks");
    }

    @Override
    public void setTerrainPopulated(boolean terrainPopulated) {
        this.forcePopulated = terrainPopulated;
    }

    @Override
    public Material getMaterial(int blockX, int blockY, int blockZ) {
        int id = getBlockType(blockX, blockY, blockZ) | ifSectionExists(blockY, 0, s -> s.getExtId(blockX, blockY, blockZ)) << 8;
        return Material.get(id, getDataValue(blockX, blockY, blockZ));
    }

    @Override
    public void setMaterial(int blockX, int blockY, int blockZ, Material material) {
        int id = material.blockType & 0xFF;
        int extId = material.blockType >> 8;
        setBlockType(blockX, blockY, blockZ, id);
        getOrMakeSection(blockY).setExtId(blockX, blockY, blockZ, extId);
        setDataValue(blockX, blockY, blockZ, material.data);
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
        return biomes == null ? 0 : biomes[Coords.index(blockX, blockZ)];
    }

    @Override
    public void setBiome(int blockX, int blockZ, int biome) {
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
        this.forceLightPopulated = lightPopulated;
    }

    @Override
    public long getInhabitedTime() {
        return this.inhabitedTime;
    }

    @Override
    public void setInhabitedTime(long inhabitedTime) {
        this.inhabitedTime = inhabitedTime;
    }

    @Override
    public int getHighestNonAirBlock(int x, int z) {
        return 0;
    }

    @Override
    public int getHighestNonAirBlock() {
        return 0;
    }

    // serialization
    public void setBiomeArray(byte[] biomeArray) {
        this.biomes = biomeArray;
    }

    public byte[] getBiomeArray() {
        return biomes;
    }

    public Map<Integer, Section> getSections() {
        return sections;
    }

    public byte isForcePopulated() {
        return (byte) (forcePopulated ? 1 : 0);
    }

    public static class Section extends AbstractNBTItem {

        private final int level;
        private byte[] blocks;
        private byte[] data;
        private byte[] skyLight;
        private byte[] blockLight;
        private byte[] add;

        private static final long serialVersionUID = 1L;

        Section(CompoundTag tag) {
            super(tag);
            level = getInt(TAG_Y2);
            blocks = getByteArray(TAG_BLOCKS);
            if (containsTag(TAG_ADD)) {
                add = getByteArray(TAG_ADD);
            }
            data = getByteArray(TAG_DATA);
            skyLight = getByteArray(TAG_SKY_LIGHT);
            blockLight = getByteArray(TAG_BLOCK_LIGHT);
        }

        Section(int level) {
            super(new CompoundTag("", new HashMap<>()));
            this.level = Coords.cubeToMinBlock(level);
            blocks = new byte[256 * 16];
            data = new byte[128 * 16];
            skyLight = new byte[128 * 16];
            Arrays.fill(skyLight, (byte) 0xff);
            blockLight = new byte[128 * 16];
        }

        @Override
        public Tag toNBT() {
            setInt(TAG_Y2, level);
            setByteArray(TAG_BLOCKS, blocks);
            if (add != null) {
                for (byte b : add) {
                    if (b != 0) {
                        setByteArray(TAG_ADD, add);
                        break;
                    }
                }
            }
            setByteArray(TAG_DATA, data);
            setByteArray(TAG_SKY_LIGHT, skyLight);
            setByteArray(TAG_BLOCK_LIGHT, blockLight);
            return super.toNBT();
        }

        /**
         * Indicates whether the section is empty, meaning all block ID's, data
         * values and block light values are 0, and all sky light values are 15.
         *
         * @return <code>true</code> if the section is empty
         */
        boolean isEmpty() {
            for (byte b : blocks) {
                if (b != (byte) 0) {
                    return false;
                }
            }
            if (add != null) {
                for (byte b : add) {
                    if (b != (byte) 0) {
                        return false;
                    }
                }
            }
            for (byte b : skyLight) {
                if (b != (byte) -1) {
                    return false;
                }
            }
            for (byte b : blockLight) {
                if (b != (byte) 0) {
                    return false;
                }
            }
            for (byte b : data) {
                if (b != (byte) 0) {
                    return false;
                }
            }
            return true;
        }

        int getBlockLight(int x, int y, int z) {
            return getDataByte(blockLight, x, y, z);
        }

        int getSkyLight(int x, int y, int z) {
            return getDataByte(skyLight, x, y, z);
        }

        int getMeta(int x, int y, int z) {
            return getDataByte(data, x, y, z);
        }

        int getExtId(int x, int y, int z) {
            if (add == null) {
                return 0;
            }
            return getDataByte(add, x, y, z);
        }

        int getId(int x, int y, int z) {
            return blocks[Coords.index(x, y, z)] & 0xFF;
        }

        void setBlockLight(int x, int y, int z, int val) {
            setDataByte(blockLight, x, y, z, val);
        }

        void setSkyLight(int x, int y, int z, int val) {
            setDataByte(skyLight, x, y, z, val);
        }

        void setMeta(int x, int y, int z, int val) {
            setDataByte(data, x, y, z, val);
        }

        void setExtId(int x, int y, int z, int val) {
            if (add == null) {
                if (val == 0) {
                    return;
                }
                add = new byte[2048];
            }
            setDataByte(add, x, y, z, val);
        }

        void setId(int x, int y, int z, int val) {
            blocks[Coords.index(x, y, z)] = (byte) val;
        }

        private int getDataByte(byte[] array, int x, int y, int z) {
            int blockOffset = Coords.index(x, y, z);
            byte dataByte = array[blockOffset >> 1];
            // Even byte -> least significant bits
            // Odd byte -> most significant bits
            return (blockOffset & 1) == 0 ? dataByte & 0x0F : (dataByte & 0xF0) >> 4;
        }

        private void setDataByte(byte[] array, int x, int y, int z, int val) {
            int blockOffset = Coords.index(x, y, z);
            int offset = blockOffset >> 1;
            byte dataByte = array[offset];
            // Even byte -> least significant bits
            // Odd byte -> most significant bits
            array[offset] = (blockOffset & 1) == 0 ?
                    (byte) ((dataByte & 0xF0) | (val & 0x0F)) :
                    (byte) ((dataByte & 0x0F) | ((val & 0x0F) << 4));
        }

        public void setDataFromNBT(byte[] blocks, byte[] data, byte[] add) {
            this.blocks = blocks;
            this.data = data;
            this.add = add;
        }

        public void setBlockLightArray(byte[] array) {
            this.blockLight = array;
        }

        public void setSkyLightArray(byte[] array) {
            this.skyLight = array;
        }

        public int getY() {
            return level;
        }

        public byte[] getIds() {
            return blocks;
        }

        public byte[] getMetas() {
            return data;
        }

        public byte[] getExtIds() {
            return add;
        }

        public byte[] getSkylight() {
            return skyLight;
        }

        public byte[] getBlocklight() {
            return blockLight;
        }
    }
}