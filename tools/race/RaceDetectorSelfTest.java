import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.level.parallel.RaceCheck;

/**
 * Shows that the race check finds races and stays quiet without them. The {@link Fixture} class
 * is rewritten with the real build pass ({@code RaceTransform}) and loaded, then driven from two
 * threads:
 * <ul>
 * <li>two threads adding at once: reported;
 * <li>a thread adding while another is inside a for-each over the same list: reported;
 * <li>a thread adding after another's for-each returned early, after another thread's adds
 * finished (a hand-off), or under a common monitor: not reported.
 * </ul>
 * Run with {@code -Dtickless.raceCheck=true}, with RaceTransform and the race check server on the
 * class path (tools/stress/races.sh does this).
 */
public class RaceDetectorSelfTest {
    /** Rewritten by RaceTransform before it is loaded; never used directly. */
    public static final class Fixture {
        private final List<Integer> list = new ArrayList<>();

        public void add(final int value) {
            this.list.add(value);
        }

        public void iterate(final Runnable inside) {
            for (Integer value : this.list) {
                inside.run();
            }
        }

        public Integer first() {
            for (Integer value : this.list) {
                return value;
            }

            return null;
        }
    }

    /** Loads the rewritten Fixture ahead of the application class loader. */
    private static final class RewritingLoader extends ClassLoader {
        private final byte[] bytes;

        RewritingLoader(final byte[] bytes) {
            super(RaceDetectorSelfTest.class.getClassLoader());
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.equals(Fixture.class.getName())) {
                synchronized (this.getClassLoadingLock(name)) {
                    Class<?> loaded = this.findLoadedClass(name);
                    return loaded != null ? loaded : this.defineClass(name, this.bytes, 0, this.bytes.length);
                }
            }

            return super.loadClass(name, resolve);
        }
    }

    private static Class<?> fixtureClass;
    private static int failures;

    public static void main(final String[] args) throws Exception {
        if (!RaceCheck.ENABLED) {
            throw new IllegalStateException("run with -Dtickless.raceCheck=true");
        }

        byte[] original;
        try (var in = RaceDetectorSelfTest.class.getResourceAsStream("RaceDetectorSelfTest$Fixture.class")) {
            original = in.readAllBytes();
        }

        ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
            ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(RaceDetectorSelfTest.class.getClassLoader()))
        ));
        List<String> sites = new ArrayList<>();
        byte[] rewritten = RaceTransform.transform(classFile.parse(original), classFile, sites);
        if (rewritten == null || sites.isEmpty()) {
            throw new IllegalStateException("RaceTransform did not hook the fixture");
        }

        fixtureClass = new RewritingLoader(rewritten).loadClass(Fixture.class.getName());
        System.out.println("fixture hooked at " + sites.size() + " call sites");

        expect("concurrent adds", true, () -> {
            Object fixture = fresh();
            Thread[] threads = new Thread[2];
            for (int t = 0; t < threads.length; t++) {
                threads[t] = Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 200_000; i++) {
                        try {
                            add(fixture, i);
                        } catch (RuntimeException e) {
                            // An unsynchronized ArrayList can throw when its adds overlap.
                        }
                    }
                });
            }

            for (Thread thread : threads) {
                thread.join();
            }
        });

        expect("add during another thread's for-each", true, () -> {
            Object fixture = fresh();
            add(fixture, 1);
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread iterating = Thread.ofPlatform().start(() -> {
                try {
                    iterate(fixture, () -> {
                        inside.countDown();
                        await(release);
                    });
                } catch (java.util.ConcurrentModificationException e) {
                    // The race itself: the list changed under the iteration.
                }
            });
            await(inside);
            Thread adding = Thread.ofPlatform().start(() -> add(fixture, 2));
            adding.join();
            release.countDown();
            iterating.join();
        });

        expect("add after another thread's for-each returned early", false, () -> {
            Object fixture = fresh();
            add(fixture, 1);
            Thread reading = Thread.ofPlatform().start(() -> first(fixture));
            reading.join();
            Thread adding = Thread.ofPlatform().start(() -> add(fixture, 2));
            adding.join();
        });

        expect("adds handed from one thread to the next", false, () -> {
            Object fixture = fresh();
            for (int t = 0; t < 4; t++) {
                Thread thread = Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 10_000; i++) {
                        add(fixture, i);
                    }
                });
                thread.join();
            }
        });

        expect("adds under a common monitor", false, () -> {
            Object fixture = fresh();
            Object lock = new Object();
            Thread[] threads = new Thread[2];
            for (int t = 0; t < threads.length; t++) {
                threads[t] = Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 50_000; i++) {
                        synchronized (lock) {
                            add(fixture, i);
                        }
                    }
                });
            }

            for (Thread thread : threads) {
                thread.join();
            }
        });

        RaceCheck.logSummary();
        if (failures != 0) {
            System.out.println("FAIL: " + failures + " expectation(s) not met");
            System.exit(1);
        }

        System.out.println("PASS");
    }

    private interface Scenario {
        void run() throws Exception;
    }

    private static void expect(final String name, final boolean race, final Scenario scenario) throws Exception {
        long before = RaceCheck.racesSeen();
        scenario.run();
        long seen = RaceCheck.racesSeen() - before;
        boolean ok = race ? seen > 0 : seen == 0;
        System.out.println((ok ? "ok   " : "FAIL ") + name + ": " + seen + " racing accesses, expected " + (race ? "some" : "none"));
        if (!ok) {
            failures++;
        }
    }

    private static Object fresh() throws ReflectiveOperationException {
        return fixtureClass.getConstructor().newInstance();
    }

    private static void add(final Object fixture, final int value) {
        call(fixture, "add", new Class<?>[] {int.class}, value);
    }

    private static void iterate(final Object fixture, final Runnable inside) {
        call(fixture, "iterate", new Class<?>[] {Runnable.class}, inside);
    }

    private static void first(final Object fixture) {
        call(fixture, "first", new Class<?>[0]);
    }

    private static void call(final Object fixture, final String method, final Class<?>[] types, final Object... args) {
        try {
            fixtureClass.getMethod(method, types).invoke(fixture, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }

            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(10L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
