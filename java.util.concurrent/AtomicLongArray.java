package java.util.concurrent.atomic;
import java.util.function.LongUnaryOperator;
import java.util.function.LongBinaryOperator;
import sun.misc.Unsafe;

/**
 * 在原子变量相关类中，AtomicIntegerArray, AtomicLongArray, AtomicReferenceArray三个类是对数组类型的原子类操作，其原理和用法类似.
 * 
 * @since 1.5
 * @author Doug Lea
 */
public class AtomicLongArray implements java.io.Serializable {
    private static final long serialVersionUID = -2308431214976778248L;

    //成员变量unsafe是原子变量相关操作的基础,原子变量的修改操作最终有sun.misc.Unsafe类的CAS操作实现
    private static final Unsafe unsafe = Unsafe.getUnsafe();

    //arrayBaseOffset获取数组首个元素地址偏移
    private static final int base = unsafe.arrayBaseOffset(long[].class);

    //shift就是数组元素的偏移量
    private static final int shift;

    //保存数据的数组，在构造函数中初始化
    private final long[] array;

    static {
         //scale数组元素的增量偏移
        int scale = unsafe.arrayIndexScale(long[].class);

        /**
         * 用二进制&操作判断是否是2的倍数，很精彩
         * 对于int型数组，scale是4
         * 对于lang型数组，scale是8
         * 对于Reference型数组，scale是4
         */
        if ((scale & (scale - 1)) != 0)
            throw new Error("data type scale not a power of two");
        
        /**
         * 这里是处理long型的偏移量
         * 对于int型的偏移量，shift是2
         * 对于lang型的偏移量，shift是3
         * 对于Reference型的偏移量，shift是2
         */    
        shift = 31 - Integer.numberOfLeadingZeros(scale);  //该方法的作用是返回无符号整型i的最高非零位前面的0的个数，包括符号位在内
    }

    /**
     * 首先判断索引值i是否越界，如果越界，则抛出越界异常。
     * 否则，调用byteOffset(int i)函数计算该索引值i对应在数组中的内存偏移值，该偏移值被sun.misc.Unsafe类的函数使用。
     */
    private long checkedByteOffset(int i) {
        if (i < 0 || i >= array.length)
            throw new IndexOutOfBoundsException("index " + i);

        return byteOffset(i);
    }

    //根据索引值i，计算数组中元素的地址，首地址偏移+每个元素的偏移
    private static long byteOffset(int i) {
        return ((long) i << shift) + base;
    }

    //构造函数，初始化一个长度为length的空数组
    public AtomicLongArray(int length) {
        array = new long[length];
    }

    //构造函数，通过拷贝给定数组的值进行初始化;通过构造函数中final域的内存语义，保证数据可见性
    public AtomicLongArray(long[] array) {
        // Visibility guaranteed by final field guarantees
        this.array = array.clone();
    }

    //返回数组长度
    public final int length() {
        return array.length;
    }

    //以原子方式获取数组元素
    public final long get(int i) {
        return getRaw(checkedByteOffset(i));
    }

    //以原子方式获取数组元素，私有函数
    private long getRaw(long offset) {
        return unsafe.getLongVolatile(array, offset);
    }

    //以原子方式设置数组指定位置为新的值newValue
    public final void set(int i, long newValue) {
        unsafe.putLongVolatile(array, checkedByteOffset(i), newValue);
    }

    /**
     * 简单点说，lazySet优先保证数据的修改操作，而降低对可见性的要求。
     * lazySet是使用Unsafe.putOrderedLong方法，这个方法在对低延迟代码是很有用的，它能够实现非堵塞的写入，
     * 这些写入不会被Java的JIT重新排序指令(instruction reordering)，这样它使用快速的存储-存储(store-store) barrier, 而不是较慢的存储-加载(store-load) barrier, 
     * 后者总是用在volatile的写操作上，这种性能提升是有代价的，虽然便宜，也就是写后结果并不会被其他线程看到，甚至是自己的线程，通常是几纳秒后被其他线程看到，这个时间比较短，所以代价可以忍受。
     * 类似Unsafe.putOrderedLong还有unsafe.putOrderedObject等方法，unsafe.putOrderedLong比使用 volatile long要快3倍左右。
     */
    public final void lazySet(int i, long newValue) {
        unsafe.putOrderedLong(array, checkedByteOffset(i), newValue);
    }

    /**
     * 以原子方式设置数组指定位置为新的值newValue，该过程会以自旋的形式循环执行，直到操作成功。
     * 该过程不会阻塞。因为该函数包含两个操作(get和set)，因此需要使用自旋方式通过sun.misc.Unsafe的CAS操作保证原子性。
     */
    public final long getAndSet(int i, long newValue) {
        return unsafe.getAndSetLong(array, checkedByteOffset(i), newValue);
    }

    // 以原子方式设置数组指定位置为新的值update
    // 如果当前值等于expect，并设置成功，返回true
    // 如果当前值不等于expect，则设置失败，返回false
    // 该过程不阻塞
    public final boolean compareAndSet(int i, long expect, long update) {
        return compareAndSetRaw(checkedByteOffset(i), expect, update);
    }

    // 以原子方式设置数组指定位置为新的值update
    // 如果当前值等于expect，并设置成功，返回true
    // 如果当前值不等于expect，则设置失败，返回false
    // 该过程不阻塞
    // 私有函数
    private boolean compareAndSetRaw(long offset, long expect, long update) {
        return unsafe.compareAndSwapLong(array, offset, expect, update);
    }

    // 以原子方式设置数组指定位置为新的值update
    // 如果当前值等于expect，并设置成功，返回true
    // 如果当前值不等于expect，则设置失败，返回false
    // 该过程不阻塞
    // 该过程不保证volatile成员的happens-before语义顺序
    public final boolean weakCompareAndSet(int i, long expect, long update) {
        return compareAndSet(i, expect, update);
    }

    // 以原子方式设置数组指定位置为当前值加1
    // 该过程不阻塞
    // 返回更新前的值
    public final long getAndIncrement(int i) {
        return getAndAdd(i, 1);
    }

    // 以原子方式设置数组指定位置为当前值减1
    // 该过程不阻塞
    // 返回更新前的值
    public final long getAndDecrement(int i) {
        return getAndAdd(i, -1);
    }

    // 以原子方式设置数组指定位置为当前值+delta
    // 该过程不阻塞
    // 返回更新前的值
    public final long getAndAdd(int i, long delta) {
        return unsafe.getAndAddLong(array, checkedByteOffset(i), delta);
    }

    // 以原子方式设置数组指定位置为当前值加1
    // 该过程不阻塞
    // 返回更新前的值
    public final long incrementAndGet(int i) {
        return getAndAdd(i, 1) + 1;
    }

    // 以原子方式设置数组指定位置为当前值减1
    // 该过程不阻塞
    // 返回更新前的值
    public final long decrementAndGet(int i) {
        return getAndAdd(i, -1) - 1;
    }

    // 以原子方式设置数组指定位置为当前值+delta
    // 该过程不阻塞
    // 返回更新后的值
    public long addAndGet(int i, long delta) {
        return getAndAdd(i, delta) + delta;
    }

    public final long getAndUpdate(int i, LongUnaryOperator updateFunction) {
        long offset = checkedByteOffset(i);
        long prev, next;
        do {
            prev = getRaw(offset);
            next = updateFunction.applyAsLong(prev);
        } while (!compareAndSetRaw(offset, prev, next));
        return prev;
    }

    public final long updateAndGet(int i, LongUnaryOperator updateFunction) {
        long offset = checkedByteOffset(i);
        long prev, next;
        do {
            prev = getRaw(offset);
            next = updateFunction.applyAsLong(prev);
        } while (!compareAndSetRaw(offset, prev, next));
        return next;
    }

    public final long getAndAccumulate(int i, long x,
                                      LongBinaryOperator accumulatorFunction) {
        long offset = checkedByteOffset(i);
        long prev, next;
        do {
            prev = getRaw(offset);
            next = accumulatorFunction.applyAsLong(prev, x);
        } while (!compareAndSetRaw(offset, prev, next));
        return prev;
    }

    public final long accumulateAndGet(int i, long x,
                                      LongBinaryOperator accumulatorFunction) {
        long offset = checkedByteOffset(i);
        long prev, next;
        do {
            prev = getRaw(offset);
            next = accumulatorFunction.applyAsLong(prev, x);
        } while (!compareAndSetRaw(offset, prev, next));
        return next;
    }

    //通过遍历数组中元素来构造字符串，并返回。该函数是线程不安全的，在操作过程中内容可能会发生变化，使得AtomicLongArray具有弱一致性。
    public String toString() {
        int iMax = array.length - 1;
        if (iMax == -1)
            return "[]";

        StringBuilder b = new StringBuilder();
        b.append('[');
        for (int i = 0; ; i++) {
            b.append(getRaw(byteOffset(i)));
            if (i == iMax)
                return b.append(']').toString();
            b.append(',').append(' ');
        }
    }

}
