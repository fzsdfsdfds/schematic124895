package com.example.schematicbuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * .litematic reader. Keeps full block states (with properties such as facing/half/axis).
 * Coordinates are relative to the lowest corner of the schematic.
 */
public class Schematic {
    public record Entry(int x, int y, int z, BlockState state) {}

    /** face order used everywhere: 0=-Y 1=+Y 2=-Z 3=+Z 4=-X 5=+X */
    public static final int[][] FACES = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    public final List<Entry> entries = new ArrayList<>();
    /** indices of entries that have at least one exposed face (used for the ghost preview) */
    public int[] surface = new int[0];
    /** per entry: bit i set => face i is exposed */
    public byte[] faceMask = new byte[0];
    public int skipped = 0;

    private static long key(int x, int y, int z) {
        return ((long) (x + 1) << 42) | ((long) (y + 1) << 21) | (long) (z + 1);
    }

    private static <T extends Comparable<T>> BlockState applyProp(BlockState s, Property<T> p, String v) {
        return p.getValue(v).map(val -> s.setValue(p, val)).orElse(s);
    }

    public static Schematic load(Path path) throws IOException {
        CompoundTag root;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }

        Schematic result = new Schematic();
        CompoundTag regions = root.getCompoundOrEmpty("Regions");
        if (regions.keySet().isEmpty()) throw new IOException("To nie wyglada na plik .litematic (brak 'Regions').");

        List<Entry> raw = new ArrayList<>();

        for (String regionName : regions.keySet()) {
            CompoundTag region = regions.getCompoundOrEmpty(regionName);
            CompoundTag pos = region.getCompoundOrEmpty("Position");
            CompoundTag size = region.getCompoundOrEmpty("Size");

            int px = pos.getIntOr("x", 0), py = pos.getIntOr("y", 0), pz = pos.getIntOr("z", 0);
            int sx = size.getIntOr("x", 0), sy = size.getIntOr("y", 0), sz = size.getIntOr("z", 0);
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            if (ax == 0 || ay == 0 || az == 0) continue;

            int startX = sx < 0 ? px + sx + 1 : px;
            int startY = sy < 0 ? py + sy + 1 : py;
            int startZ = sz < 0 ? pz + sz + 1 : pz;

            ListTag paletteNbt = region.getListOrEmpty("BlockStatePalette");
            int paletteSize = paletteNbt.size();
            if (paletteSize == 0) continue;

            BlockState[] palette = new BlockState[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                CompoundTag c = paletteNbt.getCompoundOrEmpty(i);
                String name = c.getStringOr("Name", "minecraft:air");
                Identifier id = Identifier.tryParse(name);
                Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(id);
                BlockState state = block.defaultBlockState();

                CompoundTag props = c.getCompoundOrEmpty("Properties");
                for (String propName : props.keySet()) {
                    Property<?> property = block.getStateDefinition().getProperty(propName);
                    if (property != null) state = applyProp(state, property, props.getStringOr(propName, ""));
                }
                palette[i] = state;
            }

            long[] states = region.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            long volume = (long) ax * ay * az;
            if (volume > 60_000_000L) throw new IOException("Schemat jest zbyt duzy.");
            if ((volume * bits + 63) / 64 > states.length) throw new IOException("Uszkodzony plik (za malo danych BlockStates).");

            long mask = (1L << bits) - 1L;

            for (int i = 0; i < (int) volume; i++) {
                long startBit = (long) i * bits;
                int startArr = (int) (startBit >>> 6);
                int endArr = (int) ((startBit + bits - 1) >>> 6);
                int startOffset = (int) (startBit & 63L);

                int idx;
                if (startArr == endArr) {
                    idx = (int) ((states[startArr] >>> startOffset) & mask);
                } else {
                    int endOffset = 64 - startOffset;
                    idx = (int) (((states[startArr] >>> startOffset) | (states[endArr] << endOffset)) & mask);
                }
                if (idx >= paletteSize) continue;

                BlockState state = palette[idx];
                if (state.isAir()) continue;
                if (state.getBlock().asItem() == Items.AIR) {
                    result.skipped++;
                    continue;
                }

                int lx = i % ax;
                int lz = (i / ax) % az;
                int ly = i / (ax * az);
                raw.add(new Entry(startX + lx, startY + ly, startZ + lz, state));
            }
        }

        if (raw.isEmpty()) return result;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (Entry e : raw) {
            minX = Math.min(minX, e.x());
            minY = Math.min(minY, e.y());
            minZ = Math.min(minZ, e.z());
        }
        for (Entry e : raw) result.entries.add(new Entry(e.x() - minX, e.y() - minY, e.z() - minZ, e.state()));

        result.entries.sort(Comparator.comparingInt(Entry::y).thenComparingInt(Entry::x).thenComparingInt(Entry::z));
        result.buildSurface();
        return result;
    }

    private void buildSurface() {
        int n = entries.size();
        Set<Long> occupied = new HashSet<>(n * 2);
        for (Entry e : entries) occupied.add(key(e.x(), e.y(), e.z()));

        faceMask = new byte[n];
        int[] tmp = new int[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            int m = 0;
            for (int f = 0; f < 6; f++) {
                if (!occupied.contains(key(e.x() + FACES[f][0], e.y() + FACES[f][1], e.z() + FACES[f][2]))) m |= (1 << f);
            }
            faceMask[i] = (byte) m;
            if (m != 0) tmp[count++] = i;
        }
        surface = java.util.Arrays.copyOf(tmp, count);
    }
}
