package net.minecraft.world.level.parallel;

/** The agent lives in the system loader, while world code lives in Knot's game loader. */
final class RuntimeOwnershipSites {
    private static final Class<?> AGENT = agent();
    private static final java.lang.invoke.MethodHandle DESCRIBE = find("describe", String.class, int.class);
    private static final java.lang.invoke.MethodHandle INFRASTRUCTURE = find("isInfrastructure", boolean.class);
    private static Class<?> agent() {
        try {
            return Class.forName("FabricOwnershipAgent", false, ClassLoader.getSystemClassLoader());
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private static java.lang.invoke.MethodHandle find(String name, Class<?> result, Class<?>... arguments) {
        try {
            return java.lang.invoke.MethodHandles.publicLookup().findStatic(AGENT, name,
                java.lang.invoke.MethodType.methodType(result, arguments));
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    static boolean isInfrastructure() {
        try { return (boolean)INFRASTRUCTURE.invokeExact(); }
        catch (Throwable e) { throw new IllegalStateException("Cannot inspect Fabric loading scope", e); }
    }
    static String describe(int site) {
        try { return (String)DESCRIBE.invokeExact(site); }
        catch (Throwable e) { throw new IllegalStateException("Cannot resolve post-Mixin ownership site " + site, e); }
    }
}
