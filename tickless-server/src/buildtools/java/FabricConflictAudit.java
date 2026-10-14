import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/** Refuse mixins which erase scheduler/ownership operations or silently miss patched classes. */
public final class FabricConflictAudit {
    private static final Map<String, Map<String, Integer>> BASELINE = new HashMap<>();
    private static final Map<String, java.util.Set<String>> ORIGINAL_METHODS = new HashMap<>();

    static void initialize() throws Exception {
        try (ZipFile game = new ZipFile(Path.of(System.getProperty("fabric.gameJarPath")).toFile())) {
            var manifest = game.getEntry("tickless-patched-classes.txt");
            if (manifest == null) throw new IllegalStateException("Missing patched-class conflict manifest");
            var patched = new java.util.HashSet<>(java.util.Arrays.asList(
                new String(game.getInputStream(manifest).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")));
            var entries = game.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || !entry.getName().startsWith("net/minecraft/")) continue;
                var model = ClassFile.of().parse(game.getInputStream(entry).readAllBytes());
                String name = model.thisClass().asInternalName();
                String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
                if (patched.contains(outer) || name.startsWith("net/minecraft/world/level/parallel/")) {
                    var methods = new java.util.HashSet<String>();
                    for (var method : model.methods()) methods.add(method.methodName().stringValue() + method.methodType().stringValue());
                    ORIGINAL_METHODS.put(name, methods);
                }
                var markers = markers(model);
                if (!markers.isEmpty()) BASELINE.put(model.thisClass().asInternalName(), markers);
            }
        }
    }

    private static Map<String, Integer> markers(ClassModel model) {
        Map<String, Integer> markers = new HashMap<>();
        for (MethodModel method : model.methods()) {
            if (method.code().isEmpty()) continue;
            for (var instruction : method.code().get()) {
                String owner = null, member = null;
                if (instruction instanceof InvokeInstruction invoke) {
                    owner = invoke.owner().asInternalName();
                    member = invoke.name().stringValue() + invoke.typeSymbol().descriptorString();
                } else if (instruction instanceof FieldInstruction field) {
                    owner = field.owner().asInternalName();
                    member = field.name().stringValue() + field.typeSymbol().descriptorString();
                    if (field.name().stringValue().equals("worldLock")) {
                        markers.merge(owner + "." + member, 1, Integer::sum);
                    }
                }
                if (owner != null && (owner.startsWith("net/minecraft/world/level/parallel/")
                    || owner.equals("net/minecraft/server/level/LevelEventScheduler")
                    || owner.equals("net/minecraft/server/level/PhaseWheel"))) {
                    markers.merge(owner + "." + member, 1, Integer::sum);
                }
            }
        }
        return markers;
    }

    static void checkClass(String name, byte[] bytes) {
        ClassModel model = ClassFile.of().parse(bytes);
        var originals = ORIGINAL_METHODS.get(name);
        if (originals != null) {
            for (var method : model.methods()) {
                if (!originals.contains(method.methodName().stringValue() + method.methodType().stringValue())) continue;
                var annotations = method.findAttribute(Attributes.runtimeVisibleAnnotations()).orElse(null);
                if (annotations != null && annotations.annotations().stream().anyMatch(a ->
                    a.className().equalsString("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;"))) {
                    throw new IllegalStateException("Tickless Fabric mixin conflict: overwritten method " + name + "."
                        + method.methodName().stringValue() + method.methodType().stringValue()
                        + " belongs to a Tickless-patched class; remove the conflicting overwrite mod.");
                }
            }
        }
        Map<String, Integer> baseline = BASELINE.get(name);
        if (baseline == null) return;
        Map<String, Integer> actual = markers(model);
        for (var marker : baseline.entrySet()) {
            if (actual.getOrDefault(marker.getKey(), 0) < marker.getValue()) {
                throw new IllegalStateException("Tickless Fabric mixin conflict in " + name
                    + ": a mixin erased Tickless operation " + marker.getKey()
                    + ". Remove the conflicting optimization/overwrite mod; refusing to disable parallel simulation or ownership.");
            }
        }
    }

    public static void checkInjection(Object info) {
        try {
            int count = (int)info.getClass().getMethod("getInjectedCallbackCount").invoke(info);
            if (count != 0) return;
            Object node = info.getClass().getMethod("getTargetClassNode").invoke(info);
            String name = (String)node.getClass().getField("name").get(node);
            if (BASELINE.containsKey(name) || ORIGINAL_METHODS.containsKey(name)) {
                throw new IllegalStateException("Tickless Fabric mixin conflict: " + info
                    + " injected zero callbacks into patched " + name
                    + ". Optional injections may not silently disable mod behavior on Tickless.");
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot audit Fabric injection " + info, failure);
        }
    }
}
