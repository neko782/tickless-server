package net.minecraft.world.level.parallel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Sorting by keys that other world workers may change while the sort runs, such as the
 * distance to an entity that is moving on another thread. Each key is read once, so the
 * comparison stays consistent and the sort cannot fail its contract check.
 */
public final class SnapshotSort {
    private SnapshotSort() {
    }

    /** First entry of a stable float-key sort, without allocating or sorting a list. */
    public static <T> @Nullable T firstByKey(
        final Iterable<T> items, final Predicate<? super T> filter, final ToDoubleFunction<? super T> key
    ) {
        T first = null;
        float best = 0;
        for (T item : items) {
            if (filter.test(item)) {
                float value = (float)key.applyAsDouble(item);
                if (first == null || Float.compare(value, best) < 0) {
                    first = item;
                    best = value;
                }
            }
        }
        return first;
    }

    public static <T> void sortByKey(final List<T> list, final ToDoubleFunction<? super T> key) {
        int size = list.size();
        if (size < 2) {
            return;
        }

        // Pack each key, rounded to a float, above its index and sort the primitives. Keys that
        // round to the same float keep their list order, which only reorders near-equal distances.
        Object[] items = list.toArray();
        long[] packed = new long[size];
        for (int i = 0; i < size; i++) {
            int bits = Float.floatToIntBits((float)key.applyAsDouble((T)items[i]));
            bits ^= (bits >> 31) & 0x7FFFFFFF;
            packed[i] = (long)bits << 32 | i;
        }

        if (size < 512) {
            Arrays.sort(packed);
        } else {
            sortKeys(packed);
        }
        for (int i = 0; i < size; i++) {
            list.set(i, (T)items[(int)packed[i]]);
        }
    }

    /** Stable radix sort of the float key; equal keys retain their original indices. */
    private static void sortKeys(final long[] packed) {
        long[] scratch = new long[packed.length];
        int[] offsets = new int[256];
        long[] from = packed, to = scratch;
        for (int shift = 32; shift <= 56; shift += 8) {
            Arrays.fill(offsets, 0);
            int sign = shift == 56 ? 128 : 0;
            for (long value : from) {
                offsets[((int)(value >>> shift) & 255) ^ sign]++;
            }
            int start = 0;
            for (int i = 0; i < offsets.length; i++) {
                int count = offsets[i];
                offsets[i] = start;
                start += count;
            }
            for (long value : from) {
                to[offsets[((int)(value >>> shift) & 255) ^ sign]++] = value;
            }
            long[] swap = from;
            from = to;
            to = swap;
        }
        // Four passes finish in the original array.
    }

    public static <T> Stream<T> sortedByKey(final Stream<T> stream, final ToDoubleFunction<? super T> key) {
        List<T> list = new ArrayList<>(stream.toList());
        sortByKey(list, key);
        return list.stream();
    }
}
