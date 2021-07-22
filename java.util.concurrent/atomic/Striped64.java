package java.util.concurrent.atomic;

import java.util.function.LongBinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.concurrent.ThreadLocalRandom;

@SuppressWarnings("serial")
abstract class Striped64 extends Number {

    @sun.misc.Contended
    static final class Cell {
        volatile long value;

        Cell(long x) {
            value = x;
        }

        final boolean cas(long cmp, long val) {
            return UNSAFE.compareAndSwapLong(this, valueOffset, cmp, val);
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long valueOffset;

        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> ak = Cell.class;
                valueOffset = UNSAFE.objectFieldOffset
                        (ak.getDeclaredField("value"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    // 表示当前计算机的cpu数量,控制cells数组长度的一个关键条件
    static final int NCPU = Runtime.getRuntime().availableProcessors();

    // cells数组，长度为2的指数幂
    transient volatile Cell[] cells;

    // 没有发生竞争时 数据会累加到base上 或者 当cells扩容时 需要加数据写到base中
    transient volatile long base;

    // 初始化cells或者扩容cells时 都需要获取锁 0表示无锁状态 1表示其实线程已经持有锁了
    transient volatile int cellsBusy;

    Striped64() {
    }


    final boolean casBase(long cmp, long val) {
        return UNSAFE.compareAndSwapLong(this, BASE, cmp, val);
    }

    // 通过CAS方式获取锁
    final boolean casCellsBusy() {
        return UNSAFE.compareAndSwapInt(this, CELLSBUSY, 0, 1);
    }

    // 返回当前线程的探测值。 由于包装限制，从 ThreadLocalRandom 复制
    static final int getProbe() {
        return UNSAFE.getInt(Thread.currentThread(), PROBE);
    }

    // 重置当前线程的探测值
    static final int advanceProbe(int probe) {
        probe ^= probe << 13;   // xorshift
        probe ^= probe >>> 17;
        probe ^= probe << 5;
        UNSAFE.putInt(Thread.currentThread(), PROBE, probe);
        return probe;
    }

    /**
     * Handles cases of updates involving initialization, resizing,
     * creating new Cells, and/or contention. See above for
     * explanation. This method suffers the usual non-modularity
     * problems of optimistic retry code, relying on rechecked sets of
     * reads.
     *
     * @param x              the value
     * @param fn             the update function, or null for add (this convention
     *                       avoids the need for an extra field or function in LongAdder).
     * @param wasUncontended false if CAS failed before call
     */
    // 都有哪些情况会调用？
    // 1.true -> 说明cells未初始化，也就是多线程写base发生竞争了
    // 2.true -> 说明当前线程对应的cell为null,需要longAccumulate支持
    // 3.true -> 表示cas失败 意味着当前线程对应的cell有竞争
    final void longAccumulate(long x, LongBinaryOperator fn,
                              boolean wasUncontended) {  // 只有当cells初始化之后,并且当前线程 竞争修改失败 才会是false
        int h; // 当前线程的hash值
        // 条件成立 说明当前线程还未分配hash值
        if ((h = getProbe()) == 0) {
            ThreadLocalRandom.current(); // force initialization
            h = getProbe();
            wasUncontended = true;
        }

        // 表示扩容意向 false表示一定不会扩容 true表示可能会扩容
        boolean collide = false;                // True if last slot nonempty
        for (; ; ) {
            Cell[] as;  // 表示cells的引用
            Cell a;     // 表示当前线程命中的cell
            int n;      // 表示cells数组长度
            long v;     // 表示期望值

            // CASE1: 表示cells已经初始化了 当前线程应该将数据写入对应的cell中
            if ((as = cells) != null && (n = as.length) > 0) {

                // CASE1.1 当前线程对应的cell还未创建
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {       // 尝试附加新的Cell
                        Cell r = new Cell(x);   // 乐观的创建
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // 在锁定状态下重新检查
                                Cell[] rs;
                                int m, j;
                                if ((rs = cells) != null &&
                                        (m = rs.length) > 0 &&
                                        rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }

                    // 扩容意向强制改为false
                    collide = false;

                    // CASE1.2:
                    // wasUncontended: 只有当cells初始化之后,并且当前线程 竞争修改失败 才会是false
                } else if (!wasUncontended)       // CAS already known to fail                CAS 已知失败
                    wasUncontended = true;        // Continue after rehash                    重刷后继续

                    // CASE1.3: 当前线程rehash过hash值后 然后新命中的cell补位空
                    // true -> 写成功 退出循环
                    // false -> 写失败 说明新命中的cell也存在竞争 重试1次
                else if (a.cas(v = a.value, ((fn == null) ? v + x :
                        fn.applyAsLong(v, x))))
                    break;
                    // CASE1.4:
                    // 条件一: true -> 扩容意向改为false了 表示不扩容了; false -> 说明cells数组还可以扩容
                    // 条件二: true -> 表示在此期间其他线程已经扩容过了 当前线程rehash之后重试即可
                else if (n >= NCPU || cells != as)
                    collide = false;  // 扩容意向改为false 表示不扩容了
                    // CASE1.5:
                    // true -> 设置扩容意向为true 但是不一定真的发生扩容 会rehash之后 再次重试1次
                else if (!collide)
                    collide = true;
                    // CASE1.6: 真正扩容的逻辑
                    // 条件一: true -> 表示当前无锁状态 当前线程可以去竞争这把锁
                    // 条件二: true -> 表示当前线程获取锁成功 可以执行扩容逻辑
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }

                //重置当前线程的hash值
                h = advanceProbe(h);

                // CASE2: 前置条件cells还未初始化 as为null
                // 条件一: true -> 表示当前未加锁
                // 条件二: cells == as? 因为其他线程可能会在你给as赋值之后修改了 cells
                // 条件三: true -> 表示获取锁成功 会把cellsBusy设置成1
                //        false -> 表示其它线程正在持有这把锁
            } else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {
                    // cells == as? 防止其他线程已经初始化锁了 当前线程再初始化 会导致数据丢失
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(x);
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
                // CASE3:
                // 1.当前cellsBusy处于加锁状态 表示其他线程正在初始化cells 所以当前线程将值累加到base上
                // 2.cells被其他线程初始化后 当前线程需要将数据累加到base
            } else if (casBase(v = base, ((fn == null) ? v + x :
                    fn.applyAsLong(v, x))))
                break;                          // Fall back on using base
        }
    }

    /**
     * Same as longAccumulate, but injecting long/double conversions
     * in too many places to sensibly merge with long version, given
     * the low-overhead requirements of this class. So must instead be
     * maintained by copy/paste/adapt.
     */
    final void doubleAccumulate(double x, DoubleBinaryOperator fn,
                                boolean wasUncontended) {
        int h;
        if ((h = getProbe()) == 0) {
            ThreadLocalRandom.current(); // force initialization
            h = getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (; ; ) {
            Cell[] as;
            Cell a;
            int n;
            long v;
            if ((as = cells) != null && (n = as.length) > 0) {
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {       // Try to attach new Cell
                        Cell r = new Cell(Double.doubleToRawLongBits(x));
                        if (cellsBusy == 0 && casCellsBusy()) {
                            boolean created = false;
                            try {               // Recheck under lock
                                Cell[] rs;
                                int m, j;
                                if ((rs = cells) != null &&
                                        (m = rs.length) > 0 &&
                                        rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                } else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                else if (a.cas(v = a.value,
                        ((fn == null) ?
                                Double.doubleToRawLongBits
                                        (Double.longBitsToDouble(v) + x) :
                                Double.doubleToRawLongBits
                                        (fn.applyAsDouble
                                                (Double.longBitsToDouble(v), x)))))
                    break;
                else if (n >= NCPU || cells != as)
                    collide = false;            // At max size or stale
                else if (!collide)
                    collide = true;
                else if (cellsBusy == 0 && casCellsBusy()) {
                    try {
                        if (cells == as) {      // Expand table unless stale
                            Cell[] rs = new Cell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            cells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = advanceProbe(h);
            } else if (cellsBusy == 0 && cells == as && casCellsBusy()) {
                boolean init = false;
                try {                           // Initialize table
                    if (cells == as) {
                        Cell[] rs = new Cell[2];
                        rs[h & 1] = new Cell(Double.doubleToRawLongBits(x));
                        cells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            } else if (casBase(v = base,
                    ((fn == null) ?
                            Double.doubleToRawLongBits
                                    (Double.longBitsToDouble(v) + x) :
                            Double.doubleToRawLongBits
                                    (fn.applyAsDouble
                                            (Double.longBitsToDouble(v), x)))))
                break;                          // Fall back on using base
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long BASE;
    private static final long CELLSBUSY;
    private static final long PROBE;

    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> sk = Striped64.class;
            BASE = UNSAFE.objectFieldOffset
                    (sk.getDeclaredField("base"));
            CELLSBUSY = UNSAFE.objectFieldOffset
                    (sk.getDeclaredField("cellsBusy"));
            Class<?> tk = Thread.class;
            PROBE = UNSAFE.objectFieldOffset
                    (tk.getDeclaredField("threadLocalRandomProbe"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
