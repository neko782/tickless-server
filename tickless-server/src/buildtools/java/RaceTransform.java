import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The race check rewrite ({@code -PraceCheck}): every call the game makes on a collection or one
 * of its iterators goes through a bridge that tells {@code RaceCheck} when the call starts and
 * ends, which views and iterators it returned, and each iterator step. One private static bridge
 * is added per class and call target; the call site passes its site number.
 */
final class RaceTransform {
    private static final ClassDesc RACE = ClassDesc.of("net.minecraft.world.level.parallel.RaceCheck");
    private static final MethodTypeDesc ENTER = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_int, ConstantDescs.CD_boolean);
    private static final MethodTypeDesc EXIT = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object);
    private static final MethodTypeDesc VIEW = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object);
    private static final MethodTypeDesc MARK = MethodTypeDesc.of(ConstantDescs.CD_int);
    private static final MethodTypeDesc RELEASE = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int);
    private static final MethodTypeDesc ITERATING = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Object, ConstantDescs.CD_Object, ConstantDescs.CD_int);
    private static final Set<String> JAVA_COLLECTIONS = Set.of(
        "Iterable", "Collection", "List", "Set", "Map", "Queue", "Deque", "SortedMap", "SortedSet", "NavigableMap",
        "NavigableSet", "SequencedCollection", "SequencedMap", "SequencedSet", "AbstractCollection", "AbstractList",
        "AbstractMap", "AbstractSet", "AbstractQueue", "AbstractSequentialList", "ArrayList", "LinkedList", "HashMap",
        "LinkedHashMap", "HashSet", "LinkedHashSet", "TreeMap", "TreeSet", "ArrayDeque", "PriorityQueue",
        "IdentityHashMap", "EnumMap", "EnumSet", "WeakHashMap", "BitSet"
    );
    private static final Set<String> JAVA_ITERATORS = Set.of("Iterator", "ListIterator", "PrimitiveIterator", "PrimitiveIterator$OfInt", "PrimitiveIterator$OfLong", "PrimitiveIterator$OfDouble");
    private static final Set<String> VIEWS = Set.of(
        "keySet", "values", "entrySet", "navigableKeySet", "descendingMap", "descendingKeySet", "headMap", "tailMap", "subMap",
        "headSet", "tailSet", "subSet", "subList", "reversed", "sequencedKeySet", "sequencedValues", "sequencedEntrySet",
        "asMap", "elementSet", "entries", "keys"
    );
    private static final String[] WRITE_PREFIXES = {
        "put", "add", "remove", "rem", "clear", "compute", "merge", "replace", "set", "retain", "poll", "push", "pop",
        "offer", "sort", "trim", "enqueue", "dequeue", "shuffle", "ensureCapacity", "getAndMove", "moveTo", "and", "or",
        "xor", "flip"
    };

    private enum Kind { READ, WRITE, VIEW, ITERATOR, STEP, HAS_NEXT, ITERATOR_WRITE }

    private record Target(Opcode opcode, ClassDesc owner, String name, MethodTypeDesc type, boolean isInterface, Kind kind) {
    }

    private RaceTransform() {
    }

    static byte[] transform(final ClassModel model, final ClassFile writer, final List<String> sites) {
        String className = model.thisClass().asInternalName();
        String simpleName = className.substring(className.lastIndexOf('/') + 1);
        ClassDesc thisClass = model.thisClass().asSymbol();
        boolean isInterface = (model.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0;
        Map<Target, String> bridges = new LinkedHashMap<>();
        boolean any = false;
        for (MethodModel method : model.methods()) {
            if (method.code().isPresent()) {
                for (CodeElement element : method.code().get()) {
                    if (element instanceof InvokeInstruction invoke && target(invoke) != null) {
                        any = true;
                        break;
                    }
                }
            }
        }

        if (!any) {
            return null;
        }

        ClassTransform transform = new ClassTransform() {
            @Override
            public void accept(final ClassBuilder builder, final ClassElement element) {
                if (element instanceof MethodModel method && method.code().isPresent()) {
                    String methodName = method.methodName().stringValue();
                    // Iterations a method starts end when it returns or throws, unless they ended
                    // before. Constructors only check calls: a handler around super() is not allowed.
                    boolean initializer = methodName.startsWith("<");
                    boolean scoped = !initializer && startsIterations(method);
                    builder.transformMethod(method, (methodBuilder, methodElement) -> {
                        if (methodElement instanceof CodeModel code) {
                            methodBuilder.transformCode(code, new CodeTransform() {
                                private int line;
                                private int mark;
                                private Label start;

                                @Override
                                public void atStart(final CodeBuilder code) {
                                    if (scoped) {
                                        code.invokestatic(RACE, "mark", MARK);
                                        this.mark = code.allocateLocal(TypeKind.INT);
                                        code.istore(this.mark);
                                        this.start = code.newBoundLabel();
                                    }
                                }

                                @Override
                                public void atEnd(final CodeBuilder code) {
                                    if (scoped) {
                                        Label end = code.newBoundLabel();
                                        Label handler = code.newBoundLabel();
                                        code.iload(this.mark);
                                        code.invokestatic(RACE, "release", RELEASE);
                                        code.athrow();
                                        code.exceptionCatchAll(this.start, end, handler);
                                    }
                                }

                                @Override
                                public void accept(final CodeBuilder code, final CodeElement codeElement) {
                                    if (codeElement instanceof LineNumber lineNumber) {
                                        this.line = lineNumber.line();
                                    } else if (codeElement instanceof ReturnInstruction && scoped) {
                                        code.iload(this.mark);
                                        code.invokestatic(RACE, "release", RELEASE);
                                    } else if (codeElement instanceof InvokeInstruction invoke) {
                                        Target target = target(invoke);
                                        if (target != null && initializer && target.kind() == Kind.ITERATOR) {
                                            target = new Target(target.opcode(), target.owner(), target.name(), target.type(), target.isInterface(), Kind.READ);
                                        }

                                        if (target != null) {
                                            String bridge = bridges.computeIfAbsent(target, t -> "race$" + bridges.size());
                                            int site = sites.size();
                                            sites.add(simpleName(invoke.owner().asInternalName()) + "." + target.name() + " in " + simpleName + "." + methodName + ":" + this.line);
                                            code.loadConstant(site);
                                            code.invokestatic(thisClass, bridge, bridgeType(target), isInterface);
                                            return;
                                        }
                                    }

                                    code.with(codeElement);
                                }
                            });
                        } else {
                            methodBuilder.with(methodElement);
                        }
                    });
                } else {
                    builder.with(element);
                }
            }

            @Override
            public void atEnd(final ClassBuilder builder) {
                for (Map.Entry<Target, String> bridge : bridges.entrySet()) {
                    Target target = bridge.getKey();
                    builder.withMethodBody(bridge.getValue(), bridgeType(target), ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC,
                        code -> writeBridge(code, target));
                }
            }
        };
        return writer.transformClass(model, transform);
    }

    private static boolean startsIterations(final MethodModel method) {
        for (CodeElement element : method.code().get()) {
            if (element instanceof InvokeInstruction invoke) {
                Target target = target(invoke);
                if (target != null && target.kind() == Kind.ITERATOR) {
                    return true;
                }
            }
        }

        return false;
    }

    private static MethodTypeDesc bridgeType(final Target target) {
        List<ClassDesc> parameters = new ArrayList<>();
        parameters.add(target.owner());
        parameters.addAll(target.type().parameterList());
        parameters.add(ConstantDescs.CD_int);
        return MethodTypeDesc.of(target.type().returnType(), parameters);
    }

    private static void writeBridge(final CodeBuilder code, final Target target) {
        List<ClassDesc> parameters = target.type().parameterList();
        int siteSlot = 1;
        for (ClassDesc parameter : parameters) {
            siteSlot += TypeKind.from(parameter).slotSize();
        }

        boolean iterator = target.kind() == Kind.STEP || target.kind() == Kind.HAS_NEXT || target.kind() == Kind.ITERATOR_WRITE;
        boolean write = target.kind() == Kind.WRITE || target.kind() == Kind.ITERATOR_WRITE;
        code.aload(0);
        code.iload(siteSlot);
        code.loadConstant(write ? 1 : 0);
        code.invokestatic(RACE, iterator ? "step" : "enter", ENTER);
        Label start = code.newBoundLabel();
        code.aload(0);
        int slot = 1;
        for (ClassDesc parameter : parameters) {
            TypeKind type = TypeKind.from(parameter);
            code.loadLocal(type, slot);
            slot += type.slotSize();
        }

        if (target.opcode() == Opcode.INVOKEINTERFACE) {
            code.invokeinterface(target.owner(), target.name(), target.type());
        } else {
            code.invokevirtual(target.owner(), target.name(), target.type());
        }

        switch (target.kind()) {
            case VIEW -> {
                // Stack: view -> view, collection, view.
                code.dup();
                code.aload(0);
                code.swap();
                code.invokestatic(RACE, "view", VIEW);
            }
            case ITERATOR -> {
                code.dup();
                code.aload(0);
                code.swap();
                code.iload(siteSlot);
                code.invokestatic(RACE, "iterating", ITERATING);
            }
            case HAS_NEXT -> {
                // Stack: more. An exhausted iterator ends its iteration.
                code.dup();
                code.ifThen(Opcode.IFEQ, then -> {
                    then.aload(0);
                    then.invokestatic(RACE, "done", EXIT);
                });
            }
            default -> {
            }
        }

        if (iterator) {
            code.return_(TypeKind.from(target.type().returnType()));
            return;
        }

        code.aload(0);
        code.invokestatic(RACE, "exit", EXIT);
        Label end = code.newBoundLabel();
        code.return_(TypeKind.from(target.type().returnType()));
        Label handler = code.newBoundLabel();
        code.aload(0);
        code.invokestatic(RACE, "exit", EXIT);
        code.athrow();
        code.exceptionCatchAll(start, end, handler);
    }

    private static Target target(final InvokeInstruction invoke) {
        Opcode opcode = invoke.opcode();
        if (opcode != Opcode.INVOKEVIRTUAL && opcode != Opcode.INVOKEINTERFACE) {
            return null;
        }

        String owner = invoke.owner().asInternalName();
        String name = invoke.name().stringValue();
        MethodTypeDesc type = invoke.typeSymbol();
        boolean returnsObject = !type.returnType().isPrimitive();
        Kind kind = null;
        if (isIterator(owner)) {
            kind = switch (name) {
                case "hasNext" -> Kind.HAS_NEXT;
                case "remove", "set", "add" -> Kind.ITERATOR_WRITE;
                default -> Kind.STEP;
            };
        } else if (isCollection(owner)) {
            if (returnsObject && (name.equals("iterator") || name.equals("listIterator") || name.equals("fastIterator") || name.equals("descendingIterator"))) {
                kind = Kind.ITERATOR;
            } else if (returnsObject && (VIEWS.contains(name) || name.endsWith("EntrySet") || name.endsWith("KeySet"))) {
                kind = Kind.VIEW;
            } else if (type.parameterCount() == 0 && (name.equals("isEmpty") || name.equals("size"))) {
                // A peek can only see a stale answer; it cannot break the collection.
                return null;
            } else {
                kind = isWrite(name, type) ? Kind.WRITE : Kind.READ;
            }
        }

        return kind == null ? null : new Target(opcode, invoke.owner().asSymbol(), name, type, invoke.isInterface(), kind);
    }

    private static boolean isIterator(final String owner) {
        String simple = simpleName(owner);
        if (owner.startsWith("java/util/") && owner.indexOf('/', "java/util/".length()) < 0) {
            return JAVA_ITERATORS.contains(simple);
        }

        return (owner.startsWith("it/unimi/dsi/fastutil/") || owner.startsWith("com/google/common/collect/"))
            && simple.endsWith("Iterator") && !simple.endsWith("Spliterator");
    }

    private static boolean isCollection(final String owner) {
        String simple = simpleName(owner);
        if (owner.startsWith("java/util/") && owner.indexOf('/', "java/util/".length()) < 0) {
            return JAVA_COLLECTIONS.contains(simple);
        }

        if (!owner.startsWith("it/unimi/dsi/fastutil/") && !owner.startsWith("com/google/common/collect/")) {
            return false;
        }

        if (simple.contains("Iterator") || simple.contains("Entry") || simple.contains("Comparator") || simple.contains("Function")
            || simple.contains("Consumer") || simple.contains("Predicate") || simple.contains("Operator") || simple.contains("Strategy")
            || simple.contains("Spliterator") || simple.contains("Immutable")) {
            return false;
        }

        return simple.contains("Map") || simple.contains("Set") || simple.contains("List") || simple.contains("Collection")
            || simple.contains("Queue") || simple.contains("Deque") || simple.contains("Iterable") || simple.contains("Multiset")
            || simple.contains("Table") || simple.contains("Multimap");
    }

    private static boolean isWrite(final String name, final MethodTypeDesc type) {
        if (name.equals("size") || name.equals("defaultReturnValue")) {
            return type.parameterCount() > 0;
        }

        for (String prefix : WRITE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    private static String simpleName(final String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }
}
