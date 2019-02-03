package io.github.opencubicchunks.worldpainterplugin;

import org.pepsoft.minecraft.mapexplorer.DirectoryNode;
import org.pepsoft.minecraft.mapexplorer.FileSystemNode;
import org.pepsoft.minecraft.mapexplorer.JavaMapRootNode;
import org.pepsoft.worldpainter.mapexplorer.MapRecognizer;
import org.pepsoft.worldpainter.mapexplorer.Node;
import org.pepsoft.util.IconUtils;
import org.tmatesoft.sqljet.core.SqlJetException;
import org.tmatesoft.sqljet.core.SqlJetTransactionMode;
import org.tmatesoft.sqljet.core.table.ISqlJetCursor;
import org.tmatesoft.sqljet.core.table.ISqlJetTable;
import org.tmatesoft.sqljet.core.table.SqlJetDb;

import javax.swing.*;
import java.io.File;
import java.util.*;

/**
 * Created by Pepijn on 25-2-2017.
 */
public class CubicChunksMapRecognizer implements MapRecognizer {
    @Override
    public boolean isMap(File dir) {
        return new File(dir, "level.dat").isFile() && new File(dir, "region3d").isDirectory();
    }

    @Override
    public synchronized Node getMapNode(File mapDir) {
        return new JavaMapRootNode(mapDir);
    }
}