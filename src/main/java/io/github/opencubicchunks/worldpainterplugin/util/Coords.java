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
package io.github.opencubicchunks.worldpainterplugin.util;

import java.util.Random;

public class Coords {

    public static final int CUBE_SIZE = 16;
    public static final int NO_HEIGHT = Integer.MIN_VALUE + 32;

    public static int index(int x, int y, int z) {
        return blockToLocal(x) | blockToLocal(z) << 4 | blockToLocal(y) << 8;
    }

    public static int index(int x, int z) {
        return blockToLocal(x) | blockToLocal(z) << 4;
    }

    public static int blockToLocal(int val) {
        return val & 0xf;
    }

    public static int blockToCube(int val) {
        return val >> 4;
    }

    public static int blockCeilToCube(int val) {
        return -((-val) >> 4);
    }
    
    public static int blockToBiome(int val) {
        return (val & 14) >> 1;
    }

    public static int localToBlock(int cubeVal, int localVal) {
        return cubeToMinBlock(cubeVal) + localVal;
    }

    public static int cubeToMinBlock(int val) {
        return val << 4;
    }

    public static int cubeToMaxBlock(int val) {
        return cubeToMinBlock(val) + 15;
    }

    public static int cubeToCenterBlock(int cubeVal) {
        return localToBlock(cubeVal, 16/ 2);
    }

    /**
     * Returns the minimum coordinate inside the population area this coordinate is in
     *
     * @param coord the coordinate
     * @return the minimum coordinate for population area
     */
    public static int getMinCubePopulationPos(int coord) {
        return localToBlock(blockToCube(coord), 16 / 2);
    }

    /**
     * Return a seed for random number generation, based on initial seed and 3 coordinates.
     *
     * @param seed the world seed
     * @param x the x coordinate
     * @param y the y coordinate
     * @param z the z coordinate
     * @return A seed value based on world seed, x, y and z coordinates
     */
    public static long coordsSeedHash(long seed, int x, int y, int z) {
        long hash = 3;
        hash = 41 * hash + seed;
        hash = 41 * hash + x;
        hash = 41 * hash + y;
        return 41 * hash + z;
    }

    public static Random coordsSeedRandom(long seed, int x, int y, int z) {
        return new Random(coordsSeedHash(seed, x, y, z));
    }
}
