package io.github.opencubicchunks.worldpainterplugin;

import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.ChunkStore;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.exporting.JavaPostProcessor;
import org.pepsoft.worldpainter.exporting.PostProcessor;
import org.pepsoft.worldpainter.exporting.WorldExporter;
import org.pepsoft.worldpainter.mapexplorer.MapRecognizer;
import org.pepsoft.worldpainter.plugins.AbstractPlugin;
import org.pepsoft.worldpainter.plugins.BlockBasedPlatformProvider;
import org.pepsoft.worldpainter.util.MinecraftUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static io.github.opencubicchunks.worldpainterplugin.Version.VERSION;
import static java.util.Collections.singletonList;
import static org.pepsoft.worldpainter.Constants.DIM_NORMAL;
import static org.pepsoft.worldpainter.GameType.CREATIVE;
import static org.pepsoft.worldpainter.GameType.SURVIVAL;
import static org.pepsoft.worldpainter.Generator.DEFAULT;
import static org.pepsoft.worldpainter.Platform.Capability.*;

public class CubicChunksPlatformProvider extends AbstractPlugin implements BlockBasedPlatformProvider {
    public CubicChunksPlatformProvider() {
        super("CubicChunksPlatform", VERSION);
        init();
    }

    // PlatformProvider

    @Override
    public List<Platform> getKeys() {
        return singletonList(CUBICCHUNKS);
    }

    @Override
    public Chunk createChunk(Platform platform, int x, int z, int maxHeight) {
        if (!platform.equals(CUBICCHUNKS)) {
            throw new IllegalArgumentException("Platform " + platform + " not supported");
        }
        return new Chunk16Virtual(x, z, maxHeight);
    }

    @Override
    public ChunkStore getChunkStore(Platform platform, File worldDir, int dimension) {
        if (!platform.equals(CUBICCHUNKS)) {
            throw new IllegalArgumentException("Platform " + platform + " not supported");
        }
        try {
            return new CubicChunkStore(worldDir, dimension, Integer.MAX_VALUE / 2);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public WorldExporter getExporter(World2 world) {
        Platform platform = world.getPlatform();
        if (!platform.equals(CUBICCHUNKS)) {
            throw new IllegalArgumentException("Platform " + platform + " not supported");
        }
        return new CubicChunksWorldExporter(world);
    }

    @Override
    public File getDefaultExportDir(Platform platform) {
        File minecraftDir = MinecraftUtil.findMinecraftDir();
        return (minecraftDir != null) ? new File(minecraftDir, "saves") : null;
    }

    @Override
    public PostProcessor getPostProcessor(Platform platform) {
        if (!platform.equals(CUBICCHUNKS)) {
            throw new IllegalArgumentException("Platform " + platform + " not supported");
        }
        return new JavaPostProcessor();
    }

    @Override
    public MapRecognizer getMapRecognizer() {
        return new CubicChunksMapRecognizer();
    }

    private void init() {

    }

    static final Platform CUBICCHUNKS = new Platform(
            "org.pepsoft.cubicchunks",
            "CubicChunks",
            256, 256, Integer.MAX_VALUE / 2,
            Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE,
            Arrays.asList(SURVIVAL, CREATIVE),
            singletonList(DEFAULT),
            singletonList(DIM_NORMAL),
            EnumSet.of(BLOCK_BASED, BIOMES, PRECALCULATED_LIGHT, SET_SPAWN_POINT, SEED));

    private static final Logger logger = LoggerFactory.getLogger(CubicChunksPlatformProvider.class);
}