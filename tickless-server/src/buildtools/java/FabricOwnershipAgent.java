import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/** Final-definition ownership pass: receives the bytes Knot has already mixed. */
public final class FabricOwnershipAgent {
    private static final List<String> SITES = new ArrayList<>();
    public static final int FIRST_SITE = 1_000_000;
    private static long classes;
    private static final ThreadLocal<Integer> INFRASTRUCTURE_DEPTH = ThreadLocal.withInitial(() -> 0);
    public static void enterInfrastructure() { INFRASTRUCTURE_DEPTH.set(INFRASTRUCTURE_DEPTH.get() + 1); }
    public static void exitInfrastructure() { INFRASTRUCTURE_DEPTH.set(INFRASTRUCTURE_DEPTH.get() - 1); }
    public static boolean isInfrastructure() { return INFRASTRUCTURE_DEPTH.get() != 0; }

    public static synchronized String describe(int site) {
        return SITES.get(site - FIRST_SITE);
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        System.setProperty("tickless.fabric", "true");
        System.setProperty("tickless.fabric.agent", "true");
        if (!Boolean.getBoolean("tickless.fabric.checks")) {
            try { FabricConflictAudit.initialize(); }
            catch (Exception failure) { throw new IllegalStateException("Cannot initialize Tickless Fabric conflict audit", failure); }
        }
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefining,
                    ProtectionDomain domain, byte[] bytes) {
                if (name != null && name.equals("net/fabricmc/loader/impl/launch/knot/KnotClassDelegate")) {
                    var cf = java.lang.classfile.ClassFile.of();
                    return cf.transformClass(cf.parse(bytes), java.lang.classfile.ClassTransform.transformingMethodBodies(
                        method -> method.methodName().equalsString("getPostMixinClassByteArray"),
                        new InfrastructureScope()));
                }
                if (name != null && name.equals("org/spongepowered/asm/mixin/injection/struct/InjectionInfo")) {
                    var cf = java.lang.classfile.ClassFile.of();
                    return cf.transformClass(cf.parse(bytes), java.lang.classfile.ClassTransform.transformingMethodBodies(
                        method -> method.methodName().equalsString("postInject"),
                        new java.lang.classfile.CodeTransform() {
                            @Override public void atStart(java.lang.classfile.CodeBuilder builder) {
                            builder.aload(0);
                            builder.invokestatic(java.lang.constant.ClassDesc.of("FabricConflictAudit"), "checkInjection",
                                java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void, java.lang.constant.ConstantDescs.CD_Object));
                            }
                            @Override public void accept(java.lang.classfile.CodeBuilder builder, java.lang.classfile.CodeElement element) { builder.with(element); }
                        }));
                }
                if (loader == null || name == null || !(loader.getClass().getName().contains("Knot") || Boolean.getBoolean("tickless.fabric.checks"))) return null;
                boolean game = name.startsWith("net/minecraft/");
                String origin = domain == null || domain.getCodeSource() == null ? "" : domain.getCodeSource().getLocation().toString();
                boolean mod = origin.contains("/mods/") || origin.contains("/processedMods/");
                boolean api = name.startsWith("net/fabricmc/") || name.startsWith("com/llamalad7/mixinextras/")
                    || name.startsWith("org/spongepowered/asm/");
                if (!game && (!mod || api)) return null;
                synchronized (FabricOwnershipAgent.class) {
                    try {
                        if (game) FabricConflictAudit.checkClass(name, bytes);
                        byte[] result = new OwnershipTransform().transformRuntime(name, bytes, loader,
                            FIRST_SITE + SITES.size(), SITES::addAll, mod && !api);
                        if (result != null) classes++;
                        return result;
                    } catch (Throwable failure) {
                        // Instrumentation ignores transformer exceptions. Invalid bytes ensure
                        // definition fails instead of running the class without ownership hooks.
                        System.err.println("Tickless Fabric: refusing unsafe class " + name + " from " + origin);
                        failure.printStackTrace(System.err);
                        return new byte[] {0};
                    }
                }
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (FabricOwnershipAgent.class) {
                System.err.println("Tickless Fabric ownership: " + classes + " classes, " + SITES.size() + " sites transformed after Mixin");
            }
        }, "Tickless Fabric transform summary"));
        System.err.println("Tickless Fabric: post-Mixin ownership agent active");
    }
    /** Mixin plugins run inside class loading, not as world callbacks. Never unwind a linker with a transaction abort. */
    private static final class InfrastructureScope implements java.lang.classfile.CodeTransform {
        private java.lang.classfile.Label start;
        private static void call(java.lang.classfile.CodeBuilder builder, String method) {
            builder.invokestatic(java.lang.constant.ClassDesc.of("FabricOwnershipAgent"), method,
                java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void));
        }
        @Override public void atStart(java.lang.classfile.CodeBuilder builder) {
            call(builder, "enterInfrastructure");
            start = builder.newBoundLabel();
        }
        @Override public void accept(java.lang.classfile.CodeBuilder builder, java.lang.classfile.CodeElement element) {
            if (element instanceof java.lang.classfile.instruction.ReturnInstruction) call(builder, "exitInfrastructure");
            builder.with(element);
        }
        @Override public void atEnd(java.lang.classfile.CodeBuilder builder) {
            var end = builder.newBoundLabel();
            var handler = builder.newBoundLabel();
            call(builder, "exitInfrastructure");
            builder.athrow();
            builder.exceptionCatchAll(start, end, handler);
        }
    }

}
