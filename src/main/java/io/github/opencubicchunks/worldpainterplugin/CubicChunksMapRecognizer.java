package io.github.opencubicchunks.worldpainterplugin;

import org.pepsoft.minecraft.mapexplorer.JavaMinecraftDirectoryNode;
import org.pepsoft.util.IconUtils;
import org.pepsoft.worldpainter.mapexplorer.MapRecognizer;
import org.pepsoft.worldpainter.mapexplorer.Node;

import javax.swing.*;
import java.io.File;

public class CubicChunksMapRecognizer implements MapRecognizer {

    private final CubicChunksPlatformProvider platformProvider;

    public CubicChunksMapRecognizer(CubicChunksPlatformProvider platformProvider) {
        this.platformProvider = platformProvider;
    }

    @Override
    public boolean isMap(File dir) {
        return platformProvider.isCubicWorld(dir.toPath());
    }

    @Override
    public synchronized Node getMapNode(File mapDir) {
        return new CubicChunksRootNode(mapDir);
    }

    public static class CubicChunksRootNode extends JavaMinecraftDirectoryNode {
        public CubicChunksRootNode(File dir) {
            super(dir);
        }

        @Override
        public Icon getIcon() {
            return ICON;
        }

        private static final Icon ICON = IconUtils.scaleIcon(IconUtils.loadScaledIcon("io/github/opencubicchunks/worldpainterplugin/logo.png"), 16);
    }
}