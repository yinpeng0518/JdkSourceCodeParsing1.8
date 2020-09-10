package java.util.concurrent;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

/**
 * 线程池状态装换列表如下：
 *  RUNNING -> SHUTDOWN: 显式调用shutdown()方法，或者隐式调用了finalize()方法里面的shutdown方法。 
 *  (RUNNING或SHUTDOWN) -> STOP: 显式调用shutdownNow()方法时.
 *  SHUTDOWN -> TIDYING: 当线程池和任务队列都为空时. 
 *  STOP -> TIDYING: 当线程池为空时
 *  TIDYING -> TERMINATED: 当terminated() hook方法执行完成时
 * 
 * 线程池任务提交过程： 任务提交的顺序为 corePoolSize –> workQueue –> maximumPoolSize -> handler。
 *     1.如果运行的线程数少于 corePoolSize，则创建新线程来处理任务，即使线程池中的其他线程是空闲的；
 *     2.如果运行的线程数大于等于 corePoolSize，则将任务放入workQueue中，等待有空闲的线程去从workQueue中取任务并处理；
 *     3.当workQueue已经满时，如果运行的线程数小于maximumPoolSize，则创建新的线程去处理提交的任务；
 *     4.当workQueue已经满时，如果运行的线程数大于等于maximumPoolSize且没有空闲线程，则通过handler所指定的拒绝策略来处理任务。
 * 
 * 线程池中的线程执行完当前任务后，会循环到任务队列中取任务继续执行；线程获取队列中任务时会阻塞，直到获取到任务返回；当线程数大于corePoolSize且线程阻塞时间超时，线程就会被销毁。
 * 
 * @since 1.5
 * @author Doug Lea
 */
public class ThreadPoolExecutor extends AbstractExecutorService {
    
    /**
     * (高3位)用来表示线程池的状态，(低29位)用来表示线程的个数
     * 默认是RUNNING状态，线程个数为0
     */
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));

    //后29位用于存放线程数
    private static final int COUNT_BITS = Integer.SIZE - 3; //32-3=29

    /**
     * 000 11111111111111111111111111111
     * 最大线程数：这里得到的是 29 个 1，也就是说线程池的最大线程数是 2^29-1=536870911
     */
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1; 

    /** 高3位表示线程池的状态 */

    // 111 00000000000000000000000000000
    //接受新任务并且处理阻塞队列里的任务
    private static final int RUNNING    = -1 << COUNT_BITS; 
    
    // 000 00000000000000000000000000000
    //拒绝新任务，但是处理阻塞队列里的任务
    private static final int SHUTDOWN   =  0 << COUNT_BITS;

    // 001 00000000000000000000000000000
    //拒绝新任务，并且抛弃阻塞队列里的任务，同时会中断正在处理的任务。
    private static final int STOP       =  1 << COUNT_BITS;  

    // 010 00000000000000000000000000000
    //所有任务都执行完(包含阻塞队列里面的任务)后，当前线程池活动线程数为0，将要调用terminated方法
    private static final int TIDYING    =  2 << COUNT_BITS;  

    // 011 00000000000000000000000000000 
    //终止状态，terminated方法调用完成以后的状态。
    private static final int TERMINATED =  3 << COUNT_BITS; 

    // 将整数 c 的低 29 位修改为 0，获取线程池的状态
    private static int runStateOf(int c)     { return c & ~CAPACITY; }

    // 将整数 c 的高 3 为修改为 0，获取线程池中的线程数
    private static int workerCountOf(int c)  { return c & CAPACITY; }

    //计算ctl新值(现在状态与线程个数)
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    /*
     * Bit field accessors that don't require unpacking ctl.
     * These depend on the bit layout and on workerCount being never negative.
     */

    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    //线程池是否是运行状态
    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    //CAS 工作线程数加1
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    //CAS 工作线程数减1
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    //CAS 工作线程数减1
    private void decrementWorkerCount() {
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }

    /**
     * 等待队列，当线程池中的线程数量大于等于corePoolSize时，把该任务放入等待队列；
     * 比如基于数组有界的ArrayBlockingQueue，基于链表无界的LinkedBlockingQueue，最多只有一个元素同步队列SynchronousQueue，优先级队列PriorityBlockingQueue
     */
    private final BlockingQueue<Runnable> workQueue;

    private final ReentrantLock mainLock = new ReentrantLock();

    //包含池中所有工作线程的集合。仅在持有mainLock锁时访问
    private final HashSet<Worker> workers = new HashSet<Worker>();

    //等待条件
    private final Condition termination = mainLock.newCondition();

    //线程池曾经创建过的最大线程数量。通过这个数据可以知道线程池是否满过，也就是达到了maximumPoolSize
    private int largestPoolSize;

    //已完成任务的计数器。仅在工作线程终止时更新。仅在主锁下访问。
    private long completedTaskCount;

    //创建线程的工厂
    private volatile ThreadFactory threadFactory;

    /**
     * 拒绝策略，对队列满并且线程个数达到maximumPoolSize后采取的策略，
     * 比如AbortPolicy(抛出异常)，CallerRunsPolicy(使用调用者所在的线程来运行任务)，
     * DiscardOldestPolicy(调用poll丢弃一个任务，执行当前任务)，DiscardPolicy(默默丢弃)
     */
    private volatile RejectedExecutionHandler handler;

    //存活时间。如果当前线程池中的线程数量比核心线程数多，并且是闲置状态，则这些闲置的线程能存活的最大时间。
    private volatile long keepAliveTime;

    //如果为false(默认)，则核心线程即使在空闲时也保持活动。如果为真，核心线程使用keepAliveTime来超时等待工作。
    private volatile boolean allowCoreThreadTimeOut;

    //线程池核心线程个数：保持活动的工作线程的最小数量(并且不允许超时等)，除非设置了allowCoreThreadTimeOut，在这种情况下，最小值为零
    private volatile int corePoolSize;

    //线程池最大线程数量。注意，实际的最大值在内部受到容量的限制。
    private volatile int maximumPoolSize;

    //默认的拒绝策略(抛出异常)
    private static final RejectedExecutionHandler defaultHandler = new AbortPolicy();

    private static final RuntimePermission shutdownPerm = new RuntimePermission("modifyThread");

    /* The context to be used when executing the finalizer, or null. */
    private final AccessControlContext acc;

    /**
     * 线程池中的每一个线程被封装成一个Worker对象，线程池维护的其实就是一组Worker对象。
     */
    private final class Worker extends AbstractQueuedSynchronizer implements Runnable {

        private static final long serialVersionUID = 6138294804551838833L;

        // 线程被封装成Worker
        final Thread thread;

        //在创建线程的时候，如果同时指定的需要执行的第一个任务。可以为 null，线程自己到任务队列中取任务执行
        Runnable firstTask;
      
        // 线程完成的任务数
        volatile long completedTasks;

        Worker(Runnable firstTask) {
            setState(-1); // inhibit interrupts until runWorker
            this.firstTask = firstTask;
            this.thread = getThreadFactory().newThread(this); //调用 ThreadFactory 来创建一个新的线程
        }

        // worker工作，调用外部类的 runWorker 方法，循环等待队列中获取任务并执行
        public void run() {
            runWorker(this);
        }

        //是否持有独占锁
        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        public void lock()        { acquire(1); }
        public boolean tryLock()  { return tryAcquire(1); }
        public void unlock()      { release(1); }
        public boolean isLocked() { return isHeldExclusively(); }

        void interruptIfStarted() {
            Thread t;
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }

    private void advanceRunState(int targetState) {
        for (;;) {
            int c = ctl.get();
            if (runStateAtLeast(c, targetState) ||
                ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    //结束线程池，最终将线程池状态设置为TERMINATED。
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();

            /** 
            * 当前线程池的状态为以下几种情况时，直接返回：
            *       1. RUNNING，因为还在运行中，不能停止；
            *       2. TIDYING或TERMINATED，已经关闭了；
            *       3. SHUTDOWN并且等待队列非空，这时要执行完workQueue中的task；
            */
            if (isRunning(c) ||
                runStateAtLeast(c, TIDYING) ||
                (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
                return;
            //如果线程数量不为0，则中断一个空闲的工作线程，并返回    
            if (workerCountOf(c) != 0) { 
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                //尝试设置状态为TIDYING
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        terminated();   //钩子方法，留给子类实现
                    } finally {
                        //设置状态为TERMINATED
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

    private void checkShutdownAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                for (Worker w : workers)
                    security.checkAccess(w.thread);
            } finally {
                mainLock.unlock();
            }
        }
    }

    //中断所有工作线程，无论是否空闲
    private void interruptWorkers() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers)
                w.interruptIfStarted();
        } finally {
            mainLock.unlock();
        }
    }

    //中断空闲线程，onlyOne=true 中断一个； onlyOne=flase 中断全部
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                Thread t = w.thread;
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                if (onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }

    //中断全部空闲线程
    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;

    
    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

   
    void onShutdown() {
    }

    final boolean isRunningOrShutdown(boolean shutdownOK) {
        int rs = runStateOf(ctl.get());
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    //取出阻塞队列中没有被执行的任务并返回
    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        q.drainTo(taskList);
        if (!q.isEmpty()) {
            for (Runnable r : q.toArray(new Runnable[0])) {
                if (q.remove(r))
                    taskList.add(r);
            }
        }
        return taskList;
    }

    /**
     * 方法的主要工作是在线程池中创建一个新的线程并执行：
     *   1.增加线程数量ctl；
     *   2.创建Worker对象来执行任务，每一个Worker对象都会创建一个线程;
     *      参数firstTask：这个新创建的线程需要第一个执行的任务；firstTask==null，表示创建线程，到workQueue中取任务执行；
     *      参数core：true代表使用corePoolSize作为创建线程的界限；false代表使用maximumPoolSize作为界限
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
         // 增加线程数量ctl
        retry:
        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);  //获取运行状态

           /** 
            * 不能创建线程的几种情况：
            * 1. 线程池已关闭且rs == SHUTDOWN，不允许提交任务，且中断正在执行的任务
            * 2. 线程池已关闭且firstTask!=null，
            * 3. 线程池已关闭且workQueue为空
            */
            if (rs >= SHUTDOWN && !(rs == SHUTDOWN && firstTask == null && !workQueue.isEmpty()))
                return false;

            for (;;) {
                int wc = workerCountOf(c); // 获取线程数
                // 判断线程数上限
                if (wc >= CAPACITY || wc >= (core ? corePoolSize : maximumPoolSize))
                    return false;
                 // 尝试增加workerCount，如果成功，则跳出外层for循环    
                if (compareAndIncrementWorkerCount(c))
                    break retry;
                // CAS失败，循环尝试   
                c = ctl.get();  // Re-read ctl

                if (runStateOf(c) != rs)
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
            }
        }

        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
            //创建Worker对象来执行任务，每一个Worker对象都会创建一个线程
            w = new Worker(firstTask);
            final Thread t = w.thread;
            if (t != null) {
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // Recheck while holding lock.
                    // Back out on ThreadFactory failure or if
                    // shut down before lock acquired.
                    int rs = runStateOf(ctl.get());

                   /** 
                    * 判断状态：
                    * 小于 SHUTTDOWN 那就是 RUNNING，最正常的情况
                    * 等于 SHUTDOWN，不接受新的任务但是会继续执行等待队列中的任务，所以要求firstTask == null
                    */
                    if (rs < SHUTDOWN || (rs == SHUTDOWN && firstTask == null)) {
                        if (t.isAlive()) // precheck that t is startable
                            throw new IllegalThreadStateException();
                        workers.add(w);  //添加worker
                        int s = workers.size();
                        //largestPoolSize记录着线程池中出现过的最大线程数量
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }

                // worker添加成功，启动这个worker中的线程
                if (workerAdded) {
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            if (! workerStarted)
                addWorkerFailed(w);
        }
        return workerStarted;
    }

    /**
     * Rolls back the worker thread creation.
     * - removes worker from workers, if present
     * - decrements worker count
     * - rechecks for termination, in case the existence of this
     *   worker was holding up termination
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null)
                workers.remove(w);
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Performs cleanup and bookkeeping for a dying worker. Called
     * only from worker threads. Unless completedAbruptly is set,
     * assumes that workerCount has already been adjusted to account
     * for exit.  This method removes thread from worker set, and
     * possibly terminates the pool or replaces the worker if either
     * it exited due to user task exception or if fewer than
     * corePoolSize workers are running or queue is non-empty but
     * there are no workers.
     *
     * @param w the worker
     * @param completedAbruptly if the worker died due to user exception
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        if (completedAbruptly) // If abrupt, then workerCount wasn't adjusted
            decrementWorkerCount();

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            completedTaskCount += w.completedTasks;
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }

        tryTerminate();

        int c = ctl.get();
        if (runStateLessThan(c, STOP)) {
            if (!completedAbruptly) {
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                if (min == 0 && ! workQueue.isEmpty())
                    min = 1;
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
            }
            addWorker(null, false);
        }
    }

    /**
     * 获取workQueue中的任务
     *   1.正常情况，直接workQueue.take()获取到任务返回；
     *   2.workQueue中没有任务，当前线程阻塞直到获取到任务；
     *   3.getTask()返回 null， runWorker()方法会销毁当前线程，如下情况返回null：
     *      a.状态为SHUTDOWN && workQueue.isEmpty()，任务队列没有任务，且即将关闭线程池，销毁当前线程;
     *      b.状态 >= STOP，关闭线程池，销毁当前线程;
     *      c.当前线程数超过最大maximumPoolSize，销毁当前线程
     *      d.闲线程超时keepAliveTime，需要销毁线程
     */
    private Runnable getTask() {
        boolean timedOut = false; // Did the last poll() time out?

        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);  //获取线程池状态

            /**
            * 两种返回null的情况：
            * 1. rs == SHUTDOWN && workQueue.isEmpty()
            * 2. rs >= STOP
            */
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();  //CAS 操作，减少工作线程数
                return null;
            }

            int wc = workerCountOf(c);

            // Are workers subject to culling?
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            /** 
            * 两种返回null的情况：
            * 1. 当前线程数 wc > maximumPoolSize，return null
            * 2. 空闲线程超时，return null
            */
            if ((wc > maximumPoolSize || (timed && timedOut))
                && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c))
                    return null;
                continue;
            }

            //到 workQueue 中获取任务并返回
            try {
                Runnable r = timed ?
                    workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                    workQueue.take();
                if (r != null)
                    return r;
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }

    /**
     * 循环从等待队列中获取任务并执行
     *   1.获取到新任务就执行；
     *   2.获取不到就阻塞等待新任务；
     *   3.队列中没有任务或空闲线程超时，销毁线程。
     */
    final void runWorker(Worker w) {
        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;
        w.firstTask = null;
        w.unlock(); // allow interrupts
        boolean completedAbruptly = true;
        try {
            /** 
            * 循环调用 getTask() 获取任务
            * 获取到任务就执行，
            * 获取不到就阻塞等待新任务，
            * 返回null任务就销毁当前线程
            */
            while (task != null || (task = getTask()) != null) {
                w.lock();
                // 如果线程池状态大于等于 STOP，中断
                if ((runStateAtLeast(ctl.get(), STOP) ||
                     (Thread.interrupted() &&
                      runStateAtLeast(ctl.get(), STOP))) &&
                    !wt.isInterrupted())
                    wt.interrupt();
                try {
                    beforeExecute(wt, task);  //钩子方法，留给需要的子类实现
                    Throwable thrown = null;
                    try {
                        task.run();    //真正执行任务，执行execute()中传入任务的run方法
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        afterExecute(task, thrown);   //钩子方法，留给需要的子类实现
                    }
                } finally {
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }
            completedAbruptly = false;
        } finally {
            // 如果到这里，需要销毁线程：
            // 1. getTask 返回 null退出while循环，队列中没有任务或空闲线程超时
            // 2. 任务执行过程中发生了异常
            processWorkerExit(w, completedAbruptly);
        }
    }

    // Public constructors and methods
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), defaultHandler);
    }

    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             threadFactory, defaultHandler);
    }

    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), handler);
    }

    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              RejectedExecutionHandler handler) {
        if (corePoolSize < 0 || maximumPoolSize <= 0 || maximumPoolSize < corePoolSize || keepAliveTime < 0) throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null) throw new NullPointerException();

        this.acc = System.getSecurityManager() == null ? null : AccessController.getContext();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime); 
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    /**
     * execute()方法执行过程如下：
     *      1.如果workerCount < corePoolSize，则创建并启动一个线程来执行新提交的任务，即使有空闲线程，也要创建一个新线程；
     *      2.如果workerCount >= corePoolSize，且线程池内的阻塞队列未满，则将任务添加到该阻塞队列中；
     *      3.如果workerCount >= corePoolSize，且线程池内的阻塞队列已满，则创建并启动一个线程来执行新提交的任务；
     *      4.如果workerCount >= maximumPoolSize，且线程池内的阻塞队列已满, 则根据拒绝策略来处理该任务, 默认的处理方式是直接抛异常。
     */
    public void execute(Runnable command) {
        if (command == null){
            throw new NullPointerException();
        }
            
        int c = ctl.get(); //获取线程状态和线程池线程数量

        //当前核心线程数小于corePoolSize，则新建一个线程放入线程池中。注意这里不管核心线程有没有空闲，都会创建线程
        if (workerCountOf(c) < corePoolSize) {
            // 创建线程，并执行command
            if (addWorker(command, true)){
                return;
            }
           
            // 如果添加失败，则重新获取ctl值    
            c = ctl.get();
        }

        // 当前核心线程数大于等于corePoolSize，将任务添加到队列
        if (isRunning(c) && workQueue.offer(command)) {
            int recheck = ctl.get();
            /*
            * 再次检查线程池的运行状态
            * 如果不是运行状态，将command从workQueue中移除，使用拒绝策略处理command
            */
            if (! isRunning(recheck) && remove(command))
                reject(command);
             // 如果有效线程数为0，创建一个线程    
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);
        }
        //当前核心线程数大于等于corePoolSize，且workQueue队列添加任务失败，尝试创建maximumPoolSize中的线程来执行任务
        else if (!addWorker(command, false))
            reject(command);
    }

    
    /**
     * shutdown方法过程：
     *      1.将线程池切换到SHUTDOWN状态；
     *      2.调用interruptIdleWorkers方法请求中断所有空闲的worker；
     *      3.调用tryTerminate尝试结束线程池。
     * 
     * shutdown方法 VS shutdownNow方法:
     *      shutdown方法设置线程池状态为SHUTDOWN，SHUTDOWN状态不再接受新提交的任务，但却可以继续处理阻塞队列中已保存的任务。
     *      shutdownNow方法设置线程池状态为STOP，STOP状态不能接受新任务，也不处理队列中的任务，会中断正在处理任务的线程。
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();       //安全策略判断
            advanceRunState(SHUTDOWN);   //CAS设置线程池状态为SHUTDOWN
            interruptIdleWorkers();      //中断空闲线程
            onShutdown();                //钩子方法，用于ScheduledThreadPoolExecutor
        } finally {
            mainLock.unlock();
        }
        tryTerminate();     //尝试结束线程池
    }

    /**
     * shutdownNow方法过程：
     *      1.将线程池切换到STOP状态；
     *      2.中断所有工作线程，无论是否空闲；
     *      3.取出阻塞队列中没有被执行的任务并返回；
     *      4.调用tryTerminate尝试结束线程池。
     * 
     * shutdown方法 VS shutdownNow方法:
     *      shutdown方法设置线程池状态为SHUTDOWN，SHUTDOWN状态不再接受新提交的任务，但却可以继续处理阻塞队列中已保存的任务。
     *      shutdownNow方法设置线程池状态为STOP，STOP状态不能接受新任务，也不处理队列中的任务，会中断正在处理任务的线程。
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            checkShutdownAccess();      //安全策略判断
            advanceRunState(STOP);      //CAS设置线程池状态为STOP
            interruptWorkers();         //中断所有工作线程，无论是否空闲
            tasks = drainQueue();       //取出阻塞队列中没有被执行的任务并返回
        } finally {
            mainLock.unlock();
        }
        tryTerminate();                 //结束线程池，最终将线程池状态设置为TERMINATED
        return tasks;
    }

    public boolean isShutdown() {
        return ! isRunning(ctl.get());
    }

    /**
     * Returns true if this executor is in the process of terminating
     * after {@link #shutdown} or {@link #shutdownNow} but has not
     * completely terminated.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, causing this executor not
     * to properly terminate.
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        int c = ctl.get();
        return ! isRunning(c) && runStateLessThan(c, TERMINATED);
    }

    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (;;) {
                if (runStateAtLeast(ctl.get(), TERMINATED))
                    return true;
                if (nanos <= 0)
                    return false;
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Invokes {@code shutdown} when this executor is no longer
     * referenced and it has no threads.
     */
    protected void finalize() {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null || acc == null) {
            shutdown();
        } else {
            PrivilegedAction<Void> pa = () -> { shutdown(); return null; };
            AccessController.doPrivileged(pa, acc);
        }
    }

    /**
     * Sets the thread factory used to create new threads.
     *
     * @param threadFactory the new thread factory
     * @throws NullPointerException if threadFactory is null
     * @see #getThreadFactory
     */
    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    /**
     * Returns the thread factory used to create new threads.
     *
     * @return the current thread factory
     * @see #setThreadFactory(ThreadFactory)
     */
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    /**
     * Sets a new handler for unexecutable tasks.
     *
     * @param handler the new handler
     * @throws NullPointerException if handler is null
     * @see #getRejectedExecutionHandler
     */
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    /**
     * Returns the current handler for unexecutable tasks.
     *
     * @return the current handler
     * @see #setRejectedExecutionHandler(RejectedExecutionHandler)
     */
    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    //设置核心池大小
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0)
            throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize)
            interruptIdleWorkers();
        else if (delta > 0) {
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty())
                    break;
            }
        }
    }

    //获得核心线程数的数量
    public int getCorePoolSize() {
        return corePoolSize;
    }
    
    //默认情况下，创建线程池之后，线程池中是没有线程的，需要提交任务之后才会创建线程。如果需要线程池创建之后立即创建线程,可以通过该方法实现
    //初始化一个核心线程
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize &&
            addWorker(null, true);
    }

    /**
     * Same as prestartCoreThread except arranges that at least one
     * thread is started even if corePoolSize is 0.
     */
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if (wc < corePoolSize)
            addWorker(null, true);
        else if (wc == 0)
            addWorker(null, false);
    }

    //默认情况下，创建线程池之后，线程池中是没有线程的，需要提交任务之后才会创建线程。如果需要线程池创建之后立即创建线程,可以通过该方法实现
    //初始化所有核心线程
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true))
            ++n;
        return n;
    }

    /**
     * Returns true if this pool allows core threads to time out and
     * terminate if no tasks arrive within the keepAlive time, being
     * replaced if needed when new tasks arrive. When true, the same
     * keep-alive policy applying to non-core threads applies also to
     * core threads. When false (the default), core threads are never
     * terminated due to lack of incoming tasks.
     *
     * @return {@code true} if core threads are allowed to time out,
     *         else {@code false}
     *
     * @since 1.6
     */
    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    /**
     * Sets the policy governing whether core threads may time out and
     * terminate if no tasks arrive within the keep-alive time, being
     * replaced if needed when new tasks arrive. When false, core
     * threads are never terminated due to lack of incoming
     * tasks. When true, the same keep-alive policy applying to
     * non-core threads applies also to core threads. To avoid
     * continual thread replacement, the keep-alive time must be
     * greater than zero when setting {@code true}. This method
     * should in general be called before the pool is actively used.
     *
     * @param value {@code true} if should time out, else {@code false}
     * @throws IllegalArgumentException if value is {@code true}
     *         and the current keep-alive time is not greater than zero
     *
     * @since 1.6
     */
    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            if (value)
                interruptIdleWorkers();
        }
    }

    //设置线程池最大能创建的线程数目大小
    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        if (workerCountOf(ctl.get()) > maximumPoolSize)
            interruptIdleWorkers();
    }

    //获得线程池允许的线程最大数量
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    //设置生存时间
    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0)
            throw new IllegalArgumentException();
        if (time == 0 && allowsCoreThreadTimeOut())
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        if (delta < 0)
            interruptIdleWorkers();
    }

    //获得生存时间
    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }

    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate(); // In case SHUTDOWN and now empty
        return removed;
    }

    /**
     * 尝试从工作队列中删除所有未来已被取消的任务。此方法可用于存储回收操作，对功能没有其他影响。已取消的任务永远不会执行，
     * 但可能会在工作队列中累积，直到工作线程可以主动删除它们为止。而调用此方法则尝试立即删除它们。但是，如果有其他线程的干扰，此方法可能无法删除任务。
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    it.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            for (Object r : q.toArray())
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    q.remove(r);
        }

        tryTerminate(); // In case SHUTDOWN and now empty
    }


    //线程池当前的线程数量
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return runStateAtLeast(ctl.get(), TIDYING) ? 0 : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    //当前线程池中正在执行任务的线程数量
    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for (Worker w : workers)
                if (w.isLocked())
                    ++n;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    //线程池曾经创建过的最大线程数量。通过这个数据可以知道线程池是否满过，也就是达到了maximumPoolSize
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    //线程池已经执行的和未执行的任务总数
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked())
                    ++n;
            }
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    //线程池已完成的任务数量，该值小于等于taskCount
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers)
                n += w.completedTasks;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    //标识此池及其状态的字符串
    public String toString() {
        long ncompleted;
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c, SHUTDOWN) ? "Running" :
                     (runStateAtLeast(c, TERMINATED) ? "Terminated" :
                      "Shutting down"));
        return super.toString() +
            "[" + rs +
            ", pool size = " + nworkers +
            ", active threads = " + nactive +
            ", queued tasks = " + workQueue.size() +
            ", completed tasks = " + ncompleted +
            "]";
    }

    //实现钩子方法beforeExecute方法，增加一些新操作。
    protected void beforeExecute(Thread t, Runnable r) { }

    //实现钩子方法afterExecute方法，增加一些新操作。
    protected void afterExecute(Runnable r, Throwable t) { }

    //实现钩子方法terminated方法，增加一些新操作。
    protected void terminated() { }

    /* Predefined RejectedExecutionHandlers */

    //用调用者所在的线程来执行任务；
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
      
        public CallerRunsPolicy() { }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    //直接抛出异常，默认策略
    public static class AbortPolicy implements RejectedExecutionHandler {
       
        public AbortPolicy() { }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                                                 " rejected from " +
                                                 e.toString());
        }
    }

    //直接丢弃任务
    public static class DiscardPolicy implements RejectedExecutionHandler {
       
        public DiscardPolicy() { }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            //什么也不做，直接丢弃任务
        }
    }

    //丢弃阻塞队列中靠最前的任务，并执行当前任务
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
       
        public DiscardOldestPolicy() { }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}