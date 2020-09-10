package java.util.concurrent.atomic;

/**
 * AtomicMarkableReference则是带了布尔型标记位(Boolean mark)的引用型原子量，每次执行CAS操作是需要对比该标记位，如果标记满足要求，则操作成功，否则操作失败。
 * 
 * @since 1.5
 * @author Doug Lea
 * @param <V> The type of object referred to by this reference
 */
public class AtomicMarkableReference<V> {

    /**
     * AtomicMarkableReference是带布尔型标记为的原子引用类型，
     * 为了同时兼顾引用值和标记位，它定义了一个静态内部类Pair，
     * AtomicMarkableReference的相关操作都是对Pair内成员的操作
     */
    private static class Pair<T> {
        final T reference;
        final boolean mark;
        private Pair(T reference, boolean mark) {
            this.reference = reference;
            this.mark = mark;
        }
        static <T> Pair<T> of(T reference, boolean mark) {
            return new Pair<T>(reference, mark);
        }
    }

    //用volatile的内存语义保证可见性,保存引用值和标记值
    private volatile Pair<V> pair;

    //构造函数，根据指定的引用值和标记值，构造一个Pair对象，并将该对象赋值给成员变量pair。
    //由于成员变量pair被volatile修饰，并且这里只有一个单操作的赋值语句，因此是可以保证原子性的。
    public AtomicMarkableReference(V initialRef, boolean initialMark) {
        pair = Pair.of(initialRef, initialMark);
    }

    //以原子方式获取当前引用值
    public V getReference() {
        return pair.reference;
    }

    //以原子方式获取当前标记值
    public boolean isMarked() {
        return pair.mark;
    }

    //这个函数很有意思，同时获取引用值和标记值。由于Java程序只能有一个返回值，该函数通过一个数组参数int[] markHolder来返回标记值，而通过return语句返回引用值。
    public V get(boolean[] markHolder) {
        Pair<V> pair = this.pair;
        markHolder[0] = pair.mark;
        return pair.reference;
    }

    /**
     * 以原子的方式同时更新引用值和标记值。该是通过调用CompareAndSet实现的。
     * JDK文档中说，weakCompareAndSet在更新变量时并不创建任何happens-before顺序，
     * 因此即使要修改的值是volatile的，也不保证对该变量的读写操作的顺序（一般来讲，volatile的内存语义保证happens-before顺序）。
     */
    public boolean weakCompareAndSet(V       expectedReference,
                                     V       newReference,
                                     boolean expectedMark,
                                     boolean newMark) {
        return compareAndSet(expectedReference, newReference,
                             expectedMark, newMark);
    }

    /**
     * 以原子的方式同时更新引用值和标记值。
     * 当期望引用值不等于当前引用值时，操作失败，返回false。
     * 当期望标记值不等于当前标记值时，操作失败，返回false。
     * 在期望引用值和期望标记值同时等于当前值的前提下，当新的引用值和新的标记值同时等于当前值时，不更新，直接返回true。由于要修改的内容与原内容完全一致，这种处理可以避免一次内存操作，效率较高。
     * 当新的引用值和新的标记值不同时等于当前值时，同时设置新的引用值和新的标记值，返回true
     */
    public boolean compareAndSet(V       expectedReference,
                                 V       newReference,
                                 boolean expectedMark,
                                 boolean newMark) {
        Pair<V> current = pair;
        return
            expectedReference == current.reference &&
            expectedMark == current.mark &&
            ((newReference == current.reference &&
              newMark == current.mark) ||
             casPair(current, Pair.of(newReference, newMark)));
    }

    //只要新的引用值和新的标记值，有一个与当前值不一样的，就同时修改引用值和标记值。
    public void set(V newReference, boolean newMark) {
        Pair<V> current = pair;
        if (newReference != current.reference || newMark != current.mark)
            this.pair = Pair.of(newReference, newMark);
    }

    /**
     * 修改指定引用值的标记值。
     * 当期望的引用值与当前引用值不相同时，操作失败，返回fasle。
     * 当期望的引用值与当前引用值相同时，操作成功，返回true。
     */
    public boolean attemptMark(V expectedReference, boolean newMark) {
        Pair<V> current = pair;
        return
            expectedReference == current.reference &&
            (newMark == current.mark ||
             casPair(current, Pair.of(expectedReference, newMark)));
    }

    // 成员变量unsafe是原子变量相关操作的基础，原子变量的修改操作最终有sun.misc.Unsafe类的CAS操作实现
    private static final sun.misc.Unsafe UNSAFE = sun.misc.Unsafe.getUnsafe();

    //成员变量pair的内存偏移值
    private static final long pairOffset =
        objectFieldOffset(UNSAFE, "pair", AtomicMarkableReference.class);

    //使用sun.misc.Unsafe类原子地交换两个对象。    
    private boolean casPair(Pair<V> cmp, Pair<V> val) {
        return UNSAFE.compareAndSwapObject(this, pairOffset, cmp, val);
    }

    //获取指定域的内存偏移量
    static long objectFieldOffset(sun.misc.Unsafe UNSAFE,
                                  String field, Class<?> klazz) {
        try {
            return UNSAFE.objectFieldOffset(klazz.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            // Convert Exception to corresponding Error
            NoSuchFieldError error = new NoSuchFieldError(field);
            error.initCause(e);
            throw error;
        }
    }
}
