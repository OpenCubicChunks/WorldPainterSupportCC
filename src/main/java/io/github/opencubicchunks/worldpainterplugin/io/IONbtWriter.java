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

import io.github.opencubicchunks.worldpainterplugin.util.Coords;
import org.jnbt.*;
import io.github.opencubicchunks.worldpainterplugin.Chunk16Virtual;

import java.util.ArrayList;
import java.util.HashMap;

// TODO: actually implement all of it and move it to Chunk16Virtual
public class IONbtWriter {

    public CompoundTag write(Chunk16Virtual column) {
        CompoundTag columnNbt = new CompoundTag("", new HashMap<>());
        CompoundTag level = new CompoundTag("Level", new HashMap<>());
        columnNbt.setTag("Level", level);
        writeBaseColumn(column, level);
        writeBiomes(column, level);
        return columnNbt;
    }

    public CompoundTag write(Chunk16Virtual column, final Chunk16Virtual.Section cube) {
        CompoundTag cubeNbt = new CompoundTag("", new HashMap<>());
        //Added to preserve compatibility with vanilla NBT chunk format.
        CompoundTag level = new CompoundTag("Level", new HashMap<>());
        cubeNbt.setTag("Level", level);
        writeBaseCube(column, cube, level);
        writeBlocks(cube, level);
        //writeEntities(cube, level);
        //writeTileEntities(cube, level);
        //writeScheduledTicks(cube, level);
        //writeLightingInfo(cube, level);
        //writeBiomes(cube, level);
        return cubeNbt;
    }

    private void writeBaseColumn(Chunk16Virtual column, CompoundTag nbt) {// coords
        nbt.setTag("x", new IntTag("x", column.getxPos()));
        nbt.setTag("z", new IntTag("z", column.getzPos()));

        // column properties
        nbt.setTag("v", new ByteTag("v", (byte) 1));
        nbt.setTag("InhabitedTime", new LongTag("InhabitedTime", column.getInhabitedTime()));
    }

    private void writeBiomes(Chunk16Virtual column, CompoundTag nbt) {// biomes
        nbt.setTag("Biomes", new ByteArrayTag("Biomes", column.getBiomeArray()));
    }

    private void writeBaseCube(Chunk16Virtual column, Chunk16Virtual.Section cube, CompoundTag cubeNbt) {
        cubeNbt.setTag("v", new ByteTag("v", (byte) 1));

        // coords
        cubeNbt.setTag("x", new IntTag("x", column.getxPos()));
        cubeNbt.setTag("y", new IntTag("y", Coords.blockToCube(cube.getY())));
        cubeNbt.setTag("z", new IntTag("z", column.getzPos()));

        // save the worldgen stage and the target stage
        cubeNbt.setTag("populated", new ByteTag("populated", column.isForcePopulated()));
        cubeNbt.setTag("isSurfaceTracked", new ByteTag("isSurfaceTracked", (byte) 0));
        cubeNbt.setTag("fullyPopulated", new ByteTag("fullyPopulated", column.isForcePopulated()));

        cubeNbt.setTag("initLightDone",  new ByteTag("initLightDone", (byte) 0));
    }

    private void writeBlocks(Chunk16Virtual.Section ebs, CompoundTag cubeNbt) {
        ArrayList sectionArrList = new ArrayList<>();
        CompoundTag section = new CompoundTag("", new HashMap<>());
        sectionArrList.add(section);
        ListTag sectionList = new ListTag("Sections", CompoundTag.class, sectionArrList);
        cubeNbt.setTag("Sections", sectionList);
        byte[] ids = ebs.getIds();
        byte[] meta = ebs.getMetas();
        byte[] add = ebs.getExtIds();
        byte[] skylight = ebs.getSkylight();
        byte[] blocklight = ebs.getBlocklight();

        section.setTag("Blocks", new ByteArrayTag("Blocks", ids));
        section.setTag("Data", new ByteArrayTag("Data", meta));

        if (add != null) {
            section.setTag("Add", new ByteArrayTag("Add", add));
        }

        section.setTag("BlockLight", new ByteArrayTag("BlockLight", skylight));

        if (blocklight != null) {
            section.setTag("SkyLight", new ByteArrayTag("SkyLight", skylight));
        }
    }
/*
    private void writeEntities(Cube cube, NBTTagCompound cubeNbt) {// entities
        cube.getEntityContainer().writeToNbt(cubeNbt, "Entities", entity -> {
            // make sure this entity is really in the chunk
            int cubeX = Coords.getCubeXForEntity(entity);
            int cubeY = Coords.getCubeYForEntity(entity);
            int cubeZ = Coords.getCubeZForEntity(entity);
            if (cubeX != cube.getX() || cubeY != cube.getY() || cubeZ != cube.getZ()) {
                CubicChunks.LOGGER.warn(String.format("Saved entity %s in cube (%d,%d,%d) to cube (%d,%d,%d)! Entity thinks its in (%d,%d,%d)",
                        entity.getClass().getName(),
                        cubeX, cubeY, cubeZ,
                        cube.getX(), cube.getY(), cube.getZ(),
                        entity.chunkCoordX, entity.chunkCoordY, entity.chunkCoordZ
                ));
            }
        });
    }

    private void writeTileEntities(Cube cube, NBTTagCompound cubeNbt) {// tile entities
        NBTTagList nbtTileEntities = new NBTTagList();
        cubeNbt.setTag("TileEntities", nbtTileEntities);
        for (TileEntity blockEntity : cube.getTileEntityMap().values()) {
            NBTTagCompound nbtTileEntity = new NBTTagCompound();
            blockEntity.writeToNBT(nbtTileEntity);
            nbtTileEntities.appendTag(nbtTileEntity);
        }
    }

    private void writeScheduledTicks(Cube cube, NBTTagCompound cubeNbt) {// scheduled block ticks
        Iterable<NextTickListEntry> scheduledTicks = getScheduledTicks(cube);
        long time = cube.getWorld().getTotalWorldTime();

        NBTTagList nbtTicks = new NBTTagList();
        cubeNbt.setTag("TileTicks", nbtTicks);
        for (NextTickListEntry scheduledTick : scheduledTicks) {
            NBTTagCompound nbtScheduledTick = new NBTTagCompound();
            ResourceLocation resourcelocation = Block.REGISTRY.getNameForObject(scheduledTick.getBlock());
            nbtScheduledTick.setString("i", resourcelocation.toString());
            nbtScheduledTick.setInteger("x", scheduledTick.position.getX());
            nbtScheduledTick.setInteger("y", scheduledTick.position.getY());
            nbtScheduledTick.setInteger("z", scheduledTick.position.getZ());
            nbtScheduledTick.setInteger("t", (int) (scheduledTick.scheduledTime - time));
            nbtScheduledTick.setInteger("p", scheduledTick.priority);
            nbtTicks.appendTag(nbtScheduledTick);
        }
    }

    private void writeLightingInfo(Cube cube, NBTTagCompound cubeNbt) {
        NBTTagCompound lightingInfo = new NBTTagCompound();
        cubeNbt.setTag("LightingInfo", lightingInfo);

        int[] lastHeightmap = cube.getColumn().getHeightMap();
        lightingInfo.setIntArray("LastHeightMap", lastHeightmap); //TODO: why are we storing the height map on a Cube???
        byte edgeNeedSkyLightUpdate = 0;
        for (int i = 0; i < cube.edgeNeedSkyLightUpdate.length; i++) {
            if (cube.edgeNeedSkyLightUpdate[i])
                edgeNeedSkyLightUpdate |= 1 << i;
        }
        lightingInfo.setByte("EdgeNeedSkyLightUpdate", edgeNeedSkyLightUpdate);
    }

    private void writeBiomes(Cube cube, NBTTagCompound nbt) {// biomes
        byte[] biomes = cube.getBiomeArray();
        if (biomes != null)
            nbt.setByteArray("Biomes", biomes);
    }

    private List<NextTickListEntry> getScheduledTicks(Cube cube) {
        ArrayList<NextTickListEntry> out = new ArrayList<>();

        // make sure this is a server, otherwise don't save these, writing to client cache
        if (!(cube.getWorld() instanceof WorldServer)) {
            return out;
        }
        WorldServer worldServer = (WorldServer) cube.getWorld();

        // copy the ticks for this cube
        copyScheduledTicks(out, getPendingTickListEntriesHashSet(worldServer), cube);
        copyScheduledTicks(out, getPendingTickListEntriesThisTick(worldServer), cube);

        return out;
    }

    private void copyScheduledTicks(ArrayList<NextTickListEntry> out, Collection<NextTickListEntry> scheduledTicks, Cube cube) {
        out.addAll(scheduledTicks.stream().filter(scheduledTick -> cube.containsBlockPos(scheduledTick.position)).collect(Collectors.toList()));
    }*/
}
