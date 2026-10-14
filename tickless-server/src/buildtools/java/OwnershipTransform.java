import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.MonitorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.Label;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Rewrites the server jar so that every field write on an entity or block entity first calls
 * {@code WorldOwnership}, which takes (or checks) the object's world lock, then logs the field's
 * old value with {@code WorldUndo} so the event can be rolled back; field writes on a few other
 * world objects (item stacks, container menus, inventories) are logged too. Every Java
 * monitor a world event enters or waits for is visible to the world deadlock resolver: hooks go
 * around each {@code monitorenter}/{@code monitorexit}, and synchronized methods become explicit
 * monitor blocks with the same hooks.
 *
 * <p>usage: OwnershipTransform &lt;jar&gt; [classpath jars...]. The jar is rewritten in place,
 * and {@code tickless-ownership-sites.txt} is added, naming each rewritten write by its index;
 * a field write's line also gives the field (tab separated owner, name and descriptor), which a
 * rollback uses to restore it.
 */
public final class OwnershipTransform {
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final String UNOWNED = "Lnet/minecraft/world/level/parallel/Unowned;";
    private static final String SKIP_PACKAGE = "net/minecraft/world/level/parallel/";
    private static final ClassDesc OWNERSHIP = ClassDesc.of("net.minecraft.world.level.parallel.WorldOwnership");
    private static final ClassDesc UNDO = ClassDesc.of("net.minecraft.world.level.parallel.WorldUndo");
    // Field writes on these (and subclasses) are logged for rollbacks but take no lock: they are
    // reached through an entity or block entity that is locked.
    private static final java.util.Set<String> LOGGED = java.util.Set.of(
        "net/minecraft/world/item/ItemStack",
        "net/minecraft/world/inventory/AbstractContainerMenu",
        "net/minecraft/world/SimpleContainer",
        "net/minecraft/world/entity/player/Inventory",
        "net/minecraft/world/food/FoodData",
        // Connection state a player's packets change (teleport ids, movement checks), so a rolled
        // back packet runs again from the same state.
        "net/minecraft/server/network/ServerCommonPacketListenerImpl"
    );
    private static final MethodTypeDesc ENTITY_HOOK = MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("net.minecraft.world.entity.Entity"), ConstantDescs.CD_int);
    private static final ClassDesc TXN = ClassDesc.of("net.minecraft.world.level.parallel.WorldTxn");
    private static final MethodTypeDesc MONITOR_HOOK = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object);
    private static final MethodTypeDesc BLOCK_ENTITY_HOOK = MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("net.minecraft.world.level.block.entity.BlockEntity"), ConstantDescs.CD_int);

    private static final MethodTypeDesc OWNED_HOOK = MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("net.minecraft.world.entity.Entity"), ConstantDescs.CD_int);
    private static final MethodTypeDesc ADOPT_HOOK = MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("net.minecraft.world.entity.Entity"), ConstantDescs.CD_Object);
    private static final ClassDesc CD_ENTITY = ClassDesc.of("net.minecraft.world.entity.Entity");
    private static final String CONTAINER = "net/minecraft/world/Container";
    // Objects an entity owns, with the field naming their owner. Every method of these (and their
    // subclasses) first locks the owner.
    private static final Map<String, String[]> OWNED = Map.of(
        "net/minecraft/world/entity/ai/Brain", new String[] {"worldOwner", "Lnet/minecraft/world/entity/Entity;"},
        "net/minecraft/world/entity/EntityEquipment", new String[] {"worldOwner", "Lnet/minecraft/world/entity/Entity;"},
        "net/minecraft/world/SimpleContainer", new String[] {"worldOwner", "Lnet/minecraft/world/entity/Entity;"},
        "net/minecraft/world/entity/player/Inventory", new String[] {"player", "Lnet/minecraft/world/entity/player/Player;"}
    );
    // Types that become owned when an entity stores one in a field (WorldOwnership.adopt).
    private static final java.util.Set<String> ADOPTED = java.util.Set.of(
        "Lnet/minecraft/world/entity/ai/Brain;", "Lnet/minecraft/world/entity/EntityEquipment;", "Lnet/minecraft/world/SimpleContainer;"
    );
    // Container methods that hand out or change contents; on an entity or block entity they lock it.
    private static final java.util.Set<String> CONTAINER_METHODS = java.util.Set.of(
        "getItem", "setItem", "removeItem", "removeItemNoUpdate", "clearContent", "isEmpty", "getItems", "setItems"
    );

    private enum Kind { NONE, ENTITY, BLOCK_ENTITY, LOGGED }

    /** A lock taken on entry to a method: of the object itself, or of the entity owning it. */
    private record EntryHook(Kind self, String ownedBase, String ownerField, String ownerType) {
    }

    private final Map<String, ClassModel> classes = new HashMap<>();
    private final Map<String, Kind> kinds = new HashMap<>();
    private final List<String> sites = new ArrayList<>();
    private String current;

    public static void main(final String[] args) throws IOException {
        Path jar = Path.of(args[0]);
        List<URL> classpath = new ArrayList<>();
        classpath.add(jar.toUri().toURL());
        for (int i = 1; i < args.length; i++) {
            classpath.add(Path.of(args[i]).toUri().toURL());
        }

        new OwnershipTransform().run(jar, classpath);
    }

    private void run(final Path jar, final List<URL> classpath) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> e = zip.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }

        ClassFile reader = ClassFile.of();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (entry.getKey().endsWith(".class")) {
                ClassModel model = reader.parse(entry.getValue());
                this.classes.put(model.thisClass().asInternalName(), model);
            }
        }

        URLClassLoader loader = new URLClassLoader(classpath.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        ClassFile writer = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
            ClassHierarchyResolver.defaultResolver().orElse(ClassHierarchyResolver.ofResourceParsing(loader)).cached()
        ));
        int rewrittenClasses = 0;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                continue;
            }

            String name = entry.getKey().substring(0, entry.getKey().length() - ".class".length());
            ClassModel model = this.classes.get(name);
            this.current = name;
            if (name.startsWith(SKIP_PACKAGE) || model.methods().stream().noneMatch(this::needsRewrite)) {
                continue;
            }

            String simpleName = name.substring(name.lastIndexOf('/') + 1);
            ClassDesc thisClass = model.thisClass().asSymbol();
            ClassTransform transform = (classBuilder, element) -> {
                if (element instanceof MethodModel method && this.needsRewrite(method)) {
                    boolean synchronizedMethod = isSynchronized(method);
                    Rewrite rewrite = new Rewrite(simpleName, this.rewritesFields(method), synchronizedMethod ? method : null, thisClass);
                    rewrite.entryHook = this.entryHook(name, method);
                    classBuilder.transformMethod(method, (methodBuilder, methodElement) -> {
                        if (methodElement instanceof AccessFlags flags && synchronizedMethod) {
                            methodBuilder.withFlags(flags.flagsMask() & ~ClassFile.ACC_SYNCHRONIZED);
                        } else if (methodElement instanceof CodeModel code) {
                            methodBuilder.transformCode(code, rewrite);
                        } else {
                            methodBuilder.with(methodElement);
                        }
                    });
                } else {
                    classBuilder.with(element);
                }
            };
            entry.setValue(writer.transformClass(model, transform));
            rewrittenClasses++;
        }

        entries.put("tickless-ownership-sites.txt", (String.join("\n", this.sites) + "\n").getBytes(StandardCharsets.UTF_8));
        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }

        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Ownership: rewrote " + this.sites.size() + " field writes in " + rewrittenClasses + " classes");
    }

    private boolean needsRewrite(final MethodModel method) {
        CodeModel code = method.code().orElse(null);
        if (code == null) {
            return false;
        }

        if (isSynchronized(method) || this.entryHook(this.current, method) != null) {
            return true;
        }

        boolean fields = this.rewritesFields(method);
        for (CodeElement element : code) {
            if (element instanceof MonitorInstruction
                || element instanceof FieldInstruction field && (fields && this.kindOf(field) != Kind.NONE || this.adopts(field))) {
                return true;
            }
        }

        return false;
    }

    private EntryHook entryHook(final String className, final MethodModel method) {
        int flags = method.flags().flagsMask();
        String name = method.methodName().stringValue();
        if ((flags & (ClassFile.ACC_STATIC | ClassFile.ACC_ABSTRACT)) != 0 || name.startsWith("<") || method.code().isEmpty()
            || hasUnowned(method.findAttribute(Attributes.runtimeInvisibleAnnotations()).orElse(null))) {
            return null;
        }

        for (Map.Entry<String, String[]> owned : OWNED.entrySet()) {
            if (this.isSubclass(className, owned.getKey())) {
                return new EntryHook(Kind.NONE, owned.getKey(), owned.getValue()[0], owned.getValue()[1]);
            }
        }

        Kind kind = this.kind(className);
        if ((kind == Kind.ENTITY || kind == Kind.BLOCK_ENTITY) && CONTAINER_METHODS.contains(name) && this.implementsInterface(className, CONTAINER)) {
            return new EntryHook(kind, null, null, null);
        }

        return null;
    }

    /** An entity storing a brain, equipment or inventory in its own field adopts it. */
    private boolean adopts(final FieldInstruction field) {
        return field.opcode() == Opcode.PUTFIELD && ADOPTED.contains(field.typeSymbol().descriptorString()) && this.kind(field.owner().asInternalName()) == Kind.ENTITY;
    }

    private boolean isSubclass(final String name, final String base) {
        for (String c = name; c != null; ) {
            if (c.equals(base)) {
                return true;
            }

            ClassModel model = this.classes.get(c);
            c = model == null ? null : model.superclass().map(s -> s.asInternalName()).orElse(null);
        }

        return false;
    }

    private boolean implementsInterface(final String name, final String iface) {
        if (name == null) {
            return false;
        }

        if (name.equals(iface)) {
            return true;
        }

        ClassModel model = this.classes.get(name);
        if (model == null) {
            return false;
        }

        for (var i : model.interfaces()) {
            if (this.implementsInterface(i.asInternalName(), iface)) {
                return true;
            }
        }

        return this.implementsInterface(model.superclass().map(s -> s.asInternalName()).orElse(null), iface);
    }

    private static boolean isSynchronized(final MethodModel method) {
        return (method.flags().flagsMask() & ClassFile.ACC_SYNCHRONIZED) != 0 && method.code().isPresent();
    }

    /** Field writes are rewritten outside constructors and methods marked @Unowned. */
    private boolean rewritesFields(final MethodModel method) {
        String name = method.methodName().stringValue();
        return !name.equals("<init>") && !name.equals("<clinit>") && !hasUnowned(method.findAttribute(Attributes.runtimeInvisibleAnnotations()).orElse(null));
    }

    private Kind kindOf(final FieldInstruction field) {
        if (field.opcode() != Opcode.PUTFIELD) {
            return Kind.NONE;
        }

        String owner = field.owner().asInternalName();
        Kind kind = this.kind(owner);
        if (kind == Kind.NONE || this.isUnownedField(owner, field.name().stringValue())) {
            return Kind.NONE;
        }

        return kind;
    }

    private Kind kind(final String name) {
        Kind cached = this.kinds.get(name);
        if (cached != null) {
            return cached;
        }

        Kind kind;
        if (name.equals(ENTITY)) {
            kind = Kind.ENTITY;
        } else if (name.equals(BLOCK_ENTITY)) {
            kind = Kind.BLOCK_ENTITY;
        } else if (LOGGED.contains(name)) {
            kind = Kind.LOGGED;
        } else {
            ClassModel model = this.classes.get(name);
            kind = model == null || model.superclass().isEmpty() ? Kind.NONE : this.kind(model.superclass().get().asInternalName());
        }

        this.kinds.put(name, kind);
        return kind;
    }

    private boolean isUnownedField(final String owner, final String fieldName) {
        for (String name = owner; name != null; ) {
            ClassModel model = this.classes.get(name);
            if (model == null) {
                return false;
            }

            for (FieldModel field : model.fields()) {
                if (field.fieldName().equalsString(fieldName)) {
                    return hasUnowned(field.findAttribute(Attributes.runtimeInvisibleAnnotations()).orElse(null));
                }
            }

            name = model.superclass().map(c -> c.asInternalName()).orElse(null);
        }

        return false;
    }

    private static boolean hasUnowned(final RuntimeInvisibleAnnotationsAttribute annotations) {
        if (annotations != null) {
            for (Annotation annotation : annotations.annotations()) {
                if (annotation.className().equalsString(UNOWNED)) {
                    return true;
                }
            }
        }

        return false;
    }

    private final class Rewrite implements CodeTransform {
        private final String className;
        private final boolean fields;
        // For a synchronized method: the monitor is taken explicitly, with hooks, around the body.
        private final MethodModel synchronizedMethod;
        private final ClassDesc thisClass;
        private Label bodyStart;
        private int line;
        EntryHook entryHook;

        Rewrite(final String className, final boolean fields, final MethodModel synchronizedMethod, final ClassDesc thisClass) {
            this.className = className;
            this.fields = fields;
            this.synchronizedMethod = synchronizedMethod;
            this.thisClass = thisClass;
        }

        private void loadMonitor(final CodeBuilder builder) {
            if ((this.synchronizedMethod.flags().flagsMask() & ClassFile.ACC_STATIC) != 0) {
                builder.loadConstant(this.thisClass);
            } else {
                builder.aload(0);
            }
        }

        private static void enter(final CodeBuilder builder) {
            // Stack: monitor. Hooks see it before blocking and after entering.
            builder.dup().dup();
            builder.invokestatic(TXN, "beforeMonitor", MONITOR_HOOK);
            builder.monitorenter();
            builder.invokestatic(TXN, "enteredMonitor", MONITOR_HOOK);
        }

        private static void exit(final CodeBuilder builder) {
            builder.dup();
            builder.monitorexit();
            builder.invokestatic(TXN, "exitedMonitor", MONITOR_HOOK);
        }

        @Override
        public void atStart(final CodeBuilder builder) {
            // The owner's world lock comes before any monitor, never inside it.
            if (this.entryHook != null) {
                int site = OwnershipTransform.this.sites.size();
                OwnershipTransform.this.sites.add(this.className + " entry");
                builder.aload(0);
                if (this.entryHook.ownedBase() != null) {
                    builder.getfield(ClassDesc.ofInternalName(this.entryHook.ownedBase()), this.entryHook.ownerField(), ClassDesc.ofDescriptor(this.entryHook.ownerType()));
                    builder.loadConstant(site);
                    builder.invokestatic(OWNERSHIP, "ownedWrite", OWNED_HOOK);
                } else {
                    builder.loadConstant(site);
                    builder.invokestatic(OWNERSHIP, this.entryHook.self() == Kind.ENTITY ? "entityWrite" : "blockEntityWrite", this.entryHook.self() == Kind.ENTITY ? ENTITY_HOOK : BLOCK_ENTITY_HOOK);
                }
            }

            if (this.synchronizedMethod != null) {
                this.loadMonitor(builder);
                enter(builder);
                this.bodyStart = builder.newBoundLabel();
            }
        }

        @Override
        public void atEnd(final CodeBuilder builder) {
            if (this.synchronizedMethod != null) {
                Label bodyEnd = builder.newBoundLabel();
                Label handler = builder.newBoundLabel();
                this.loadMonitor(builder);
                exit(builder);
                builder.athrow();
                builder.exceptionCatchAll(this.bodyStart, bodyEnd, handler);
            }
        }

        @Override
        public void accept(final CodeBuilder builder, final CodeElement element) {
            if (element instanceof LineNumber lineNumber) {
                this.line = lineNumber.line();
            } else if (element instanceof MonitorInstruction monitor) {
                if (monitor.opcode() == Opcode.MONITORENTER) {
                    enter(builder);
                } else {
                    exit(builder);
                }

                return;
            } else if (element instanceof ReturnInstruction && this.synchronizedMethod != null) {
                this.loadMonitor(builder);
                exit(builder);
            } else if (element instanceof FieldInstruction field && OwnershipTransform.this.adopts(field)) {
                // Stack: entity, helper. The entity adopts the helper before the store.
                builder.dup2();
                builder.invokestatic(OWNERSHIP, "adopt", ADOPT_HOOK);
                if (this.fields && OwnershipTransform.this.kindOf(field) != Kind.NONE) {
                    this.hookWrite(builder, field);
                }
            } else if (element instanceof FieldInstruction field && this.fields && OwnershipTransform.this.kindOf(field) != Kind.NONE) {
                this.hookWrite(builder, field);
            }

            builder.with(element);
        }

        private void hookWrite(final CodeBuilder builder, final FieldInstruction field) {
            Kind kind = OwnershipTransform.this.kindOf(field);
            String owner = field.owner().asInternalName();
            int site = OwnershipTransform.this.sites.size();
            OwnershipTransform.this.sites.add(
                owner.substring(owner.lastIndexOf('/') + 1) + "." + field.name().stringValue() + " in " + this.className + ":" + this.line
                    + "\t" + owner + "\t" + field.name().stringValue() + "\t" + field.typeSymbol().descriptorString()
            );
            if (kind != Kind.LOGGED) {
                // Stack: object, value. Copy the object above the value for the hook.
                copyObject(builder, field);
                builder.loadConstant(site);
                builder.invokestatic(OWNERSHIP, kind == Kind.ENTITY ? "entityWrite" : "blockEntityWrite", kind == Kind.ENTITY ? ENTITY_HOOK : BLOCK_ENTITY_HOOK);
            }

            // Then, holding the owner's lock, log the old value: object, value -> object, value, object, old.
            copyObject(builder, field);
            builder.dup();
            builder.getfield(field.owner().asSymbol(), field.name().stringValue(), field.typeSymbol());
            builder.loadConstant(site);
            TypeKind type = TypeKind.from(field.typeSymbol());
            String suffix;
            ClassDesc valueType;
            switch (type) {
                case LONG -> { suffix = "J"; valueType = ConstantDescs.CD_long; }
                case FLOAT -> { suffix = "F"; valueType = ConstantDescs.CD_float; }
                case DOUBLE -> { suffix = "D"; valueType = ConstantDescs.CD_double; }
                case REFERENCE -> { suffix = "L"; valueType = ConstantDescs.CD_Object; }
                default -> { suffix = "I"; valueType = ConstantDescs.CD_int; }
            }

            boolean entity = kind == Kind.ENTITY;
            builder.invokestatic(UNDO, (entity ? "entity" : "any") + suffix, MethodTypeDesc.of(ConstantDescs.CD_void, entity ? CD_ENTITY : ConstantDescs.CD_Object, valueType, ConstantDescs.CD_int));
        }

        /** Stack: object, value -> object, value, object. */
        private static void copyObject(final CodeBuilder builder, final FieldInstruction field) {
            if (TypeKind.from(field.typeSymbol()).slotSize() == 1) {
                builder.swap().dup_x1();
            } else {
                builder.dup2_x1().pop2().dup_x2();
            }
        }
    }
}
