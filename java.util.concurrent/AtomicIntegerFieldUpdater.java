package java.util.concurrent.atomic;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import java.util.function.IntUnaryOperator;
import sun.reflect.CallerSensitive;
import sun.reflect.Reflection;

/**
 * 字段更新器
 * 
 * 在原子变量相关类中，AtomicIntegerFieldUpdater, AtomicLongFieldUpdater, AtomicReferenceFieldUpdater三个类是用于原子地修改对象的成员属性，
 * 它们的原理和用法类似，区别在于对Integer，Long，Reference类型的成员属性进行修改。
 *
 * AtomicIntegerFieldUpdater的设计非常有意思。AtomicIntegerFieldUpdater本身是一个抽象类，只有一个受保护的构造函数，它本身不能被实例化。
 * 在AtomicIntegerFieldUpdater中定义了一些基本的模板方法，然后通过一个静态内部子类AtomicIntegerFieldUpdaterImpl来实现具体的操作。
 * AtomicIntegerFieldUpdaterImpl中的相关操作也都是基于Unsafe类来实现的。
 * 
 * @since 1.5
 * @author Doug Lea
 * @param <T> The type of the object holding the updatable field
 */
public abstract class AtomicIntegerFieldUpdater<T> {
    
    //为对象创建并返回一个具有给定字段的更新器实例。在该方法中，直接构造一个AtomicIntegerFieldUpdaterImpl实例。
    @CallerSensitive
    public static <U> AtomicIntegerFieldUpdater<U> newUpdater(Class<U> tclass,
                                                              String fieldName) {
        return new AtomicIntegerFieldUpdaterImpl<U>
            (tclass, fieldName, Reflection.getCallerClass());
    }

    //受保护的无操作构造函数，供子类使用
    protected AtomicIntegerFieldUpdater() {
    }

    public abstract boolean compareAndSet(T obj, int expect, int update);
    
    public abstract boolean weakCompareAndSet(T obj, int expect, int update);

    public abstract void set(T obj, int newValue);

    public abstract void lazySet(T obj, int newValue);

    public abstract int get(T obj);

    public int getAndSet(T obj, int newValue) {
        int prev;
        do {
            prev = get(obj);
        } while (!compareAndSet(obj, prev, newValue));
        return prev;
    }

    public int getAndIncrement(T obj) {
        int prev, next;
        do {
            prev = get(obj);
            next = prev + 1;
        } while (!compareAndSet(obj, prev, next));
        return prev;
    }

    public int getAndDecrement(T obj) {
        int prev, next;
        do {
            prev = get(obj);
            next = prev - 1;
        } while (!compareAndSet(obj, prev, next));
        return prev;
    }

    public int getAndAdd(T obj, int delta) {
        int prev, next;
        do {
            prev = get(obj);
            next = prev + delta;
        } while (!compareAndSet(obj, prev, next));
        return prev;
    }

    public int incrementAndGet(T obj) {
        int prev, next;
        do {
            prev = get(obj);
            next = prev + 1;
        } while (!compareAndSet(obj, prev, next));
        return next;
    }

    public int decrementAndGet(T obj) {
        int prev, next;
        do {
            prev = get(obj);
            next = prev - 1;
        } while (!compareAndSet(obj, prev, next));
        return next;
    }

    public int addAndGet(T obj, int delta) {
        int prev, next;
        do {
            prev = get(obj);
            next = prev + delta;
        } while (!compareAndSet(obj, prev, next));
        return next;
    }

    public final int getAndUpdate(T obj, IntUnaryOperator updateFunction) {
        int prev, next;
        do {
            prev = get(obj);
            next = updateFunction.applyAsInt(prev);
        } while (!compareAndSet(obj, prev, next));
        return prev;
    }

    public final int updateAndGet(T obj, IntUnaryOperator updateFunction) {
        int prev, next;
        do {
            prev = get(obj);
            next = updateFunction.applyAsInt(prev);
        } while (!compareAndSet(obj, prev, next));
        return next;
    }

    public final int getAndAccumulate(T obj, int x,
                                      IntBinaryOperator accumulatorFunction) {
        int prev, next;
        do {
            prev = get(obj);
            next = accumulatorFunction.applyAsInt(prev, x);
        } while (!compareAndSet(obj, prev, next));
        return prev;
    }

    public final int accumulateAndGet(T obj, int x,
                                      IntBinaryOperator accumulatorFunction) {
        int prev, next;
        do {
            prev = get(obj);
            next = accumulatorFunction.applyAsInt(prev, x);
        } while (!compareAndSet(obj, prev, next));
        return next;
    }

    //AtomicIntegerFieldUpdater本身是一个抽象类，通过一个静态内部子类来实现相关的操作。
    private static final class AtomicIntegerFieldUpdaterImpl<T> extends AtomicIntegerFieldUpdater<T> {

        //成员变量unsafe是原子变量相关操作的基础，原子变量的修改操作最终有sun.misc.Unsafe类的CAS操作实现
        private static final sun.misc.Unsafe U = sun.misc.Unsafe.getUnsafe();

        //成员变量fieldName的内存偏移值，在构造函数中初始化
        private final long offset;
        
        //调用者类，通过反射获取
        private final Class<?> cclass;

        //操作目标类，对该类中的fieldName字段进行更新
        private final Class<T> tclass;


        /**
         * 受保护的无操作构造函数，供子类实现。AtomicIntegerFieldUpdaterImpl是唯一的子类。
         * 在构造函数中，首先获取要更新的类(tclass)的指定成员变量fieldName的访问修饰符(Modifier: public, private, default, protected)，
         * 然后检查调用类(caller)是否有权限访问该成员变量fieldName，如果没有权限则抛出异常。
         * 接下来，判断指定的成员变量fieldName的类型是否是int，如果不是，也抛出异常。
         * 接下来，判断当前指定的成员变量是否是volatile类型的，如果不是，也抛出异常。
         * 接下来，实例化调用者类cclass，和操作目标类tclass。最后，计算指定成员变量fieldName的内存偏移值。
         */
        AtomicIntegerFieldUpdaterImpl(final Class<T> tclass,
                                      final String fieldName,
                                      final Class<?> caller) {
            final Field field;
            final int modifiers;
            try {
                //获取要更新的类的指定成员变量fieldName的访问修饰符
                field = AccessController.doPrivileged(
                    new PrivilegedExceptionAction<Field>() {
                        public Field run() throws NoSuchFieldException {
                            return tclass.getDeclaredField(fieldName);
                        }
                    });
                modifiers = field.getModifiers();
                 //验证访问修饰符权限
                sun.reflect.misc.ReflectUtil.ensureMemberAccess(caller, tclass, null, modifiers);

                ClassLoader cl = tclass.getClassLoader();
                ClassLoader ccl = caller.getClassLoader();
                if ((ccl != null) && (ccl != cl) && ((cl == null) || !isAncestor(cl, ccl))) {
                    sun.reflect.misc.ReflectUtil.checkPackageAccess(tclass);
                }
            } catch (PrivilegedActionException pae) {
                throw new RuntimeException(pae.getException());
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            //当前成员变量的类型必须是int
            if (field.getType() != int.class)
                throw new IllegalArgumentException("Must be integer type");
            
            //当前成员变量必须是volatile修饰
            if (!Modifier.isVolatile(modifiers))
                throw new IllegalArgumentException("Must be volatile type");

            this.cclass = (Modifier.isProtected(modifiers) &&
                           tclass.isAssignableFrom(caller) &&
                           !isSamePackage(tclass, caller))
                          ? caller : tclass;

            //设置目标操作类             
            this.tclass = tclass;

            //设置成员变量的内存偏移值
            this.offset = U.objectFieldOffset(field);
        }

        /**
         * Returns true if the second classloader can be found in the first
         * classloader's delegation chain.
         * Equivalent to the inaccessible: first.isAncestor(second).
         */
        private static boolean isAncestor(ClassLoader first, ClassLoader second) {
            ClassLoader acl = first;
            do {
                acl = acl.getParent();
                if (second == acl) {
                    return true;
                }
            } while (acl != null);
            return false;
        }

        /**
         * Returns true if the two classes have the same class loader and
         * package qualifier
         */
        private static boolean isSamePackage(Class<?> class1, Class<?> class2) {
            return class1.getClassLoader() == class2.getClassLoader()
                   && Objects.equals(getPackageName(class1), getPackageName(class2));
        }

        private static String getPackageName(Class<?> cls) {
            String cn = cls.getName();
            int dot = cn.lastIndexOf('.');
            return (dot != -1) ? cn.substring(0, dot) : "";
        }

        /**
         * Checks that target argument is instance of cclass.  On
         * failure, throws cause.
         */
        private final void accessCheck(T obj) {
            if (!cclass.isInstance(obj))
                throwAccessCheckException(obj);
        }

        /**
         * Throws access exception if accessCheck failed due to
         * protected access, else ClassCastException.
         */
        private final void throwAccessCheckException(T obj) {
            if (cclass == tclass)
                throw new ClassCastException();
            else
                throw new RuntimeException(
                    new IllegalAccessException(
                        "Class " +
                        cclass.getName() +
                        " can not access a protected member of class " +
                        tclass.getName() +
                        " using an instance of " +
                        obj.getClass().getName()));
        }

        //以原子方式设置当前值为update。如果当前值等于expect，并设置成功，返回true。如果当前值不等于expect，则设置失败，返回false。
        public final boolean compareAndSet(T obj, int expect, int update) {
            accessCheck(obj);
            return U.compareAndSwapInt(obj, offset, expect, update);
        }

        /**
         * 以原子方式设置当前值为update。如果当前值等于expect，并设置成功，返回true。如果当前值不等于expect，则设置失败，返回false。
         * weakCompareAndSet的实现与compareAndSet完全相同，但是，在JDK文档中声明，weakCompareAndSet不保证volatile的happens-before内存顺序性语义，这是它们的区别。
         */
        public final boolean weakCompareAndSet(T obj, int expect, int update) {
            accessCheck(obj);
            return U.compareAndSwapInt(obj, offset, expect, update);
        }

        //以原子方式设置当前值为newValue。通过Unsafe的putIntVolatile保证原子性。
        public final void set(T obj, int newValue) {
            accessCheck(obj);
            U.putIntVolatile(obj, offset, newValue);
        }

        //以原子方式设置当前值为newValue。与set方法的区别在于使用Unsafe类的putOreredInt保证原子性，同时该方法优先保证数据的更新，而不保证可见性，效率高。
        public final void lazySet(T obj, int newValue) {
            accessCheck(obj);
            U.putOrderedInt(obj, offset, newValue);
        }

        //以原子方式获取当前值。通过Unsafe的getIntVolatile保证原则性。
        public final int get(T obj) {
            accessCheck(obj);
            return U.getIntVolatile(obj, offset);
        }

        //以原子方式将当前值更新为newValue，并返回更新前的值。
        public final int getAndSet(T obj, int newValue) {
            accessCheck(obj);
            return U.getAndSetInt(obj, offset, newValue);
        }

        public final int getAndAdd(T obj, int delta) {
            accessCheck(obj);
            return U.getAndAddInt(obj, offset, delta);
        }

        public final int getAndIncrement(T obj) {
            return getAndAdd(obj, 1);
        }

        public final int getAndDecrement(T obj) {
            return getAndAdd(obj, -1);
        }

        public final int incrementAndGet(T obj) {
            return getAndAdd(obj, 1) + 1;
        }

        public final int decrementAndGet(T obj) {
            return getAndAdd(obj, -1) - 1;
        }

        public final int addAndGet(T obj, int delta) {
            return getAndAdd(obj, delta) + delta;
        }
    }
}
