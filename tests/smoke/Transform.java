import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Transforme android.jar (corps « Stub! ») en un jar exécutable sur la JVM : chaque méthode appelle
 * stubhooks.Hooks.call(...) qui renvoie des valeurs par défaut « profondes » ou un comportement simulé.
 * Classes et méthodes rendues non finales / non abstraites ; constructeurs réduits à l'appel du super.
 */
public class Transform implements Opcodes {
    static final String HOOKS = "stubhooks/Hooks";

    public static void main(String[] a) throws Exception {
        ZipInputStream in = new ZipInputStream(new FileInputStream(a[0]));
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(a[1]));
        ZipEntry e;
        int n = 0;
        while ((e = in.getNextEntry()) != null) {
            String name = e.getName();
            if (!name.endsWith(".class")) continue;
            if (!(name.startsWith("android/") || name.startsWith("com/android/") || name.startsWith("dalvik/")
                    || name.startsWith("org/xmlpull/") || name.startsWith("org/apache/"))) continue;
            byte[] b = in.readAllBytes();
            byte[] t = transform(b);
            out.putNextEntry(new ZipEntry(name));
            out.write(t);
            out.closeEntry();
            n++;
        }
        out.close();
        System.out.println("classes transformées : " + n);
    }

    static byte[] transform(byte[] b) {
        ClassReader cr = new ClassReader(b);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        boolean itf = (cn.access & ACC_INTERFACE) != 0;
        cn.access &= ~ACC_FINAL;
        if (!itf) cn.access &= ~ACC_ABSTRACT;
        if (cn.innerClasses != null) for (InnerClassNode ic : cn.innerClasses) {
            ic.access &= ~ACC_FINAL;
            if ((ic.access & ACC_INTERFACE) == 0) ic.access &= ~ACC_ABSTRACT;
        }
        if (!itf) for (FieldNode f : cn.fields) f.access &= ~ACC_FINAL;
        boolean hasNoArg = false;
        for (MethodNode m : cn.methods) {
            if (m.name.equals("<clinit>")) continue;               // les stubs n'y mettent que des null / des constantes d'enum
            if (itf && (m.access & ACC_STATIC) == 0 && (m.access & ACC_ABSTRACT) != 0) continue;   // méthode d'interface
            m.access &= ~(ACC_FINAL | ACC_ABSTRACT | ACC_NATIVE | ACC_SYNCHRONIZED);
            m.instructions = new InsnList();
            m.tryCatchBlocks = new ArrayList<>();
            m.localVariables = null;
            m.visibleLocalVariableAnnotations = null;
            m.invisibleLocalVariableAnnotations = null;
            if (m.name.equals("<init>")) {
                if (m.desc.equals("()V")) hasNoArg = true;
                ctorBody(cn, m);
            } else {
                hookBody(cn, m);
            }
        }
        if (!itf && !hasNoArg) {
            MethodNode m = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
            ctorBody(cn, m);
            cn.methods.add(m);
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Constructeur : appel du constructeur parent (sans argument ou, pour Enum / classes java.*, avec valeurs par défaut). */
    static void ctorBody(ClassNode cn, MethodNode m) {
        InsnList il = m.instructions;
        il.add(new VarInsnNode(ALOAD, 0));
        String sup = cn.superName;
        if (sup.equals("java/lang/Enum")) {
            if (m.desc.startsWith("(Ljava/lang/String;I")) {
                il.add(new VarInsnNode(ALOAD, 1));
                il.add(new VarInsnNode(ILOAD, 2));
            } else {
                il.add(new InsnNode(ACONST_NULL));
                il.add(new InsnNode(ICONST_0));
            }
            il.add(new MethodInsnNode(INVOKESPECIAL, sup, "<init>", "(Ljava/lang/String;I)V", false));
        } else if (sup.startsWith("java/") || sup.startsWith("javax/") || sup.startsWith("org/")) {
            String desc = "()V";
            try {
                Class<?> k = Class.forName(sup.replace('/', '.'));
                java.lang.reflect.Constructor<?> best = null;
                for (java.lang.reflect.Constructor<?> c : k.getDeclaredConstructors()) {
                    int mod = c.getModifiers();
                    if (java.lang.reflect.Modifier.isPrivate(mod)) continue;
                    if (best == null || c.getParameterCount() < best.getParameterCount()) best = c;
                }
                if (best != null) {
                    desc = Type.getConstructorDescriptor(best);
                    for (Class<?> p : best.getParameterTypes()) pushDefault(il, Type.getType(p));
                }
            } catch (Throwable t) { /* ()V */ }
            il.add(new MethodInsnNode(INVOKESPECIAL, sup, "<init>", desc, false));
        } else {
            il.add(new MethodInsnNode(INVOKESPECIAL, sup, "<init>", "()V", false));
        }
        // le constructeur est aussi signalé aux hooks (capture des arguments, ex. new Intent(ctx, X.class))
        if (!m.desc.equals("()V")) {
            il.add(new VarInsnNode(ALOAD, 0));
            il.add(new LdcInsnNode(cn.name));
            il.add(new LdcInsnNode("<init>"));
            il.add(new LdcInsnNode(m.desc));
            pushArgs(il, m, false);
            il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "call", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false));
            il.add(new InsnNode(POP));
        }
        il.add(new InsnNode(RETURN));
    }

    static void pushDefault(InsnList il, Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT: il.add(new InsnNode(ICONST_0)); break;
            case Type.LONG: il.add(new InsnNode(LCONST_0)); break;
            case Type.FLOAT: il.add(new InsnNode(FCONST_0)); break;
            case Type.DOUBLE: il.add(new InsnNode(DCONST_0)); break;
            default: il.add(new InsnNode(ACONST_NULL));
        }
    }

    static void pushArgs(InsnList il, MethodNode m, boolean isStatic) {
        Type[] args = Type.getArgumentTypes(m.desc);
        il.add(new LdcInsnNode(args.length));
        il.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        int slot = isStatic ? 0 : 1;
        for (int i = 0; i < args.length; i++) {
            il.add(new InsnNode(DUP));
            il.add(new LdcInsnNode(i));
            Type t = args[i];
            il.add(new VarInsnNode(t.getOpcode(ILOAD), slot));
            box(il, t);
            il.add(new InsnNode(AASTORE));
            slot += t.getSize();
        }
    }

    static void box(InsnList il, Type t) {
        String w;
        switch (t.getSort()) {
            case Type.BOOLEAN: w = "java/lang/Boolean"; break;
            case Type.BYTE: w = "java/lang/Byte"; break;
            case Type.CHAR: w = "java/lang/Character"; break;
            case Type.SHORT: w = "java/lang/Short"; break;
            case Type.INT: w = "java/lang/Integer"; break;
            case Type.LONG: w = "java/lang/Long"; break;
            case Type.FLOAT: w = "java/lang/Float"; break;
            case Type.DOUBLE: w = "java/lang/Double"; break;
            default: return;
        }
        il.add(new MethodInsnNode(INVOKESTATIC, w, "valueOf", "(" + t.getDescriptor() + ")L" + w + ";", false));
    }

    static void hookBody(ClassNode cn, MethodNode m) {
        boolean isStatic = (m.access & ACC_STATIC) != 0;
        InsnList il = m.instructions;
        if (isStatic) il.add(new InsnNode(ACONST_NULL)); else il.add(new VarInsnNode(ALOAD, 0));
        il.add(new LdcInsnNode(cn.name));
        il.add(new LdcInsnNode(m.name));
        il.add(new LdcInsnNode(m.desc));
        pushArgs(il, m, isStatic);
        il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "call", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        Type r = Type.getReturnType(m.desc);
        switch (r.getSort()) {
            case Type.VOID: il.add(new InsnNode(POP)); il.add(new InsnNode(RETURN)); break;
            case Type.BOOLEAN: il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "z", "(Ljava/lang/Object;)Z", false)); il.add(new InsnNode(IRETURN)); break;
            case Type.BYTE: il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "b", "(Ljava/lang/Object;)B", false)); il.add(new InsnNode(IRETURN)); break;
            case Type.CHAR: il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "c", "(Ljava/lang/Object;)C", false)); il.add(new InsnNode(IRETURN)); break;
            case Type.SHORT: il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "s", "(Ljava/lang/Object;)S", false)); il.add(new InsnNode(IRETURN)); break;
            case Type.INT: il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "i", "(Ljava/lang/Object;)I", false)); il.add(new InsnNode(IRETURN)); break;
            case Type.LONG: il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "j", "(Ljava/lang/Object;)J", false)); il.add(new InsnNode(LRETURN)); break;
            case Type.FLOAT: il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "f", "(Ljava/lang/Object;)F", false)); il.add(new InsnNode(FRETURN)); break;
            case Type.DOUBLE: il.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "d", "(Ljava/lang/Object;)D", false)); il.add(new InsnNode(DRETURN)); break;
            default:
                il.add(new TypeInsnNode(CHECKCAST, r.getSort() == Type.ARRAY ? r.getDescriptor() : r.getInternalName()));
                il.add(new InsnNode(ARETURN));
        }
    }
}
