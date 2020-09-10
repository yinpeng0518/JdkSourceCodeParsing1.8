package java.lang;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.security.AccessController;
import java.security.AccessControlContext;
import java.security.PrivilegedAction;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.LockSupport;
import sun.nio.ch.Interruptible;
import sun.reflect.CallerSensitive;
import sun.reflect.Reflection;
import sun.security.util.SecurityConstants;

/**
 * {@link "https://blog.csdn.net/ydonghao2/article/details/107453376"}
 * 
 * 多线程编程一直是业界公认比较难也是比较重要，而且是非常基础的一点，掌握它非常重要。Java中多线程编程比较幸福，因为Jdk工程师们考虑了很多东西尽量减少使用Java的难度和复杂度。
 * 其实在C++之中是没有内建多线程的，它依赖操作系统提供这个特性，因为C++为了效率，控制C++适用的应用程序的范围。即C++没有内建多线程，它允许你直接使用操作系统提供的多线程.
 * 这也意味着你在Linux系统下编写的C++多线程代码很有可能在Windows下不能运行，这也是架构师技术选型的时候一个重要考虑点。
 */
public class Thread implements Runnable {

    //确保 registerNatives（） 会在 clinit 中调用
    private static native void registerNatives();  //注册native方法
    static {
        registerNatives();  //registerNatives 是这个类使用到了native方法，这样注册这个类的native方法。
    }

    private volatile String name;  //表示Thread的名字，可以通过Thread类的构造器中的参数来指定线程名字
    private int priority;          //表示线程的优先级（最大值为10，最小值为1，默认值为5）
    private Thread threadQ;
    private long eetop;
    private boolean single_step;                               //是否单步执行此线程
    private boolean daemon = false;  //表示线程是否是守护线程，如果在main线程中创建了一个守护线程，当main方法运行完毕之后，守护线程也会随着消亡。在JVM中，垃圾收集器线程就是守护线程。
    private boolean stillborn = false;                         //JVM 状态
    private Runnable target;                                   //要执行的任务
    private ThreadGroup group;                                 //线程群组
    private ClassLoader contextClassLoader;                    //此线程的上下文类加载器
    private AccessControlContext inheritedAccessControlContext;//此线程继承的AccessControlContext
    private static int threadInitNumber;                       //用于匿名线程的自动编号
    ThreadLocal.ThreadLocalMap threadLocals = null;            //属于这个线程的ThreadLocal值。这个映射由ThreadLocal类维护
    ThreadLocal.ThreadLocalMap inheritableThreadLocals = null; //属于这个线程的InheritableThreadLocal值。这个映射由InheritableThreadLocal类维护
    private long stackSize;                                    //此线程请求的堆栈大小，如果创建者没有指定堆栈大小，则为0。VM可以对这个数字做任何它喜欢的事情;有些vm会忽略它
    private long nativeParkEventPointer;                       //在本机线程终止后持续存在的jvm私有状态
    private long tid;                                          //线程ID
    private static long threadSeqNumber;                       //用于生成线程ID
    private volatile int threadStatus = 0;                     //工具的Java线程状态，初始化表示线程“尚未启动”


    private static synchronized long nextThreadID() {
        return ++threadSeqNumber;
    }

    private static synchronized int nextThreadNum() {
        return threadInitNumber++;
    }

    /**
     * The argument supplied to the current call to
     * java.util.concurrent.locks.LockSupport.park.
     * Set by (private) java.util.concurrent.locks.LockSupport.setBlocker
     * Accessed using java.util.concurrent.locks.LockSupport.getBlocker
     */
    volatile Object parkBlocker;

    /* The object in which this thread is blocked in an interruptible I/O
     * operation, if any.  The blocker's interrupt method should be invoked
     * after setting this thread's interrupt status.
     */
    private volatile Interruptible blocker;
    private final Object blockerLock = new Object();

    /* Set the blocker field; invoked via sun.misc.SharedSecrets from java.nio code
     */
    void blockedOn(Interruptible b) {
        synchronized (blockerLock) {
            blocker = b;
        }
    }

    public final static int MIN_PRIORITY = 1;     //线程可以拥有的最低优先级
    public final static int NORM_PRIORITY = 5;    //分配给线程的默认优先级
    public final static int MAX_PRIORITY = 10;    //线程可以拥有的最高优先级
    public static native Thread currentThread();  //返回对当前执行线程对象的引用

    /**
     * 调用yield方法会让当前线程交出CPU权限，让CPU去执行其他的线程。它跟sleep方法类似，同样不会释放锁。
     * 但是yield不能控制具体的交出CPU的时间，另外，yield方法只能让拥有相同优先级的线程有获取CPU执行时间的机会。
     * 注意，调用yield方法并不会让线程进入阻塞状态，而是让线程重回就绪状态，它只需要等待重新获取CPU执行时间，这一点是和sleep方法不一样的
     */
    public static native void yield();

    /**
     * sleep让线程睡眠，交出CPU，让CPU去执行其他的任务。sleep方法不会释放锁，也就是说如果当前线程持有对某个对象的锁，
     * 则即使调用sleep方法，其他线程也无法访问这个对象。sleep方法相当于让线程进入阻塞状态
     */
    public static native void sleep(long millis) throws InterruptedException;
    public static void sleep(long millis, int nanos) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }

        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException(
                                "nanosecond timeout value out of range");
        }

        if (nanos >= 500000 || (nanos != 0 && millis == 0)) {
            millis++;
        }

        sleep(millis);
    }

  
    private void init(ThreadGroup g, Runnable target, String name, long stackSize) {
        init(g, target, name, stackSize, null, true);
    }

    //初始化一个线程
    private void init(ThreadGroup g,    //线程组
                      Runnable target,  //要执行的任务
                      String name,      //线程的名字
                      long stackSize,   //新线程所需的堆栈大小，或0表示将忽略此参数
                      AccessControlContext acc,
                      boolean inheritThreadLocals) {
        if (name == null) { ////参数校验，线程name不能为null
            throw new NullPointerException("name cannot be null");
        }
        this.name = name;
        Thread parent = currentThread(); //当前线程就是该线程的父线程
        SecurityManager security = System.getSecurityManager();
        if (g == null) {
            //确定它是否是一个applet
            if (security != null) {  //security不为null时，线程所在group为security的group
                g = security.getThreadGroup();
            }
            if (g == null) {   //security为null时，直接使用父线程的group
                g = parent.getThreadGroup();
            }
        }
        g.checkAccess();  //无论是否显式传入threadgroup，都可以检查访问
        //授权
        if (security != null) {
            if (isCCLOverridden(getClass())) {
                security.checkPermission(SUBCLASS_IMPLEMENTATION_PERMISSION);
            }
        }
        g.addUnstarted();
        this.group = g;
        this.daemon = parent.isDaemon();
        this.priority = parent.getPriority();
        if (security == null || isCCLOverridden(parent.getClass()))
            this.contextClassLoader = parent.getContextClassLoader();
        else
            this.contextClassLoader = parent.contextClassLoader;
        this.inheritedAccessControlContext = acc != null ? acc : AccessController.getContext();
        this.target = target;
        setPriority(priority);
        if (inheritThreadLocals && parent.inheritableThreadLocals != null)
            //创建线程共享变量副本
            this.inheritableThreadLocals = ThreadLocal.createInheritedMap(parent.inheritableThreadLocals);
        /* Stash the specified stack size in case the VM cares */
        this.stackSize = stackSize;

        //分配线程id
        tid = nextThreadID();
    }

    //抛出CloneNotSupportedException作为一个线程不能被有意义的克隆
    @Override
    protected Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public Thread() {
        init(null, null, "Thread-" + nextThreadNum(), 0);
    }

    public Thread(Runnable target) {
        init(null, target, "Thread-" + nextThreadNum(), 0);
    }

    Thread(Runnable target, AccessControlContext acc) {
        init(null, target, "Thread-" + nextThreadNum(), 0, acc, false);
    }

    public Thread(ThreadGroup group, Runnable target) {
        init(group, target, "Thread-" + nextThreadNum(), 0);
    }

    public Thread(String name) {
        init(null, null, name, 0);
    }

    public Thread(ThreadGroup group, String name) {
        init(group, null, name, 0);
    }

    public Thread(Runnable target, String name) {
        init(null, target, name, 0);
    }

    public Thread(ThreadGroup group, Runnable target, String name) {
        init(group, target, name, 0);
    }

    public Thread(ThreadGroup group, Runnable target, String name,
                  long stackSize) {
        init(group, target, name, stackSize);
    }

   
    /**
     * 启动一个新线程，在新的线程运行run方法中的代码
     * start方法只是让线程进入就绪状态，里面的代码不一定立刻运行(CPU的时间片还没分给他).
     * 每个线程对象的start方法只能调用一次，如果调用了多次会出现IllegalThreadStateException.
     */
    public synchronized void start() {
        if (threadStatus != 0)
            throw new IllegalThreadStateException();
        group.add(this);
        boolean started = false;
        try {
            start0();
            started = true;
        } finally {
            try {
                if (!started) {
                    group.threadStartFailed(this);
                }
            } catch (Throwable ignore) {
                /* do nothing. If start0 threw a Throwable then
                  it will be passed up the call stack */
            }
        }
    }

    private native void start0();

    /**
     * 新线程启动会调用的方法
     * 如果在构造函数时传递了Runable参数，则线程启动后会调用Runable中的方法，否则默认不执行任何操作。
     * 但可以创建Thread的子类对象，来覆盖默认行为.
     */
    @Override
    public void run() {
        if (target != null) {
            target.run();
        }
    }

    /**
     * This method is called by the system to give a Thread
     * a chance to clean up before it actually exits.
     */
    private void exit() {
        if (group != null) {
            group.threadTerminated(this);
            group = null;
        }
        /* Aggressively null out all reference fields: see bug 4006245 */
        target = null;
        /* Speed the release of some of these resources */
        threadLocals = null;
        inheritableThreadLocals = null;
        inheritedAccessControlContext = null;
        blocker = null;
        uncaughtExceptionHandler = null;
    }

    @Deprecated
    public final void stop() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            checkAccess();
            if (this != Thread.currentThread()) {
                security.checkPermission(SecurityConstants.STOP_THREAD_PERMISSION);
            }
        }
        if (threadStatus != 0) {
            resume(); // Wake up thread if it was suspended; no-op otherwise
        }
        stop0(new ThreadDeath());
    }

    @Deprecated
    public final synchronized void stop(Throwable obj) {
        throw new UnsupportedOperationException();
    }

    /**
     * 打断线程
     * 如果被打断线程正在 sleep，wait，join 会导致被打断的线程抛出 InterruptedException，并清除 打断标记 ；
     * 如果打断的正在运行的线程，则会设置打断标记 ；
     * 打断par 线程, 不会清空打断状态，如果打断标记已经是true, 则park会失效
     */
    public void interrupt() {
        if (this != Thread.currentThread())
            checkAccess();

        synchronized (blockerLock) {
            Interruptible b = blocker;
            if (b != null) {
                interrupt0();      //只是为了设置中断标志
                b.interrupt(this);
                return;
            }
        }
        interrupt0();
    }

    //判断当前线程是否被打断,会清除打断标记
    public static boolean interrupted() {
        return currentThread().isInterrupted(true);
    }

    //判断是否被打断，不会清除打断标记
    public boolean isInterrupted() {
        return isInterrupted(false);
    }

    //测试某个线程是否被中断。根据传递的ClearInterrupted的值重置中断状态
    private native boolean isInterrupted(boolean ClearInterrupted);

    @Deprecated
    public void destroy() {
        throw new NoSuchMethodError();
    }

    //测试此线程是否处于活动状态。如果一个线程已经被启动并且还没有死，那么这个线程就是活的。
    public final native boolean isAlive();

    //挂起(暂停)线程运行
    @Deprecated
    public final void suspend() {
        checkAccess();
        suspend0();
    }

    //恢复线程运行
    @Deprecated
    public final void resume() {
        checkAccess();
        resume0();
    }

    //修改线程优先级，java中规定线程优先级是1~10的整数，较大优先级能提高该线程被CPU调度的几率
    public final void setPriority(int newPriority) {
        ThreadGroup g;
        checkAccess();
        if (newPriority > MAX_PRIORITY || newPriority < MIN_PRIORITY) {
            throw new IllegalArgumentException();
        }
        if((g = getThreadGroup()) != null) {
            if (newPriority > g.getMaxPriority()) {
                newPriority = g.getMaxPriority();
            }
            setPriority0(priority = newPriority);
        }
    }

    //获取线程优先级
    public final int getPriority() {
        return priority;
    }

    //修改线程的名称
    public final synchronized void setName(String name) {
        checkAccess();
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }

        this.name = name;
        if (threadStatus != 0) {  //threadStatus=0 说明线程尚未启动 
            setNativeName(name);
        }
    }

    //获得线程的名称
    public final String getName() {
        return name;
    }

    //返回此线程所属的线程组。如果这个线程已经死亡，这个方法返回null
    public final ThreadGroup getThreadGroup() {
        return group;
    }

    //返回当前线程的线程组及其子线程组中活动线程的估计数量。递归迭代当前线程的线程组中的所有子组。 
    // 返回的值只是一个估计数，因为在此方法遍历内部数据结构时，线程的数量可能会动态变化，并且可能会受到某些系统线程的影响。
    // 此方法主要用于调试和监视目的。 
    public static int activeCount() {
        return currentThread().getThreadGroup().activeCount();
    }

    /**
     * 将当前线程的线程组及其子线程组中的每个活动线程复制到指定数组中。这个方法只是调用当前线程的线程组的ThreadGroup.enumerate(Thread[])方法。 
     * 应用程序可以使用activeCount方法来估计数组应该有多大，但是如果数组太短，容纳不了所有线程，那么额外的线程将被忽略。
     * 如果获取当前线程的线程组及其子线程组中的每个活动线程非常重要，那么调用者应该验证返回的int值是否严格小于tarray的长度。 
     * 由于此方法中固有的竞态条件，建议仅将此方法用于调试和监视目的。
     */
    public static int enumerate(Thread tarray[]) {
        return currentThread().getThreadGroup().enumerate(tarray);
    }
 
    @Deprecated
    public native int countStackFrames();

    /**
     * 等待线程死亡的时间最多为millis毫秒。超时为0意味着永远等待。 
     * 这个实现使用这个的循环。在此条件下等待呼叫，我还活着。当线程终止此时。
     * 调用notifyAll方法。建议应用程序不要在线程实例上使用wait、notify或notifyAll。
     * @throws  InterruptedException 如果有线程中断了当前线程。抛出此异常时清除当前线程的中断状态
     */
    public final synchronized void join(long millis) throws InterruptedException {
        long base = System.currentTimeMillis();
        long now = 0;

        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }

        if (millis == 0) {
            while (isAlive()) {
                wait(0);
            }
        } else {
            while (isAlive()) {
                long delay = millis - now;
                if (delay <= 0) {
                    break;
                }
                wait(delay);
                now = System.currentTimeMillis() - base;
            }
        }
    }

    public final synchronized void join(long millis, int nanos) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException(
                                "nanosecond timeout value out of range");
        }
        if (nanos >= 500000 || (nanos != 0 && millis == 0)) {
            millis++;
        }
        join(millis);
    }

    //等待线程运行结束
    public final void join() throws InterruptedException {
        join(0);
    }

    /**
     * Prints a stack trace of the current thread to the standard error stream.
     * This method is used only for debugging.
     *
     * @see     Throwable#printStackTrace()
     */
    public static void dumpStack() {
        new Exception("Stack trace").printStackTrace();
    }

    //将此线程标记为守护线程或用户线程。当运行的线程都是守护进程线程时，Java虚拟机将退出。必须在线程启动之前调用此方法。 
    public final void setDaemon(boolean on) {
        checkAccess();
        if (isAlive()) {
            throw new IllegalThreadStateException();
        }
        daemon = on;
    }
    
    //测试此线程是否为守护线程
    public final boolean isDaemon() {
        return daemon;
    }

    //确定当前运行的线程是否具有修改此线程的权限。 如果存在安全管理器，则以该线程作为其参数调用其checkAccess方法。这可能会导致抛出SecurityException
    public final void checkAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkAccess(this);
        }
    }
    public String toString() {
        ThreadGroup group = getThreadGroup();
        if (group != null) {
            return "Thread[" + getName() + "," + getPriority() + "," +
                           group.getName() + "]";
        } else {
            return "Thread[" + getName() + "," + getPriority() + "," +
                            "" + "]";
        }
    }

    @CallerSensitive
    public ClassLoader getContextClassLoader() {
        if (contextClassLoader == null)
            return null;
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            ClassLoader.checkClassLoaderPermission(contextClassLoader,
                                                   Reflection.getCallerClass());
        }
        return contextClassLoader;
    }

    public void setContextClassLoader(ClassLoader cl) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission("setContextClassLoader"));
        }
        contextClassLoader = cl;
    }

    
    //当且仅当当前线程持有指定对象上的监视器锁时，返回true。 该方法允许程序断言当前线程已经持有指定的锁: 断言Thread.holdsLock (obj);
    public static native boolean holdsLock(Object obj);

    private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];

    /**
     * 返回表示此线程的堆栈转储的堆栈跟踪元素数组。
     * 如果这个线程还没有启动，已经启动但还没有被系统计划运行，或者已经终止，这个方法将返回一个零长度的数组。
     * 如果返回的数组长度为非零，则数组的第一个元素表示堆栈的顶部，即序列中最近的方法调用。
     * 数组的最后一个元素表示堆栈的底部，它是序列中最近的方法调用。 
     * 如果存在安全管理器，并且该线程不是当前线程，则使用RuntimePermission(“getStackTrace”)权限调用安全管理器的checkPermission方法，以查看是否可以获取堆栈跟踪。 
     * 在某些情况下，一些虚拟机可能会从堆栈跟踪中省略一个或多个堆栈帧。在极端情况下，不包含与此线程相关的堆栈跟踪信息的虚拟机允许从此方法返回长度为零的数组。
     */
    public StackTraceElement[] getStackTrace() {
        if (this != Thread.currentThread()) {
            // check for getStackTrace permission
            SecurityManager security = System.getSecurityManager();
            if (security != null) {
                security.checkPermission(
                    SecurityConstants.GET_STACK_TRACE_PERMISSION);
            }
            // optimization so we do not call into the vm for threads that
            // have not yet started or have terminated
            if (!isAlive()) {
                return EMPTY_STACK_TRACE;
            }
            StackTraceElement[][] stackTraceArray = dumpThreads(new Thread[] {this});
            StackTraceElement[] stackTrace = stackTraceArray[0];
            // a thread that was alive during the previous isAlive call may have
            // since terminated, therefore not having a stacktrace.
            if (stackTrace == null) {
                stackTrace = EMPTY_STACK_TRACE;
            }
            return stackTrace;
        } else {
            // Don't need JVM help for current thread
            return (new Exception()).getStackTrace();
        }
    }

    /**
     * 返回所有活动线程的堆栈跟踪映射。
     * 映射键是线程，每个映射值是一个StackTraceElement数组，表示相应线程的堆栈转储。返回的堆栈跟踪采用为getStackTrace方法指定的格式。
     * 在调用此方法时，线程可能正在执行。每个线程的堆栈跟踪只表示一个快照，每个堆栈跟踪可以在不同的时间获得。
     * 如果虚拟机没有关于线程的堆栈跟踪信息，则映射值中将返回一个零长度数组。 
     * 如果存在安全管理器，则使用RuntimePermission(“getStackTrace”)权限和RuntimePermission(“modifyThreadGroup”)权限调用安全管理器的checkPermission方法，以查看是否可以获得所有线程的堆栈跟踪。
     */
    public static Map<Thread, StackTraceElement[]> getAllStackTraces() {
        // check for getStackTrace permission
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(
                SecurityConstants.GET_STACK_TRACE_PERMISSION);
            security.checkPermission(
                SecurityConstants.MODIFY_THREADGROUP_PERMISSION);
        }

        // Get a snapshot of the list of all threads
        Thread[] threads = getThreads();
        StackTraceElement[][] traces = dumpThreads(threads);
        Map<Thread, StackTraceElement[]> m = new HashMap<>(threads.length);
        for (int i = 0; i < threads.length; i++) {
            StackTraceElement[] stackTrace = traces[i];
            if (stackTrace != null) {
                m.put(threads[i], stackTrace);
            }
            // else terminated so we don't put it in the map
        }
        return m;
    }


    private static final RuntimePermission SUBCLASS_IMPLEMENTATION_PERMISSION = new RuntimePermission("enableContextClassLoaderOverride");
    private static class Caches {
        static final ConcurrentMap<WeakClassKey,Boolean> subclassAudits = ew ConcurrentHashMap<>();
        static final ReferenceQueue<Class<?>> subclassAuditsQueue = new ReferenceQueue<>();
    }
    private static boolean isCCLOverridden(Class<?> cl) {
        if (cl == Thread.class)
            return false;

        processQueue(Caches.subclassAuditsQueue, Caches.subclassAudits);
        WeakClassKey key = new WeakClassKey(cl, Caches.subclassAuditsQueue);
        Boolean result = Caches.subclassAudits.get(key);
        if (result == null) {
            result = Boolean.valueOf(auditSubclass(cl));
            Caches.subclassAudits.putIfAbsent(key, result);
        }

        return result.booleanValue();
    }
    private static boolean auditSubclass(final Class<?> subcl) {
        Boolean result = AccessController.doPrivileged(
            new PrivilegedAction<Boolean>() {
                public Boolean run() {
                    for (Class<?> cl = subcl;
                         cl != Thread.class;
                         cl = cl.getSuperclass())
                    {
                        try {
                            cl.getDeclaredMethod("getContextClassLoader", new Class<?>[0]);
                            return Boolean.TRUE;
                        } catch (NoSuchMethodException ex) {
                        }
                        try {
                            Class<?>[] params = {ClassLoader.class};
                            cl.getDeclaredMethod("setContextClassLoader", params);
                            return Boolean.TRUE;
                        } catch (NoSuchMethodException ex) {
                        }
                    }
                    return Boolean.FALSE;
                }
            }
        );
        return result.booleanValue();
    }
    private native static StackTraceElement[][] dumpThreads(Thread[] threads);
    private native static Thread[] getThreads();

    //返回此线程的标识符。线程ID是创建该线程时生成的一个正长数字。线程ID是唯一的，并且在其生命周期中保持不变。当一个线程被终止时，这个线程ID可以被重用
    public long getId() {
        return tid;
    }

    //线程状态
    public enum State {
        NEW,  //尚未启动的线程的线程状态
        RUNNABLE, //可运行线程的线程状态,处于runnable状态的线程正在Java虚拟机中执行，但是它可能在等待操作系统中的其他资源，比如处理器
        BLOCKED, //线程正在等待监视器锁,正在synchronized块/方法上等待获取锁,或者调用了Object.wait(),等待重新获得锁进入同步块

        /**
         * 等待线程的线程状态 
         * 一个线程由于调用下列方法之一而处于等待状态:
         *  1. Object.wait with no timeout
         *  2. Thread.join with no timeout
         *  3. LockSupport.park
         * 处于等待状态的线程正在等待另一个线程执行特定的操作
         * 例如，一个在对象上调用object. wait()的线程正在等待另一个线程在该对象上调用object.notify()或object.notifyall()。
         * 调用thread .join()的线程正在等待指定的线程终止。
         */
        WAITING,

        /**
         * 具有指定等待时间的等待线程的线程状态
         * 线程处于定时等待状态，因为调用以下方法之一，具有指定的正等待时间:
         *  1.Thread.sleep
         *  2.Object.wait  with timeout
         *  3.Thread.join  with timeout
         *  4.LockSupport.parkNanos
         *  5.LockSupport.parkUntil
         */
        TIMED_WAITING,
        TERMINATED; //终止线程的线程状态。线程已经完成执行
    }

    //返回此线程的状态。这种方法是为监控系统状态而设计的，不是为同步控制而设计的。
    public State getState() {
        // get current thread state
        return sun.misc.VM.toThreadState(threadStatus);
    }

    // Added in JSR-166

    /**
     * Interface for handlers invoked when a <tt>Thread</tt> abruptly
     * terminates due to an uncaught exception.
     * <p>When a thread is about to terminate due to an uncaught exception
     * the Java Virtual Machine will query the thread for its
     * <tt>UncaughtExceptionHandler</tt> using
     * {@link #getUncaughtExceptionHandler} and will invoke the handler's
     * <tt>uncaughtException</tt> method, passing the thread and the
     * exception as arguments.
     * If a thread has not had its <tt>UncaughtExceptionHandler</tt>
     * explicitly set, then its <tt>ThreadGroup</tt> object acts as its
     * <tt>UncaughtExceptionHandler</tt>. If the <tt>ThreadGroup</tt> object
     * has no
     * special requirements for dealing with the exception, it can forward
     * the invocation to the {@linkplain #getDefaultUncaughtExceptionHandler
     * default uncaught exception handler}.
     *
     * @see #setDefaultUncaughtExceptionHandler
     * @see #setUncaughtExceptionHandler
     * @see ThreadGroup#uncaughtException
     * @since 1.5
     */
    @FunctionalInterface
    public interface UncaughtExceptionHandler {
        /**
         * Method invoked when the given thread terminates due to the
         * given uncaught exception.
         * <p>Any exception thrown by this method will be ignored by the
         * Java Virtual Machine.
         * @param t the thread
         * @param e the exception
         */
        void uncaughtException(Thread t, Throwable e);
    }

    private volatile UncaughtExceptionHandler uncaughtExceptionHandler;
    private static volatile UncaughtExceptionHandler defaultUncaughtExceptionHandler;

    /**
     * Set the default handler invoked when a thread abruptly terminates
     * due to an uncaught exception, and no other handler has been defined
     * for that thread.
     *
     * <p>Uncaught exception handling is controlled first by the thread, then
     * by the thread's {@link ThreadGroup} object and finally by the default
     * uncaught exception handler. If the thread does not have an explicit
     * uncaught exception handler set, and the thread's thread group
     * (including parent thread groups)  does not specialize its
     * <tt>uncaughtException</tt> method, then the default handler's
     * <tt>uncaughtException</tt> method will be invoked.
     * <p>By setting the default uncaught exception handler, an application
     * can change the way in which uncaught exceptions are handled (such as
     * logging to a specific device, or file) for those threads that would
     * already accept whatever &quot;default&quot; behavior the system
     * provided.
     *
     * <p>Note that the default uncaught exception handler should not usually
     * defer to the thread's <tt>ThreadGroup</tt> object, as that could cause
     * infinite recursion.
     *
     * @param eh the object to use as the default uncaught exception handler.
     * If <tt>null</tt> then there is no default handler.
     *
     * @throws SecurityException if a security manager is present and it
     *         denies <tt>{@link RuntimePermission}
     *         (&quot;setDefaultUncaughtExceptionHandler&quot;)</tt>
     *
     * @see #setUncaughtExceptionHandler
     * @see #getUncaughtExceptionHandler
     * @see ThreadGroup#uncaughtException
     * @since 1.5
     */
    public static void setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(
                new RuntimePermission("setDefaultUncaughtExceptionHandler")
                    );
        }

         defaultUncaughtExceptionHandler = eh;
     }

    /**
     * Returns the default handler invoked when a thread abruptly terminates
     * due to an uncaught exception. If the returned value is <tt>null</tt>,
     * there is no default.
     * @since 1.5
     * @see #setDefaultUncaughtExceptionHandler
     * @return the default uncaught exception handler for all threads
     */
    public static UncaughtExceptionHandler getDefaultUncaughtExceptionHandler(){
        return defaultUncaughtExceptionHandler;
    }

    /**
     * Returns the handler invoked when this thread abruptly terminates
     * due to an uncaught exception. If this thread has not had an
     * uncaught exception handler explicitly set then this thread's
     * <tt>ThreadGroup</tt> object is returned, unless this thread
     * has terminated, in which case <tt>null</tt> is returned.
     * @since 1.5
     * @return the uncaught exception handler for this thread
     */
    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return uncaughtExceptionHandler != null ?
            uncaughtExceptionHandler : group;
    }

    /**
     * Set the handler invoked when this thread abruptly terminates
     * due to an uncaught exception.
     * <p>A thread can take full control of how it responds to uncaught
     * exceptions by having its uncaught exception handler explicitly set.
     * If no such handler is set then the thread's <tt>ThreadGroup</tt>
     * object acts as its handler.
     * @param eh the object to use as this thread's uncaught exception
     * handler. If <tt>null</tt> then this thread has no explicit handler.
     * @throws  SecurityException  if the current thread is not allowed to
     *          modify this thread.
     * @see #setDefaultUncaughtExceptionHandler
     * @see ThreadGroup#uncaughtException
     * @since 1.5
     */
    public void setUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        checkAccess();
        uncaughtExceptionHandler = eh;
    }

    /**
     * Dispatch an uncaught exception to the handler. This method is
     * intended to be called only by the JVM.
     */
    private void dispatchUncaughtException(Throwable e) {
        getUncaughtExceptionHandler().uncaughtException(this, e);
    }

    /**
     * Removes from the specified map any keys that have been enqueued
     * on the specified reference queue.
     */
    static void processQueue(ReferenceQueue<Class<?>> queue,
                             ConcurrentMap<? extends
                             WeakReference<Class<?>>, ?> map)
    {
        Reference<? extends Class<?>> ref;
        while((ref = queue.poll()) != null) {
            map.remove(ref);
        }
    }

    //类对象的弱键
    static class WeakClassKey extends WeakReference<Class<?>> {
        /**
         * saved value of the referent's identity hash code, to maintain
         * a consistent hash code after the referent has been cleared
         */
        private final int hash;

        /**
         * Create a new WeakClassKey to the given object, registered
         * with a queue.
         */
        WeakClassKey(Class<?> cl, ReferenceQueue<Class<?>> refQueue) {
            super(cl, refQueue);
            hash = System.identityHashCode(cl);
        }

        /**
         * Returns the identity hash code of the original referent.
         */
        @Override
        public int hashCode() {
            return hash;
        }

        /**
         * Returns true if the given object is this identical
         * WeakClassKey instance, or, if this object's referent has not
         * been cleared, if the given object is another WeakClassKey
         * instance with the identical non-null referent as this one.
         */
        @Override
        public boolean equals(Object obj) {
            if (obj == this)
                return true;

            if (obj instanceof WeakClassKey) {
                Object referent = get();
                return (referent != null) &&
                       (referent == ((WeakClassKey) obj).get());
            } else {
                return false;
            }
        }
    }


    // The following three initially uninitialized fields are exclusively
    // managed by class java.util.concurrent.ThreadLocalRandom. These
    // fields are used to build the high-performance PRNGs in the
    // concurrent code, and we can not risk accidental false sharing.
    // Hence, the fields are isolated with @Contended.

    /** The current seed for a ThreadLocalRandom */
    @sun.misc.Contended("tlr")
    long threadLocalRandomSeed;

    /** Probe hash value; nonzero if threadLocalRandomSeed initialized */
    @sun.misc.Contended("tlr")
    int threadLocalRandomProbe;

    /** Secondary seed isolated from public ThreadLocalRandom sequence */
    @sun.misc.Contended("tlr")
    int threadLocalRandomSecondarySeed;

    /* Some private helper methods */
    private native void setPriority0(int newPriority);
    private native void stop0(Object o);
    private native void suspend0();
    private native void resume0();
    private native void interrupt0();
    private native void setNativeName(String name);
}
