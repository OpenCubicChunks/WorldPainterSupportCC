package io.github.opencubicchunks.worldpainterplugin;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.ObjectArrayList;

public class CubeMap {

    private final IntObjectHashMap<Chunk16Virtual.Cube16> byCubeY = new IntObjectHashMap<>(32);
    private final ObjectArrayList<Chunk16Virtual.Cube16> cubes = new ObjectArrayList<>(32);

    public CubeMap() {
        // a little hack to make the buffer the type we want
        cubes.buffer = new Chunk16Virtual.Cube16[cubes.buffer.length];
    }

    public Chunk16Virtual.Cube16 get(int cubeY) {
        return byCubeY.get(cubeY);
    }

    /**
     * Adds a cube
     *
     * @param cube the cube to add
     */
    public void put(Chunk16Virtual.Cube16 cube) {
        int searchIndex = binarySearch(cube.getY());
        if (this.contains(cube.getY(), searchIndex)) {
            throw new IllegalArgumentException("Cube at " + cube.getY() + " already exists!");
        }
        cubes.insert(searchIndex, cube);
        byCubeY.put(cube.getY(), cube);
    }

    /**
     * @return internal array with all the cubes. Size may be larger than actual amount of cubes,
     * filled with nulls after the end.
     */
    public Chunk16Virtual.Cube16[] array() {
        return (Chunk16Virtual.Cube16[]) cubes.buffer;
    }

    public int indexOfY(int cubeY) {
        return binarySearch(cubeY);
    }

    /**
     * Check if the target cube is stored here
     *
     * @param cubeY the y coordinate of the cube
     * @param searchIndex the index to search at (got form {@link #binarySearch(int)})
     *
     * @return <code>true</code> if the cube is contained here, <code>false</code> otherwise
     */
    private boolean contains(int cubeY, int searchIndex) {
        return searchIndex < cubes.size() && cubes.get(searchIndex).getY() == cubeY;
    }

    /**
     * Binary search for the index of the specified cube. If the cube is not present, returns the index at which it
     * should be inserted.
     *
     * @param cubeY cube y position
     *
     * @return the target index
     */
    private int binarySearch(int cubeY) {
        int start = 0;
        int end = cubes.size() - 1;
        int mid;

        while (start <= end) {
            mid = start + end >>> 1;

            int at = cubes.get(mid).getY();
            if (at < cubeY) { // we are below the target;
                start = mid + 1;
            } else if (at > cubeY) {
                end = mid - 1; // we are above the target
            } else {
                return mid;// found target!
            }
        }

        return start; // not found :(
    }
}
