package java.util.concurrent.atomic;

import java.io.Serializable;

/**
 * @author Doug Lea
 * @since 1.8
 */
public class LongAdder extends Striped64 implements Serializable {

    private static final long serialVersionUID = 7249069246863182397L;

    // 创建一个初始和为零的新加法器
    public LongAdder() {
    }

    // 添加给定的值
    public void add(long x) {
        Cell[] as; // cells引用
        long b, v; // b: 获取的base的值; v: 期望的值
        int m;    // 表示cells数组的长度
        Cell a;   // 表示当前线程命中的cell单元格

        // 条件一: true -> 表示cells已经初始化过了,当前线程应该将数据写入到对应的cell中
        //        false -> 表示cells未初始化,当前所有线程应该将数据写到base中
        // 条件二: false -> 表示当前线程cas替换数据成功
        //        true -> 表示发生竞争了,肯能需要重试或者扩容
        if ((as = cells) != null || !casBase(b = base, b + x)) {
            // 什么情况会进来？
            // 1. true -> 表示cells已经初始化过了,当前线程应该将数据写入到对应的cell中
            // 2. true -> 表示发生竞争了,肯能需要重试或者扩容

            // true -> 未竞争  false -> 发生竞争
            boolean uncontended = true;

            // 条件一: true -> 说明cells未初始化，也就是多线程写base发生竞争了
            //        false -> 说明cells已经初始化了, 当前线程应该是找自己的cell单元格写值
            // 条件二: true -> 说明当前线程对应的cell为null,需要longAccumulate支持
            //        false -> 说明当前线程对应的cell不为null，说明下一步要将x的值累加到cell中
            // 条件三: true -> 表示cas失败 意味着当前线程对应的cell有竞争
            //        false -> 表示cas成功 流程结束
            if (as == null || (m = as.length - 1) < 0 ||
                    (a = as[getProbe() & m]) == null ||
                    !(uncontended = a.cas(v = a.value, v + x)))
                // 都有哪些情况会进来？
                // 1.true -> 说明cells未初始化，也就是多线程写base发生竞争了
                // 2.true -> 说明当前线程对应的cell为null,需要longAccumulate支持
                // 3.true -> 表示cas失败 意味着当前线程对应的cell有竞争
                longAccumulate(x, null, uncontended);
        }
    }

    /**
     * Equivalent to {@code add(1)}.
     */
    public void increment() {
        add(1L);
    }

    /**
     * Equivalent to {@code add(-1)}.
     */
    public void decrement() {
        add(-1L);
    }

    // 返回当前总和。 返回值不是原子快照； 在没有并发更新的情况下调用会返回准确的结果，但可能不会合并在计算总和时发生的并发更新
    public long sum() {
        Cell[] as = cells;
        Cell a;
        long sum = base;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    sum += a.value;
            }
        }
        return sum;
    }

    /**
     * Resets variables maintaining the sum to zero.  This method may
     * be a useful alternative to creating a new adder, but is only
     * effective if there are no concurrent updates.  Because this
     * method is intrinsically racy, it should only be used when it is
     * known that no threads are concurrently updating.
     */
    public void reset() {
        Cell[] as = cells;
        Cell a;
        base = 0L;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    a.value = 0L;
            }
        }
    }

    /**
     * Equivalent in effect to {@link #sum} followed by {@link
     * #reset}. This method may apply for example during quiescent
     * points between multithreaded computations.  If there are
     * updates concurrent with this method, the returned value is
     * <em>not</em> guaranteed to be the final value occurring before
     * the reset.
     *
     * @return the sum
     */
    public long sumThenReset() {
        Cell[] as = cells;
        Cell a;
        long sum = base;
        base = 0L;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null) {
                    sum += a.value;
                    a.value = 0L;
                }
            }
        }
        return sum;
    }

    /**
     * Returns the String representation of the {@link #sum}.
     *
     * @return the String representation of the {@link #sum}
     */
    public String toString() {
        return Long.toString(sum());
    }

    /**
     * Equivalent to {@link #sum}.
     *
     * @return the sum
     */
    public long longValue() {
        return sum();
    }

    /**
     * Returns the {@link #sum} as an {@code int} after a narrowing
     * primitive conversion.
     */
    public int intValue() {
        return (int) sum();
    }

    /**
     * Returns the {@link #sum} as a {@code float}
     * after a widening primitive conversion.
     */
    public float floatValue() {
        return (float) sum();
    }

    /**
     * Returns the {@link #sum} as a {@code double} after a widening
     * primitive conversion.
     */
    public double doubleValue() {
        return (double) sum();
    }

    /**
     * Serialization proxy, used to avoid reference to the non-public
     * Striped64 superclass in serialized forms.
     *
     * @serial include
     */
    private static class SerializationProxy implements Serializable {
        private static final long serialVersionUID = 7249069246863182397L;

        /**
         * The current value returned by sum().
         *
         * @serial
         */
        private final long value;

        SerializationProxy(LongAdder a) {
            value = a.sum();
        }

        /**
         * Return a {@code LongAdder} object with initial state
         * held by this proxy.
         *
         * @return a {@code LongAdder} object with initial state
         * held by this proxy.
         */
        private Object readResolve() {
            LongAdder a = new LongAdder();
            a.base = value;
            return a;
        }
    }

    /**
     * Returns a
     * <a href="../../../../serialized-form.html#java.util.concurrent.atomic.LongAdder.SerializationProxy">
     * SerializationProxy</a>
     * representing the state of this instance.
     *
     * @return a {@link SerializationProxy}
     * representing the state of this instance
     */
    private Object writeReplace() {
        return new SerializationProxy(this);
    }

    /**
     * @param s the stream
     * @throws java.io.InvalidObjectException always
     */
    private void readObject(java.io.ObjectInputStream s)
            throws java.io.InvalidObjectException {
        throw new java.io.InvalidObjectException("Proxy required");
    }

}
