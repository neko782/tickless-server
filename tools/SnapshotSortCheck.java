import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.world.level.parallel.SnapshotSort;

/** Compare with stable float-key sorting, including the radix threshold and changing keys. */
public class SnapshotSortCheck {
    record Item(int id, double key) {}
    static volatile long sink;

    static void reference(List<Item> list) {
        Object[] items = list.toArray();
        long[] packed = new long[items.length];
        for (int i = 0; i < items.length; i++) {
            int bits = Float.floatToIntBits((float)((Item)items[i]).key);
            bits ^= (bits >> 31) & 0x7FFFFFFF;
            packed[i] = (long)bits << 32 | i;
        }
        Arrays.sort(packed);
        for (int i = 0; i < items.length; i++) list.set(i, (Item)items[(int)packed[i]]);
    }

    public static void main(String[] args) {
        Random random = new Random(42);
        for (int size : new int[] {0, 1, 2, 31, 511, 512, 513, 2048, 10000}) {
            for (int round = 0; round < 20; round++) {
                List<Item> actual = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    double key = switch (i % 13) {
                        case 0 -> Double.NaN;
                        case 1 -> Double.NEGATIVE_INFINITY;
                        case 2 -> Double.POSITIVE_INFINITY;
                        case 3 -> -0.0;
                        case 4 -> 0.0;
                        case 5 -> 1.0 + random.nextDouble() * 1e-9;
                        default -> Float.intBitsToFloat(random.nextInt());
                    };
                    actual.add(new Item(i, key));
                }
                List<Item> expected = new ArrayList<>(actual);
                expected.sort((a, b) -> Float.compare((float)a.key, (float)b.key));
                for (int filterMode = 0; filterMode < 3; filterMode++) {
                    final int mode = filterMode;
                    var filter = (java.util.function.Predicate<Item>)(item -> mode == 0 || mode == 1 && item.id % 3 == 0);
                    Item first = expected.stream().filter(filter).findFirst().orElse(null);
                    int[] keyReads = new int[size], filterReads = new int[size];
                    Item found = SnapshotSort.firstByKey(actual, item -> {
                        if (++filterReads[item.id] != 1) throw new AssertionError("Filter read more than once");
                        return filter.test(item);
                    }, item -> {
                        if (++keyReads[item.id] != 1) throw new AssertionError("Key read more than once");
                        return item.key;
                    });
                    if (found != first) throw new AssertionError("First differs at size " + size);
                    for (Item item : actual) {
                        if (filterReads[item.id] != 1 || keyReads[item.id] != (filter.test(item) ? 1 : 0)) {
                            throw new AssertionError("Filter/key evaluation differs");
                        }
                    }
                }
                int[] calls = new int[size];
                SnapshotSort.sortByKey(actual, item -> {
                    // A repeated key read would see a different value and invalidate the sort.
                    return calls[item.id]++ == 0 ? item.key : -item.key;
                });
                if (!actual.equals(expected)) throw new AssertionError("Order differs at size " + size);
                if (size > 1) for (int count : calls) if (count != 1) throw new AssertionError("Key read more than once");
            }
        }
        System.out.println("SnapshotSortCheck passed: stable float order and first-match selection, filtering, signed zeros, infinities, NaNs, threshold and single key reads");
        if (args.length > 0 && args[0].equals("bench")) {
            for (int size : new int[] {512, 2048, 8192}) {
                List<Item> source = new ArrayList<>();
                for (int i = 0; i < size; i++) source.add(new Item(i, random.nextDouble() * 1000));
                for (int round = 0; round < 8; round++) {
                    for (boolean radix : new boolean[] {false, true}) {
                        long start = System.nanoTime(), checksum = 0;
                        for (int repeat = 0; repeat < 200; repeat++) {
                            List<Item> list = new ArrayList<>(source);
                            if (radix) SnapshotSort.sortByKey(list, Item::key); else reference(list);
                            for (int i = 0; i < size; i++) checksum += (long)i * list.get(i).id;
                        }
                        sink = checksum;
                        System.out.printf("size %d round %d %s: %.1f us/sort, checksum %d%n", size, round,
                            radix ? "current" : "reference", (System.nanoTime() - start) / 200_000.0, checksum);
                    }
                }
            }
        }
    }
}
