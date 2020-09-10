package java.util.concurrent.atomic;
import java.util.function.IntUnaryOperator;
import java.util.function.IntBinaryOperator;
import sun.misc.Unsafe;


public class AtomicInteger extends Number implements java.io.Serializable {

    private static final long serialVersionUID = 6214790243416807050L;

    //一般来说，Java不像c或者c++那样，可以直接操作内存，Unsafe可以说是一个后门，可以直接操作内存，或者进行线程调度
    private static final Unsafe unsafe = Unsafe.getUnsafe();

    //在类初始化的时候，计算出value变量在对象中的偏移
    private static final long valueOffset;

    static {
        try {
            valueOffset = unsafe.objectFieldOffset
                (AtomicInteger.class.getDeclaredField("value"));
        } catch (Exception ex) { throw new Error(ex); }
    }

    //保存当前的值
    private volatile int value;

    //用给定初始值创建一个 AtomicInteger
    public AtomicInteger(int initialValue) {
        value = initialValue;
    }

    //用初始值0创建一个 AtomicInteger
    public AtomicInteger() {
    }

    //获取当前值
    public final int get() {
        return value;
    }

    //设置value的值
    public final void set(int newValue) {
        value = newValue;
    }

    //在不需要让共享变量的修改立刻让其他线程可见的时候，以设置普通变量的方式来修改共享状态，可以减少不必要的内存屏障，从而提高程序执行的效率。
    public final void lazySet(int newValue) {
        unsafe.putOrderedInt(this, valueOffset, newValue);
    }

    //先获取旧值，再获取新值
    public final int getAndSet(int newValue) {
        return unsafe.getAndSetInt(this, valueOffset, newValue);
    }

    //如果当前值==期望值，则自动将该值设置为给定的更新值。 
    public final boolean compareAndSet(int expect, int update) {
        return unsafe.compareAndSwapInt(this, valueOffset, expect, update);
    }

    public final boolean weakCompareAndSet(int expect, int update) {
        return unsafe.compareAndSwapInt(this, valueOffset, expect, update);
    }

    //先获取旧值，再进行自增
    public final int getAndIncrement() {
        return unsafe.getAndAddInt(this, valueOffset, 1);
    }

    //先获取旧值，再进行自减
    public final int getAndDecrement() {
        return unsafe.getAndAddInt(this, valueOffset, -1);
    }

    //先获取旧值，再增加给点的值
    public final int getAndAdd(int delta) {
        return unsafe.getAndAddInt(this, valueOffset, delta);
    }

    //先自增，再获取自增后的新值
    public final int incrementAndGet() {
        return unsafe.getAndAddInt(this, valueOffset, 1) + 1;
    }

    //先自减，再获取自减后的新值
    public final int decrementAndGet() {
        return unsafe.getAndAddInt(this, valueOffset, -1) - 1;
    }

    //先自增给点的值，再获取自增后的新值
    public final int addAndGet(int delta) {
        return unsafe.getAndAddInt(this, valueOffset, delta) + delta;
    }

    //先获取旧值，再更新成给点值
    public final int getAndUpdate(IntUnaryOperator updateFunction) {
        int prev, next;
        do {
            prev = get();
            next = updateFunction.applyAsInt(prev);
        } while (!compareAndSet(prev, next));
        return prev;
    }

    //先更新成给点值，再获取更新后的新值
    public final int updateAndGet(IntUnaryOperator updateFunction) {
        int prev, next;
        do {
            prev = get();
            next = updateFunction.applyAsInt(prev);
        } while (!compareAndSet(prev, next));
        return next;
    }

    //先获取旧值，然后更新成与给定值二元运算后的值
    public final int getAndAccumulate(int x,
                                      IntBinaryOperator accumulatorFunction) {
        int prev, next;
        do {
            prev = get();
            next = accumulatorFunction.applyAsInt(prev, x);
        } while (!compareAndSet(prev, next));
        return prev;
    }

    //先更新成与给定值二元运算后的值，再获取更新后的新值
    public final int accumulateAndGet(int x,
                                      IntBinaryOperator accumulatorFunction) {
        int prev, next;
        do {
            prev = get();
            next = accumulatorFunction.applyAsInt(prev, x);
        } while (!compareAndSet(prev, next));
        return next;
    }

    //返回当前值的字符串表示形式
    public String toString() {
        return Integer.toString(get());
    }

    //返回这个AtomicInteger的整型值
    public int intValue() {
        return get();
    }

    //返回该AtomicInteger的值作为long值
    public long longValue() {
        return (long)get();
    }

    //返回该AtomicInteger的值作为float值
    public float floatValue() {
        return (float)get();
    }

    //返回该AtomicInteger的值作为double值
    public double doubleValue() {
        return (double)get();
    }

}
