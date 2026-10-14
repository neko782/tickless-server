package net.minecraft.world.level.parallel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Restores a field from the undo log. The build names every logged write site in
 * {@link WorldOwnership#SITES_RESOURCE} with the field it writes; a site's handle is looked up the
 * first time a rollback needs it.
 */
final class UndoFields {
    private static volatile String @org.jspecify.annotations.Nullable [] sites;
    private static final ConcurrentHashMap<String, VarHandle> HANDLES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, VarHandle> BY_SITE = new ConcurrentHashMap<>();

    private UndoFields() {
    }

    static void restore(final Object target, final int site, final long prim, final Object ref) {
        VarHandle handle = BY_SITE.get(site);
        if (handle == null) {
            handle = lookup(site);
            BY_SITE.put(site, handle);
        }

        Class<?> type = handle.varType();
        if (!type.isPrimitive()) {
            handle.set(target, ref);
        } else if (type == int.class) {
            handle.set(target, (int)prim);
        } else if (type == long.class) {
            handle.set(target, prim);
        } else if (type == float.class) {
            handle.set(target, Float.intBitsToFloat((int)prim));
        } else if (type == double.class) {
            handle.set(target, Double.longBitsToDouble(prim));
        } else if (type == boolean.class) {
            handle.set(target, prim != 0L);
        } else if (type == byte.class) {
            handle.set(target, (byte)prim);
        } else if (type == short.class) {
            handle.set(target, (short)prim);
        } else {
            handle.set(target, (char)prim);
        }
    }

    private static VarHandle lookup(final int site) {
        String[] names = sites;
        if (names == null) {
            names = load();
            sites = names;
        }

        if (site < 0 || site >= names.length) {
            throw new IllegalStateException("Unknown write site " + site);
        }

        // "description<TAB>owner<TAB>name<TAB>descriptor"
        String[] parts = names[site].split("\t");
        if (parts.length < 4) {
            throw new IllegalStateException("Write site " + site + " names no field: " + names[site]);
        }

        return HANDLES.computeIfAbsent(parts[1] + "." + parts[2], key -> {
            try {
                Class<?> owner = Class.forName(parts[1].replace('/', '.'), false, UndoFields.class.getClassLoader());
                MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(owner, MethodHandles.lookup());
                for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                    try {
                        java.lang.reflect.Field field = c.getDeclaredField(parts[2]);
                        return MethodHandles.privateLookupIn(c, lookup).unreflectVarHandle(field);
                    } catch (NoSuchFieldException e) {
                        // Declared higher up.
                    }
                }

                throw new NoSuchFieldException(key);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot restore " + key, e);
            }
        });
    }

    private static String[] load() {
        List<String> names = new ArrayList<>();
        try (InputStream in = UndoFields.class.getClassLoader().getResourceAsStream(WorldOwnership.SITES_RESOURCE)) {
            if (in != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    names.add(line);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + WorldOwnership.SITES_RESOURCE, e);
        }

        return names.toArray(String[]::new);
    }
}
