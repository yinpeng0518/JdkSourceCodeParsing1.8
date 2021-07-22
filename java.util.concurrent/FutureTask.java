package java.util.concurrent;

import java.util.concurrent.locks.LockSupport;

/**
 * {@link "https://pdai.tech/md/java/thread/java-thread-x-juc-executor-FutureTask.html"}
 *
 * FutureTask 为 Future 提供了基础实现，如获取任务执行结果(get)和取消任务(cancel)等。
 * 如果任务尚未完成，获取任务执行结果时将会阻塞。一旦执行结束，任务就不能被重启或取消(除非使用runAndReset执行计算)。
 * FutureTask 常用来封装 Callable 和 Runnable，也可以作为一个任务提交到线程池中执行。
 * 除了作为一个独立的类之外，此类也提供了一些功能性函数供我们创建自定义 task 类使用。
 * FutureTask 的线程安全由CAS来保证。
 *
 * @author Doug Lea
 * @since 1.5
 */
public class FutureTask<V> implements RunnableFuture<V> {

    // 任务状态
    private volatile int state;  //确保可见性

    // 表示是个新的任务或者还没被执行完的任务。这是初始状态。
    private static final int NEW = 0;

    // 任务已经执行完成或者执行任务的时候发生异常，但是任务执行结果或者异常原因还没有保存到outcome字段的时候,
    // 状态会从NEW变更到COMPLETING。但是这个状态会时间会比较短，属于中间状态。
    private static final int COMPLETING = 1;

    // 任务已经执行完成并且任务执行结果已经保存到outcome字段,状态会从COMPLETING转换到NORMAL。这是一个最终态。
    private static final int NORMAL = 2;

    // 任务执行发生异常并且异常原因已经保存到outcome字段中后，状态会从COMPLETING转换到EXCEPTIONAL。这是一个最终态。
    private static final int EXCEPTIONAL = 3;

    // 任务还没开始执行或者已经开始执行但是还没有执行完成的时候,用户调用了cancel(false)方法取消任务且不中断任务执行线程，
    // 这个时候状态会从NEW转化为CANCELLED状态。这是一个最终态。
    private static final int CANCELLED = 4;

    // 任务还没开始执行或者已经执行但是还没有执行完成的时候，用户调用了cancel(true)方法取消任务并且要中断任务执行线程但是还没有中断任务执行线程之前，
    // 状态会从NEW转化为INTERRUPTING。这是一个中间状态。
    private static final int INTERRUPTING = 5;

    // 调用interrupt()中断任务执行线程之后状态会从INTERRUPTING转换到INTERRUPTED。这是一个最终态。
    // 有一点需要注意的是，所有值大于COMPLETING的状态都表示任务已经执行完成(任务正常执行完成，任务执行异常或者任务被取消)。
    private static final int INTERRUPTED = 6;

    // 内部持有的callable任务，运行完毕后置空
    private Callable<V> callable;

    // 从get()中返回的结果或抛出的异常
    private Object outcome; // non-volatile, protected by state reads/writes

    // 运行callable的线程(当前任务被线程执行期间,保存当前执行任务的线程对象引用)
    private volatile Thread runner;

    // 使用Treiber栈保存等待线程
    private volatile WaitNode waiters;

    /**
     * 为完成的任务返回结果或抛出异常
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        if (s == NORMAL)
            return (V) x;
        if (s >= CANCELLED) //任务被取消
            throw new CancellationException();

        throw new ExecutionException((Throwable) x); // 任务执行有异常
    }

    // 创建一个FutureTask ，它将在运行时执行给定的Callable
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;
    }

    // 创建一个FutureTask ，它将在运行时执行给定的Runnable ，并且get将在成功完成时返回给定类型的结果
    public FutureTask(Runnable runnable, V result) {
        // 使用装饰者模式将Runnable接口转换为了Callable接口,外部线程通过get()获取任务执行的结果时, 返回 V result
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;
    }

    // 判断任务是否被取消，如果任务在结束(正常执行结束或者执行异常结束)前被取消则返回true，否则返回false。
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    // 判断任务是否已经完成，如果完成则返回true，否则返回false。需要注意的是：任务执行过程中发生异常、任务被取消也属于任务已完成，也会返回true。
    public boolean isDone() {
        return state != NEW;
    }

    /**
     * cancel()方法用来取消异步任务的执行:
     * 1.如果异步任务已经完成或者已经被取消，或者由于某些原因不能取消，则会返回false。
     * 2.如果任务还没有被执行，则会返回true并且异步任务不会被执行。
     * 3.如果任务已经开始执行了但是还没有执行完成,
     * 3.1 若mayInterruptIfRunning为true，则会立即中断执行任务的线程并返回true，
     * 3.2 若mayInterruptIfRunning为false，则会返回true且不会中断任务执行线程。
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!(state == NEW &&
                UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                        mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        t.interrupt();
                } finally { // final state
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    // 获取任务执行结果，如果任务还没完成则会阻塞等待直到任务执行完成。如果任务被取消则会抛出CancellationException异常，
    // 如果任务执行过程发生异常则会抛出ExecutionException异常，如果阻塞等待过程中被中断则会抛出InterruptedException异常。
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        // 当前任务未执行或正在执行或正完成时，外部线程会被阻塞
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        return report(s);
    }

    // 带超时时间的get()版本，如果阻塞等待过程中超时则会抛出TimeoutException异常
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
                (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    // 当此任务转换到状态isDone时调用受保护的方法(无论是正常还是通过取消).默认实现什么都不做.
    // 子类可以覆盖此方法以调用完成回调或执行簿记. 请注意:您可以在此方法的执行内部查询状态,以确定此任务是否已被取消.
    protected void done() {
    }


    // 将此任务结果设置为给定值，除非此任务已被设置或已被取消。
    // 该方法在计算成功完成后由run方法在内部调用。
    protected void set(V v) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) { // 将状态设置为 COMPLETING
            outcome = v;
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // 将状态设置为 NORMAL
            finishCompletion();  //唤醒等待任务执行结果的线程
        }
    }

    // 将异常信息写入 outcome
    protected void setException(Throwable t) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {  // 将状态设置为 COMPLETING
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // 将状态设置为 EXCEPTIONAL
            finishCompletion(); //唤醒等待任务执行结果的线程
        }
    }

    // submit(runnable/callable) -> newTaskFor(runnable/callable) -> execute(task) -> pool
    // 任务执行入口
    public void run() {
        if (state != NEW   // 如果当前任务的以被执行(正常执行或取消)
                || !UNSAFE.compareAndSwapObject(this, runnerOffset, null, Thread.currentThread())) // 当前任务被其他线程抢占了
            return;
        try {
            Callable<V> c = callable;
            // 条件一: 防止提交一个为null的任务
            // 条件二: 防止外部线程取消掉当前任务
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;
                    setException(ex);  // 将异常信息写入 outcome
                }
                if (ran)
                    set(result); // 设置正常执行结果
            }
        } finally {
            runner = null;
            int s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
                !UNSAFE.compareAndSwapObject(this, runnerOffset,
                        null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt
    }


    // 用于记录 Treiber 堆栈中等待线程的简单链表节点。
    // 有关更详细的说明，请参阅其他类，例如 Phaser 和 SynchronousQueue。
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;

        WaitNode() {
            thread = Thread.currentThread();
        }
    }

    // 删除所有等待线程并发出信号，调用 done()，并将可调用对象设为 null
    private void finishCompletion() {
        // assert state > COMPLETING;
        for (WaitNode q; (q = waiters) != null; ) {
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (; ; ) {
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }

        done();

        callable = null;        // to reduce footprint
    }

    // 在中断或超时时等待完成或中止
    private int awaitDone(boolean timed, long nanos) throws InterruptedException {
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;      // 引用当前线程封装成waitNode对象
        boolean queued = false; // 表示当前线程的WaitNode对象有没有入队

        // 自旋
        for (; ; ) {
            // 条件成立: 说明当前线程是是被其他线程使用中断方式唤醒的，会清除打断标记
            if (Thread.interrupted()) {
                removeWaiter(q);  // 当前线程WaitNode出队
                throw new InterruptedException(); // 向get方法抛出中断异常
            }
            //假设当前线程是被其他线程unpark()唤醒的话,会正常自旋 走下面逻辑
            int s = state;  // 获取当前任务的最新状态
            // 条件成立: 说明当前任务执行已经有结果了。。。 可能是好 也可能是坏
            if (s > COMPLETING) {
                if (q != null)
                    q.thread = null; // help gc
                return s;
                // 条件成立: 说明当前任务接近完成状态 当前线程释放cpu 进行下一次抢占cpu
            } else if (s == COMPLETING)
                Thread.yield();
                // 条件成立: 说明是第一次自旋,将当前线程封装成WaitNode对象
            else if (q == null)
                q = new WaitNode();
                // 条件成立: 第二次自旋,当前线程封装成的WaitNode对象还未入队
            else if (!queued)
                // 当前线程封装成的WaitNode对象入队(头插法)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                        q.next = waiters, q);
                // 第三次自旋会到这里
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            } else
                // 当前get操作的线程就会被park住了,线程状态为变为WAITING状态,相当于休眠了
                // 除非有其他线程将你唤醒或者将当前线程中断
                LockSupport.park(this);
        }
    }

    //尝试取消链接超时或中断的等待节点以避免累积垃圾
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (; ; ) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    } else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                            q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;

    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
