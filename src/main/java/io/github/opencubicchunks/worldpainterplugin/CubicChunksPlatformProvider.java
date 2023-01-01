package io.github.opencubicchunks.worldpainterplugin;

import static io.github.opencubicchunks.worldpainterplugin.Version.VERSION;
import static java.util.Collections.singletonList;
import static org.pepsoft.worldpainter.Constants.*;
import static org.pepsoft.worldpainter.GameType.ADVENTURE;
import static org.pepsoft.worldpainter.GameType.CREATIVE;
import static org.pepsoft.worldpainter.GameType.HARDCORE;
import static org.pepsoft.worldpainter.GameType.SURVIVAL;
import static org.pepsoft.worldpainter.Generator.AMPLIFIED;
import static org.pepsoft.worldpainter.Generator.CUSTOM;
import static org.pepsoft.worldpainter.Generator.DEFAULT;
import static org.pepsoft.worldpainter.Generator.END;
import static org.pepsoft.worldpainter.Generator.FLAT;
import static org.pepsoft.worldpainter.Generator.LARGE_BIOMES;
import static org.pepsoft.worldpainter.Generator.NETHER;
import static org.pepsoft.worldpainter.Platform.Capability.*;

import org.jnbt.ByteTag;
import org.jnbt.CompoundTag;
import org.jnbt.NBTInputStream;
import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.ChunkStore;
import org.pepsoft.minecraft.MinecraftCoords;
import org.pepsoft.worldpainter.Constants;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.TileFactory;
import org.pepsoft.worldpainter.World2;
import org.pepsoft.worldpainter.exporting.ExportSettings;
import org.pepsoft.worldpainter.exporting.ExportSettingsEditor;
import org.pepsoft.worldpainter.exporting.PostProcessor;
import org.pepsoft.worldpainter.exporting.WorldExporter;
import org.pepsoft.worldpainter.exporting.WorldExportSettings;
import org.pepsoft.worldpainter.importing.JavaMapImporter;
import org.pepsoft.worldpainter.importing.MapImporter;
import org.pepsoft.worldpainter.mapexplorer.MapExplorerSupport;
import org.pepsoft.worldpainter.mapexplorer.Node;
import org.pepsoft.worldpainter.platforms.Java1_2PostProcessor;
import org.pepsoft.worldpainter.platforms.JavaExportSettings;
import org.pepsoft.worldpainter.platforms.JavaExportSettingsEditor;
import org.pepsoft.worldpainter.plugins.AbstractPlugin;
import org.pepsoft.worldpainter.plugins.BlockBasedPlatformProvider;
import org.pepsoft.worldpainter.plugins.MapImporterProvider;
import org.pepsoft.worldpainter.util.MinecraftUtil;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

public class CubicChunksPlatformProvider extends AbstractPlugin implements BlockBasedPlatformProvider, MapImporterProvider, MapExplorerSupport {
    public CubicChunksPlatformProvider() {
        super("CubicChunksPlatform", VERSION);
        init();
    }

    // PlatformProvider

    @Override
    public List<Platform> getKeys() {
        return singletonList(CUBICCHUNKS);
    }

    @Override public int[] getDimensions(Platform platform, File file) {
        Path world = file.toPath();
        if (!isCubicWorld(world)) {
            return new int[0];
        }
        try {
            if (platform.equals(CUBICCHUNKS)) {
                try (Stream<Path> dirs = Files.list(world)) {
                    return IntStream.concat(
                        dirs.filter(this::isCubicChunksDimension)
                            .map(Path::getFileName)
                            .map(Path::toString)
                            .filter(name -> name.matches("DIM-?[\\d]+"))
                            .map(name -> name.substring("DIM".length()))
                            .mapToInt(Integer::parseInt), IntStream.of(0)
                            .filter(this::isSupportedDimension)
                            .map(this::toWPDimensionId)
                    ).toArray();
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return new int[0];
    }

    private int toWPDimensionId(int id) {
        switch (id) {
            case 0:
                return DIM_NORMAL;
            case 1:
                return DIM_END;
            case -1:
                return DIM_NETHER;
            default:
                assert false;
                return id;
        }
    }

    private boolean isSupportedDimension(int id) {
        return id == 0 || id == 1 || id == -1;
    }

    public boolean isCubicWorld(Path worldDir) {
        Path levelDat = worldDir.resolve("level.dat");
        if (!Files.exists(levelDat)) {
            return false;
        }
        try (NBTInputStream in = new NBTInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(levelDat))))) {
            CompoundTag tag = (CompoundTag) in.readTag();
            CompoundTag data = (CompoundTag) tag.getTag("Data");
            return (data != null) && data.containsTag("isCubicWorld") && ((ByteTag) data.getTag("isCubicWorld")).getValue() == 1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean isCubicChunksDimension(Path path) {
        Path ccData = path.resolve("data/cubicChunksData.dat");
        if (!Files.exists(ccData)) {
            // if this file hasn't been created and this is in fact CC dimension, no harm one as there aren't any chunks yet
            return false;
        }
        try (NBTInputStream in = new NBTInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(ccData))))) {
            CompoundTag tag = (CompoundTag) in.readTag();
            ByteTag isCC = ((ByteTag) tag.getTag("isCubicChunks"));
            return isCC != null && isCC.getValue() == 1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Chunk createChunk(Platform platform, int x, int z, int maxHeight) {
        if (!platform.equals(CUBICCHUNKS)) {
            throw new IllegalArgumentException("Platform " + platform + " not supported");
        }
        return new Chunk16Virtual(x, z, maxHeight, EditMode.EDITABLE);
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
    public WorldExporter getExporter(World2 world, WorldExportSettings exportSettings) {
        Platform platform = world.getPlatform();
        if (!platform.equals(CUBICCHUNKS)) {
            throw new IllegalArgumentException("Platform " + platform + " not supported");
        }
        return new CubicChunksWorldExporter(world, exportSettings);
    }

    @Override
    public File getDefaultExportDir(Platform platform) {
        File minecraftDir = MinecraftUtil.findMinecraftDir();
        return (minecraftDir != null) ? new File(minecraftDir, "saves") : null;
    }

    @Override
    public MapInfo identifyMap(File dir) {
        if (isCubicWorld(dir.toPath())) {
            return new MapInfo(dir, CUBICCHUNKS, dir.getName(), CubicChunksMapRecognizer.CubicChunksRootNode.ICON, CUBICCHUNKS.maxMaxHeight);
        } else {
            return null;
        }
    }

    @Override
    public ExportSettings getDefaultExportSettings(Platform platform) {
        return new JavaExportSettings();
    }

    @Override
    public ExportSettingsEditor getExportSettingsEditor(Platform platform) {
        return new JavaExportSettingsEditor(platform);
    }

    @Override
    public PostProcessor getPostProcessor(Platform platform) {
        if (!platform.equals(CUBICCHUNKS)) {
            throw new IllegalArgumentException("Platform " + platform + " not supported");
        }
        return new Java1_2PostProcessor();
    }

    private void init() {

    }

    // MapImporterProvider

    @Override
    public MapImporter getImporter(File dir, TileFactory tileFactory, Set<MinecraftCoords> chunksToSkip, MapImporter.ReadOnlyOption readOnlyOption, Set<Integer> dimensionsToImport) {
        return new JavaMapImporter(CUBICCHUNKS, tileFactory, new File(dir, "level.dat"), chunksToSkip, readOnlyOption, dimensionsToImport);
    }

    // MapExplorerSupport

    @Override
    public Node getMapNode(File mapDir) {
        return new CubicChunksMapRecognizer.CubicChunksRootNode(mapDir);
    }

    static final Platform CUBICCHUNKS = new Platform(
            "org.pepsoft.cubicchunks",
            "Cubic Chunks (1.10.2 - 1.12.2)",
            256,
            8192,
            Math.min(Constants.MAX_HEIGHT, Integer.MAX_VALUE / 2),
            Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE,
            Arrays.asList(SURVIVAL, CREATIVE, ADVENTURE, HARDCORE),
            Arrays.asList(DEFAULT, FLAT, LARGE_BIOMES, AMPLIFIED, CUSTOM, NETHER, END),
            Arrays.asList(DIM_NORMAL, DIM_NETHER, DIM_END),
            EnumSet.of(BIOMES_3D, PRECALCULATED_LIGHT, SET_SPAWN_POINT, BLOCK_BASED, SEED, POPULATE));
}