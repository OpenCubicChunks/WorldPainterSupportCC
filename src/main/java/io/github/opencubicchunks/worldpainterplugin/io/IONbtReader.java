/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package io.github.opencubicchunks.worldpainterplugin.io;

import com.sun.istack.internal.Nullable;
import io.github.opencubicchunks.worldpainterplugin.util.Coords;
import org.jnbt.CompoundTag;
import org.jnbt.ListTag;
import org.pepsoft.minecraft.Chunk;
import io.github.opencubicchunks.worldpainterplugin.Chunk16Virtual;

// TODO: actually implement all of it and move it to Chunk16Virtual
public class IONbtReader {

    public Chunk16Virtual readColumn(int x, int z, CompoundTag nbt) {
        CompoundTag level = (CompoundTag) nbt.getTag("Level");
        Chunk16Virtual column = readBaseColumn(x, z, level);
        if (column == null) {
            return null;
        }
        readBiomes(level, column);
        readOpacityIndex(level, column);

        return column; // TODO: use Chunk, not IColumn, whenever possible
    }

    @Nullable
    public Chunk16Virtual readBaseColumn(int x, int z, CompoundTag nbt) {// check the version number
        byte version = (byte) nbt.getTag("v").getValue();
        if (version != 1) {
            throw new IllegalArgumentException(String.format("Column has wrong version: %d", version));
        }

        // check the coords
        int xCheck = (int) nbt.getTag("x").getValue();
        int zCheck = (int) nbt.getTag("z").getValue();
        if (xCheck != x || zCheck != z) {
           // CubicChunks.LOGGER.warn(String.format("Column is corrupted! Expected (%d,%d) but got (%d,%d). Column will be regenerated.", x, z, xCheck, zCheck));
            return null;
        }

        // create the column
        Chunk16Virtual column = new Chunk16Virtual(x, z, Integer.MAX_VALUE/2);

        // read the rest of the column properties
        column.setInhabitedTime((long) nbt.getTag("InhabitedTime").getValue());

        //if (column.getCapabilities() != null && nbt.hasKey("ForgeCaps")) {
        //    column.getCapabilities().deserializeNBT(nbt.getCompoundTag("ForgeCaps"));
        //}
        return column;
    }

    private void readBiomes(CompoundTag nbt, Chunk16Virtual column) {// biomes
        System.arraycopy((byte[]) nbt.getTag("Biomes").getValue(), 0, column.getBiomeArray(), 0, Coords.CUBE_SIZE * Coords.CUBE_SIZE);
    }

    private void readOpacityIndex(CompoundTag nbt, Chunk chunk) {// biomes
        //IHeightMap hmap = ((IColumn) chunk).getOpacityIndex();
        //((ServerHeightMap) hmap).readData(nbt.getByteArray("OpacityIndex"));
    }

    @Nullable
    public void readCube(Chunk16Virtual column, final int cubeX, final int cubeY, final int cubeZ, CompoundTag nbt) {
        if (column.getxPos() != cubeX || column.getzPos() != cubeZ) {
            throw new IllegalArgumentException(String.format("Invalid column (%d, %d) for cube at (%d, %d, %d)",
                    column.getxPos(), column.getzPos(), cubeX, cubeY, cubeZ));
        }
        CompoundTag level = (CompoundTag) nbt.getTag("Level");
        readBaseCube(column, cubeX, cubeY, cubeZ, level);
        readBlocks(level, column.getOrMakeSection(cubeY));
        //readEntities(level, world, cube);
        //readTileEntities(level, world, cube);
        //readScheduledBlockTicks(level, world);
        //readLightingInfo(cube, level, world);
        //readBiomes(cube, level);
    }

    @Nullable
    private void readBaseCube(Chunk16Virtual column, int cubeX, int cubeY, int cubeZ, CompoundTag nbt) {
        // check the version number
        byte version = (byte) nbt.getTag("v").getValue();
        if (version != 1) {
            throw new IllegalArgumentException("Cube has wrong version! " + version);
        }

        // check the coordinates
        int xCheck = (int) nbt.getTag("x").getValue();
        int yCheck = (int) nbt.getTag("y").getValue();
        int zCheck = (int) nbt.getTag("z").getValue();
        if (xCheck != cubeX || yCheck != cubeY || zCheck != cubeZ) {
            //CubicChunks.LOGGER.error(String
            //        .format("Cube is corrupted! Expected (%d,%d,%d) but got (%d,%d,%d). Cube will be regenerated.", cubeX, cubeY, cubeZ, xCheck,
            //                yCheck, zCheck));
            return;
        }

        // check against column
        assert cubeX == column.getxPos() && cubeZ == column.getzPos() :
                String.format("Cube is corrupted! Cube (%d,%d,%d) does not match column (%d,%d).", cubeX, cubeY, cubeZ, column.getxPos(),
                        column.getzPos());


        Chunk16Virtual.Section section = column.getOrMakeSection(cubeY);
        // build the cube
       /* final Cube cube = new Cube(column, cubeY);

        // set the worldgen stage
        cube.setPopulated(nbt.getBoolean("populated"));
        cube.setSurfaceTracked(nbt.getBoolean("isSurfaceTracked")); // previous versions will get their surface tracking redone. This is intended
        cube.setFullyPopulated(nbt.getBoolean("fullyPopulated"));

        cube.setInitialLightingDone(nbt.getBoolean("initLightDone"));*/
    }

    private void readBlocks(CompoundTag nbt, Chunk16Virtual.Section ebs) {
        boolean isEmpty = nbt.getTag("Sections") == null;// is this an empty cube?
        if (isEmpty) {
            return;
        }
        ListTag sectionList = (ListTag) nbt.getTag("Sections");
        nbt = (CompoundTag) sectionList.getValue().get(0);

        byte[] blocks = (byte[]) nbt.getTag("Blocks").getValue();
        byte[] data = (byte[]) nbt.getTag("Data").getValue();
        byte[] add = nbt.getTag("Add") != null ? (byte[]) nbt.getTag("Add").getValue() : null;

        ebs.setDataFromNBT(blocks, data, add);

        ebs.setBlockLightArray((byte[]) nbt.getTag("BlockLight").getValue());

        if (nbt.getTag("SkyLight") != null) {
            ebs.setSkyLightArray((byte[]) nbt.getTag("SkyLight").getValue());
        }
    }
/*
    private static void readEntities(NBTTagCompound nbt, World world, Cube cube) {// entities
        cube.getEntityContainer().readFromNbt(nbt, "Entities", world, entity -> {
            // make sure this entity is really in the chunk
            int entityCubeX = Coords.getCubeXForEntity(entity);
            int entityCubeY = Coords.getCubeYForEntity(entity);
            int entityCubeZ = Coords.getCubeZForEntity(entity);
            if (entityCubeX != cube.getX() || entityCubeY != cube.getY() || entityCubeZ != cube.getZ()) {
                CubicChunks.LOGGER.warn(String.format("Loaded entity %s in cube (%d,%d,%d) to cube (%d,%d,%d)!", entity.getClass()
                        .getName(), entityCubeX, entityCubeY, entityCubeZ, cube.getX(), cube.getY(), cube.getZ()));
            }

            // The entity needs to know what Cube it is in, this is normally done in Cube.addEntity()
            // but Cube.addEntity() is not used when loading entities
            // (unlike vanilla which uses Chunk.addEntity() even when loading entities)
            entity.addedToChunk = true;
            entity.chunkCoordX = cube.getX();
            entity.chunkCoordY = cube.getY();
            entity.chunkCoordZ = cube.getZ();
        });
    }

    private static void readTileEntities(NBTTagCompound nbt, World world, Cube cube) {// tile entities
        NBTTagList nbtTileEntities = nbt.getTagList("TileEntities", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < nbtTileEntities.tagCount(); i++) {
            NBTTagCompound nbtTileEntity = nbtTileEntities.getCompoundTagAt(i);
            //TileEntity.create
            TileEntity blockEntity = TileEntity.create(world, nbtTileEntity);
            if (blockEntity != null) {
                cube.addTileEntity(blockEntity);
            }
        }
    }

    private static void readScheduledBlockTicks(NBTTagCompound nbt, World world) {
        if (!(world instanceof WorldServer)) {
            // if not server, reading from client cache which doesn't have scheduled ticks
            return;
        }
        NBTTagList nbtScheduledTicks = nbt.getTagList("TileTicks", 10);
        for (int i = 0; i < nbtScheduledTicks.tagCount(); i++) {
            NBTTagCompound nbtScheduledTick = nbtScheduledTicks.getCompoundTagAt(i);
            Block block;
            if (nbtScheduledTick.hasKey("i", Constants.NBT.TAG_STRING)) {
                block = Block.getBlockFromName(nbtScheduledTick.getString("i"));
            } else {
                block = Block.getBlockById(nbtScheduledTick.getInteger("i"));
            }
            if (block == null) {
                continue;
            }
            world.scheduleBlockUpdate(
                    new BlockPos(
                            nbtScheduledTick.getInteger("x"),
                            nbtScheduledTick.getInteger("y"),
                            nbtScheduledTick.getInteger("z")
                    ),
                    block,
                    nbtScheduledTick.getInteger("t"),
                    nbtScheduledTick.getInteger("p")
            );
        }
    }

    private static void readLightingInfo(Cube cube, NBTTagCompound nbt, World world) {
        NBTTagCompound lightingInfo = nbt.getCompoundTag("LightingInfo");
        int[] lastHeightMap = lightingInfo.getIntArray("LastHeightMap"); // NO NO NO! TODO: Why is hightmap being stored in Cube's data?! kill it!
        int[] currentHeightMap = cube.getColumn().getHeightMap();
        byte edgeNeedSkyLightUpdate = 0x3F;
        if (lightingInfo.hasKey("EdgeNeedSkyLightUpdate"))
            edgeNeedSkyLightUpdate = lightingInfo.getByte("EdgeNeedSkyLightUpdate");
        for (int i = 0; i < cube.edgeNeedSkyLightUpdate.length; i++) {
            cube.edgeNeedSkyLightUpdate[i] = (edgeNeedSkyLightUpdate >>> i & 1) == 1;
        }

        // assume changes outside of this cube have no effect on this cube.
        // In practice changes up to 15 blocks above can affect it,
        // but it will be fixed by lighting update in other cube anyway
        int minBlockY = Coords.cubeToMinBlock(cube.getY());
        int maxBlockY = Coords.cubeToMaxBlock(cube.getY());
        LightingManager lightManager = ((ICubicWorldInternal) cube.getWorld()).getLightingManager();
        for (int i = 0; i < currentHeightMap.length; i++) {
            int currentY = currentHeightMap[i];
            int lastY = lastHeightMap[i];

            //sort currentY and lastY
            int minUpdateY = Math.min(currentY, lastY);
            int maxUpdateY = Math.max(currentY, lastY);

            boolean needLightUpdate = minUpdateY != maxUpdateY &&
                    //if max update Y is below minY - nothing to update
                    !(maxUpdateY < minBlockY) &&
                    //if min update Y is above maxY - nothing to update
                    !(minUpdateY > maxBlockY);
            if (needLightUpdate) {

                //clamp min/max update Y to be within current cube bounds
                if (minUpdateY < minBlockY) {
                    minUpdateY = minBlockY;
                }
                if (maxUpdateY > maxBlockY) {
                    maxUpdateY = maxBlockY;
                }
                assert minUpdateY <= maxUpdateY : "minUpdateY > maxUpdateY: " + minUpdateY + ">" + maxUpdateY;

                int localX = i & 0xF;
                int localZ = i >> 4;
                lightManager.markCubeBlockColumnForUpdate(cube,
                        localToBlock(cube.getX(), localX), localToBlock(cube.getZ(), localZ));
            }
        }
    }

    private static void readBiomes(Cube cube, NBTTagCompound nbt) {// biomes
        if (nbt.hasKey("Biomes"))
            cube.setBiomeArray(nbt.getByteArray("Biomes"));
    }*/
}
