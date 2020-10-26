package java.lang;

/**
 * {@link "https://www.gclearning.cn/views/JDK%E6%BA%90%E7%A0%81%E9%82%A3%E4%BA%9B%E4%BA%8B%E5%84%BF/2020/042912.html#%E5%89%8D%E8%A8%80"}
 *
 * @author unascribed
 * @see java.lang.Class
 * @since JDK1.0
 */
public class Object {

    //其主要作用是将C/C++中的方法映射到Java中的native方法，实现方法命名的解耦。函数的执行是在静态代码块中执行的，在类首次进行加载的时候执行
    private static native void registerNatives();

    static {
        registerNatives();
    }

    //返回运行时对象的Class类型对象，也就是运行时实际的Class类型对象，同样是native方法，反射也是使用其来实现的
    public final native Class<?> getClass();

    //返回对象的哈希码，是一个整数，这个整数是对象根据特定算法计算出来的一个散列值（hash值）
    public native int hashCode();

    //equals方法直接使用==进行比对，也就是比较对象的地址，默认的约定是相同的对象必须具有相同的哈希码
    //两个对象通过equals()判断为true，则这两个对象的hashCode()返回值也必须相同
    //两个对象的hashCode()返回值相同，equals()比较不一定需要为true，可以是不同对象
    public boolean equals(Object obj) {
        return (this == obj);
    }

    /**
     * clone方法是个本地方法，效率很高。
     * 当需要复制一个相同的对象时一般都通过clone来实现，而不是new一个新对象再把原对象的属性值等复制给新对象。
     * Object类中定义的clone方法是protected的，必须在子类中重载这个方法才能使用。
     * clone方法返回的是个Object对象，必须进行强制类型转换才能得到需要的类型。
     * 实现clone方法需要继承Cloneable接口，不继承会抛出CloneNotSupportedException异常
     */
    protected native Object clone() throws CloneNotSupportedException;

    public String toString() {
        return getClass().getName() + "@" + Integer.toHexString(hashCode());
    }

    /**
     * 作者的注释:
     * 唤醒正在此对象的monitor上等待的单个线程，如果有多个线程在该对象上等待，则选择其中一个唤醒。
     * 该选择是任意的，并且可以根据实现情况进行选择。线程通过调用wait方法在对象的monitor上等待。
     * 在当前线程放弃该对象上的锁之前，唤醒的线程将无法继续。唤醒的线程将以通常的方式与任何其他可能正在主动竞争以与此对象进行同步的线程竞争。
     * 此方法只能由作为该对象的monitor的所有者的线程调用。线程通过synchronized成为对象monitor的所有者,也就是获取对象的锁，同一时刻只有一个线程可以获得对象的锁
     *
     * 简单说明下，通知可能等待该对象的对象锁的其他线程。由JVM随机挑选一个处于wait状态的线程
     * 1.在调用notify()之前，线程必须获得该对象的对象级别锁
     * 2.执行完notify()方法后，不会马上释放锁，要直到退出synchronized代码块，当前线程才会释放锁
     * 3.notify()一次只随机通知一个线程进行唤醒
     */
    public final native void notify();

    /**
     * 作者的注释:
     * 唤醒正在此对象的monitor上等待的所有线程，在当前线程放弃对该对象的锁定之前，唤醒的线程将无法继续。
     * 唤醒的线程将以通常的方式与可能正在竞争在此对象上进行同步的任何其他线程竞争，此方法只能由拥有该对象的monitor的所有者的线程调用
     *
     * 和notify功能差不多，只不过是使所有正在等待线程池中等待同一共享资源的全部线程从等待状态退出，进入可运行状态，让它们竞争对象的锁，只有获得锁的线程才能进入就绪状态
     */
    public final native void notifyAll();

    //使当前线程等待，直到另一个线程在对象上调用notify方法或notifyAll方法唤醒等待线程，可以等待指定的时间，在等待过程中可以被中断，这里抛出InterruptedException异常
    public final native void wait(long timeout) throws InterruptedException;

    public final void wait(long timeout, int nanos) throws InterruptedException {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }

        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException(
                    "nanosecond timeout value out of range");
        }

        if (nanos > 0) {
            timeout++;
        }

        wait(timeout);
    }

    public final void wait() throws InterruptedException {
        wait(0);
    }

    /**
     * 当垃圾回收确定不再有对该对象的引用时，由垃圾回收器在对象上调用。子类覆盖finalize方法以处置系统资源或执行其他清除。
     * finalize的一般约定是，当Java虚拟机确定不再有任何手段可以使尚未死亡的任何线程访问该对象时（除非由于执行操作而导致），调用finalize。
     * 简单点说就是在垃圾回收之前调用，但是不推荐使用，了解下就好
     */
    protected void finalize() throws Throwable {
    }
}
