package java.util.concurrent.locks;

import sun.misc.Unsafe;


/**
 * LockSupport 和 CAS 是Java并发包中很多并发工具控制机制的基础，它们底层其实都是依赖Unsafe实现。
 *
 * LockSupport是用来创建锁和其他同步类的基本线程阻塞原语。LockSupport 提供park()和unpark()方法实现阻塞线程和解除线程阻塞，
 * LockSupport和每个使用它的线程都与一个许可(permit)关联。permit相当于1，0的开 关，默认是0，调用一次unpark就加1变成1，
 * 调用一次park会消费permit, 也就是将1变成0，同时park立即返 回。再次调用park会变成block（因为permit为0了，会阻塞在这里，直到permit变为1）,
 * 这时调用unpark会把 permit置为1。每个线程都有一个相关的permit, permit最多只有一个，重复调用unpark也不会积累。
 */
public class LockSupport {
    private LockSupport() {
    }

    //设置线程t的parkBlocker字段的值为arg
    private static void setBlocker(Thread t, Object arg) {
        //尽管hotspot易变，但在这里并不需要写屏障。
        UNSAFE.putObject(t, parkBlockerOffset, arg);
    }

    //释放该线程的阻塞状态，即类似释放锁，只不过这里是将许可设置为1
    public static void unpark(Thread thread) {
        //判断线程是否为空
        if (thread != null)
            //释放该线程许可
            UNSAFE.unpark(thread);
    }

    //阻塞当前线程，并且将当前线程的parkBlocker字段设置为blocker
    public static void park(Object blocker) {
        //获取当前线程
        Thread t = Thread.currentThread();
        //将当前线程的parkBlocker字段设置为blocker
        setBlocker(t, blocker);
        //阻塞当前线程，第一个参数表示isAbsolute，是否为绝对时间，第二个参数就是代表时间
        UNSAFE.park(false, 0L);
        //重新可运行后再此设置Blocker
        setBlocker(t, null);
    }

    //阻塞当前线程nanos秒
    public static void parkNanos(Object blocker, long nanos) {
        //先判断nanos是否大于0，小于等于0代表不阻塞
        if (nanos > 0) {
            //获取当前线程
            Thread t = Thread.currentThread();
            //将当前线程的parkBlocker字段设置为blocker
            setBlocker(t, blocker);
            //阻塞当前线程nanos时间
            UNSAFE.park(false, nanos);
            //将当前线程的parkBlocker字段设置为null
            setBlocker(t, null);
        }
    }

    //将当前线程阻塞绝对时间的deadline秒，并且将当前线程的parkBlockerOffset设置为blocker
    public static void parkUntil(Object blocker, long deadline) {
        //获取当前线程
        Thread t = Thread.currentThread();
        //设置当前线程parkBlocker字段设置为blocker
        setBlocker(t, blocker);
        //阻塞当前线程绝对时间的deadline秒
        UNSAFE.park(true, deadline);
        //当前线程parkBlocker字段设置为null
        setBlocker(t, null);
    }

    //获取当前线程的Blocker值
    public static Object getBlocker(Thread t) {
        //若当前线程为空就抛出异常
        if (t == null)
            throw new NullPointerException();
        //利用unsafe对象获取当前线程的Blocker值
        return UNSAFE.getObjectVolatile(t, parkBlockerOffset);
    }

    //无限阻塞线程，直到有其他线程调用unpark方法
    public static void park() {
        UNSAFE.park(false, 0L);
    }

    //阻塞当前线程nanos
    public static void parkNanos(long nanos) {
        if (nanos > 0)
            UNSAFE.park(false, nanos);
    }

    //将当前线程阻塞绝对时间的deadline秒
    public static void parkUntil(long deadline) {
        UNSAFE.park(true, deadline);
    }


    static final int nextSecondarySeed() {
        int r;
        Thread t = Thread.currentThread();
        if ((r = UNSAFE.getInt(t, SECONDARY)) != 0) {
            r ^= r << 13;   // xorshift
            r ^= r >>> 17;
            r ^= r << 5;
        } else if ((r = java.util.concurrent.ThreadLocalRandom.current().nextInt()) == 0)
            r = 1; // avoid zero
        UNSAFE.putInt(t, SECONDARY, r);
        return r;
    }

    private static final sun.misc.Unsafe UNSAFE;
    private static final long parkBlockerOffset;
    private static final long SEED;
    private static final long PROBE;
    private static final long SECONDARY;

    static {
        try {
            //实例化unsafe对象
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> tk = Thread.class;
            //利用unsafe对象来获取parkBlocker在内存地址的偏移量
            parkBlockerOffset = UNSAFE.objectFieldOffset(tk.getDeclaredField("parkBlocker"));
            //利用unsafe对象来获取threadLocalRandomSeed在内存地址的偏移量
            SEED = UNSAFE.objectFieldOffset(tk.getDeclaredField("threadLocalRandomSeed"));
            //利用unsafe对象来获取threadLocalRandomProbe在内存地址的偏移量
            PROBE = UNSAFE.objectFieldOffset(tk.getDeclaredField("threadLocalRandomProbe"));
            //利用unsafe对象来获取threadLocalRandomSecondarySeed在内存地址的偏移量
            SECONDARY = UNSAFE.objectFieldOffset(tk.getDeclaredField("threadLocalRandomSecondarySeed"));
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

}
