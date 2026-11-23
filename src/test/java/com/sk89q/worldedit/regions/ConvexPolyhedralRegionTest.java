/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.regions;

import com.sk89q.worldedit.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fresh convex selection must accept the same blocks on its first pass as on
 * every later one, and those blocks must be exactly the ones inside the hull.
 */
public class ConvexPolyhedralRegionTest {

    private interface Inside {
        boolean test(int x, int y, int z);
    }

    private static ConvexPolyhedralRegion region(Vector... points) {
        ConvexPolyhedralRegion region = new ConvexPolyhedralRegion((com.sk89q.worldedit.world.World) null);
        for (Vector point : points) {
            region.addVertex(point);
        }
        assertTrue(region.isDefined());
        return region;
    }

    private static int countContained(ConvexPolyhedralRegion region) {
        Vector min = region.getMinimumPoint();
        Vector max = region.getMaximumPoint();
        int count = 0;
        for (int x = min.getBlockX() - 1; x <= max.getBlockX() + 1; x++) {
            for (int y = min.getBlockY() - 1; y <= max.getBlockY() + 1; y++) {
                for (int z = min.getBlockZ() - 1; z <= max.getBlockZ() + 1; z++) {
                    if (region.contains(new Vector(x, y, z))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static int countDirect(int from, int to, Inside inside) {
        int count = 0;
        for (int x = from; x <= to; x++) {
            for (int y = from; y <= to; y++) {
                for (int z = from; z <= to; z++) {
                    if (inside.test(x, y, z)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static void assertStableAndExact(ConvexPolyhedralRegion region, int expected, Inside inside) {
        int first = countContained(region);
        int second = countContained(region);
        assertEquals(second, first, "first pass over a fresh region differs from the second");

        StringBuilder wrong = new StringBuilder();
        Vector min = region.getMinimumPoint();
        Vector max = region.getMaximumPoint();
        for (int x = min.getBlockX() - 1; x <= max.getBlockX() + 1; x++) {
            for (int y = min.getBlockY() - 1; y <= max.getBlockY() + 1; y++) {
                for (int z = min.getBlockZ() - 1; z <= max.getBlockZ() + 1; z++) {
                    if (region.contains(new Vector(x, y, z)) != inside.test(x, y, z)) {
                        wrong.append(" (").append(x).append(',').append(y).append(',').append(z).append(')');
                    }
                }
            }
        }
        assertEquals("", wrong.toString(), "blocks where contains() disagrees with the hull");
        assertEquals(expected, first, "blocks accepted differ from the blocks inside the hull");
        assertEquals(expected, countContained(new ConvexPolyhedralRegion(region)), "copy differs");
    }

    private static final Inside TETRAHEDRON = (x, y, z) -> x >= 0 && y >= 0 && z >= 0 && x + y + z <= 8;

    @Test
    public void tetrahedronFirstPassMatchesHull() {
        ConvexPolyhedralRegion region = region(
                new Vector(0, 0, 0), new Vector(8, 0, 0), new Vector(0, 0, 8), new Vector(0, 8, 0));

        int expected = countDirect(-2, 10, TETRAHEDRON);
        assertEquals(165, expected);
        assertStableAndExact(region, expected, TETRAHEDRON);
    }

    @Test
    public void tetrahedronAwayFromOriginMatchesHull() {
        // Selections in a world sit far from the origin and often at negative coordinates.
        final int ox = 1037, oy = 64, oz = -2219;
        ConvexPolyhedralRegion region = region(
                new Vector(ox, oy, oz), new Vector(ox + 8, oy, oz),
                new Vector(ox, oy, oz + 8), new Vector(ox, oy + 8, oz));

        Inside inside = (x, y, z) -> TETRAHEDRON.test(x - ox, y - oy, z - oz);
        assertStableAndExact(region, 165, inside);
    }

    @Test
    public void octahedronFirstPassMatchesHull() {
        ConvexPolyhedralRegion region = region(
                new Vector(6, 0, 0), new Vector(-6, 0, 0), new Vector(0, 6, 0),
                new Vector(0, -6, 0), new Vector(0, 0, 6), new Vector(0, 0, -6));

        Inside inside = (x, y, z) -> Math.abs(x) + Math.abs(y) + Math.abs(z) <= 6;
        assertStableAndExact(region, countDirect(-8, 8, inside), inside);
    }

    @Test
    public void pyramidBuiltThroughBacklogMatchesHull() {
        // The fourth point is coplanar with the first three, so it waits in the
        // backlog until the apex gives the hull some volume.
        ConvexPolyhedralRegion region = region(
                new Vector(0, 0, 0), new Vector(8, 0, 0), new Vector(0, 0, 8),
                new Vector(8, 0, 8), new Vector(4, 8, 4));

        Inside inside = (x, y, z) ->
                y >= 0 && y <= 2 * x && y <= 2 * (8 - x) && y <= 2 * z && y <= 2 * (8 - z);
        assertStableAndExact(region, countDirect(-2, 10, inside), inside);
    }
}
