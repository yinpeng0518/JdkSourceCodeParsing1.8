package java.util.concurrent;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author Doug Lea
 * @since 1.5
 */
public class ScheduledThreadPoolExecutor extends ThreadPoolExecutor implements ScheduledExecutorService {

    // 是否在线程池处于正在关闭的状态后继续执行已经存在的周期任务，默认为false
    private volatile boolean continueExistingPeriodicTasksAfterShutdown;

    // 是否在线程池处于正在关闭的状态后继续执行已经存在的延迟任务，默认为true
    private volatile boolean executeExistingDelayedTasksAfterShutdown = true;

    // 当任务被取消后是否应当从任务队列中移除
    volatile boolean removeOnCancel;

    // 为相同延时的任务提供的顺序编号 保证任务之间的FIFO顺序
    private static final AtomicLong sequencer = new AtomicLong();

    private class ScheduledFutureTask<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {

        // 当两个任务有相同的延迟时间时,按照FIFO的顺序入队
        // sequenceNumber就是为相同延时任务提供的顺序编号
        private final long sequenceNumber;

        // 任务可以执行时的时间,纳秒级,通过triggerTime方法计算得出.
        private volatile long time;

        // 任务的执行周期时间 纳秒级
        // 1. 正数表示固定速率执行(为scheduleAtFixedRate提供服务)，
        // 2. 负数表示固定延迟执行(为scheduleWithFixedDelay提供服务)，
        // 3. 0表示不重复任务
        private final long period;

        // 重新入队的任务 通过reExecutePeriodic方法入队重新排序
        RunnableScheduledFuture<V> outerTask = this;

        // 延迟队列的索引，以支持更快的取消操作
        int heapIndex;

        // 使用给定的基于 nanoTime 的触发时间创建一次性动作
        ScheduledFutureTask(Runnable r, V result, long triggerTime, long sequenceNumber) {
            super(r, result);
            this.time = triggerTime;
            this.period = 0;
            this.sequenceNumber = sequenceNumber;
        }

        ScheduledFutureTask(Runnable r, V result, long triggerTime,
                            long period, long sequenceNumber) {
            super(r, result);
            this.time = triggerTime;
            this.period = period;
            this.sequenceNumber = sequenceNumber;
        }

        ScheduledFutureTask(Callable<V> callable, long triggerTime,
                            long sequenceNumber) {
            super(callable);
            this.time = triggerTime;
            this.period = 0;
            this.sequenceNumber = sequenceNumber;
        }

        public long getDelay(TimeUnit unit) {
            return unit.convert(time - System.nanoTime(), NANOSECONDS);
        }

        public int compareTo(Delayed other) {
            if (other == this) // compare zero if same object
                return 0;
            if (other instanceof ScheduledFutureTask) {
                ScheduledFutureTask<?> x = (ScheduledFutureTask<?>) other;
                long diff = time - x.time;
                if (diff < 0)
                    return -1;
                else if (diff > 0)
                    return 1;
                else if (sequenceNumber < x.sequenceNumber)
                    return -1;
                else
                    return 1;
            }
            long diff = getDelay(NANOSECONDS) - other.getDelay(NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        /**
         * Returns {@code true} if this is a periodic (not a one-shot) action.
         *
         * @return {@code true} if periodic
         */
        public boolean isPeriodic() {
            return period != 0;
        }

        /**
         * Sets the next time to run for a periodic task.
         */
        private void setNextRunTime() {
            long p = period;
            if (p > 0)
                time += p;
            else
                time = triggerTime(-p);
        }

        public boolean cancel(boolean mayInterruptIfRunning) {
            // The racy read of heapIndex below is benign:
            // if heapIndex < 0, then OOTA guarantees that we have surely
            // been removed; else we recheck under lock in remove()
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && removeOnCancel && heapIndex >= 0)
                remove(this);
            return cancelled;
        }

        /**
         * Overrides FutureTask version so as to reset/requeue if periodic.
         */
        public void run() {
            if (!canRunInCurrentRunState(this))
                cancel(false);
            else if (!isPeriodic())
                super.run();
            else if (super.runAndReset()) {
                setNextRunTime();
                reExecutePeriodic(outerTask);
            }
        }
    }

    /**
     * Returns true if can run a task given current run state and
     * run-after-shutdown parameters.
     */
    boolean canRunInCurrentRunState(RunnableScheduledFuture<?> task) {
        if (!isShutdown())
            return true;
        if (isStopped())
            return false;
        return task.isPeriodic()
                ? continueExistingPeriodicTasksAfterShutdown
                : (executeExistingDelayedTasksAfterShutdown
                || task.getDelay(NANOSECONDS) <= 0);
    }

    /**
     * Main execution method for delayed or periodic tasks.  If pool
     * is shut down, rejects the task. Otherwise adds task to queue
     * and starts a thread, if necessary, to run it.  (We cannot
     * prestart the thread to run the task because the task (probably)
     * shouldn't be run yet.)  If the pool is shut down while the task
     * is being added, cancel and remove it if required by state and
     * run-after-shutdown parameters.
     *
     * @param task the task
     */
    private void delayedExecute(RunnableScheduledFuture<?> task) {
        if (isShutdown())
            reject(task);
        else {
            super.getQueue().add(task);
            if (!canRunInCurrentRunState(task) && remove(task))
                task.cancel(false);
            else
                ensurePrestart();
        }
    }

    /**
     * Requeues a periodic task unless current run state precludes it.
     * Same idea as delayedExecute except drops task rather than rejecting.
     *
     * @param task the task
     */
    void reExecutePeriodic(RunnableScheduledFuture<?> task) {
        if (canRunInCurrentRunState(task)) {
            super.getQueue().add(task);
            if (canRunInCurrentRunState(task) || !remove(task)) {
                ensurePrestart();
                return;
            }
        }
        task.cancel(false);
    }

    /**
     * Cancels and clears the queue of all tasks that should not be run
     * due to shutdown policy.  Invoked within super.shutdown.
     */
    @Override
    void onShutdown() {
        BlockingQueue<Runnable> q = super.getQueue();
        boolean keepDelayed =
                getExecuteExistingDelayedTasksAfterShutdownPolicy();
        boolean keepPeriodic =
                getContinueExistingPeriodicTasksAfterShutdownPolicy();
        // Traverse snapshot to avoid iterator exceptions
        // TODO: implement and use efficient removeIf
        // super.getQueue().removeIf(...);
        for (Object e : q.toArray()) {
            if (e instanceof RunnableScheduledFuture) {
                RunnableScheduledFuture<?> t = (RunnableScheduledFuture<?>) e;
                if ((t.isPeriodic()
                        ? !keepPeriodic
                        : (!keepDelayed && t.getDelay(NANOSECONDS) > 0))
                        || t.isCancelled()) { // also remove if already cancelled
                    if (q.remove(t))
                        t.cancel(false);
                }
            }
        }
        tryTerminate();
    }

    /**
     * Modifies or replaces the task used to execute a runnable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param runnable the submitted Runnable
     * @param task     the task created to execute the runnable
     * @param <V>      the type of the task's result
     * @return a task that can execute the runnable
     * @since 1.6
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(
            Runnable runnable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Modifies or replaces the task used to execute a callable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param callable the submitted Callable
     * @param task     the task created to execute the callable
     * @param <V>      the type of the task's result
     * @return a task that can execute the callable
     * @since 1.6
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(
            Callable<V> callable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * The default keep-alive time for pool threads.
     *
     * Normally, this value is unused because all pool threads will be
     * core threads, but if a user creates a pool with a corePoolSize
     * of zero (against our advice), we keep a thread alive as long as
     * there are queued tasks.  If the keep alive time is zero (the
     * historic value), we end up hot-spinning in getTask, wasting a
     * CPU.  But on the other hand, if we set the value too high, and
     * users create a one-shot pool which they don't cleanly shutdown,
     * the pool's non-daemon threads will prevent JVM termination.  A
     * small but non-zero value (relative to a JVM's lifetime) seems
     * best.
     */
    private static final long DEFAULT_KEEPALIVE_MILLIS = 10L;

    // 创建一个指定核心线程数量的线程池
    public ScheduledThreadPoolExecutor(int corePoolSize) {
        super(corePoolSize, Integer.MAX_VALUE,
                DEFAULT_KEEPALIVE_MILLIS, MILLISECONDS,
                new DelayedWorkQueue());
    }

    // 创建一个指定核心线程数量和线程工厂的线程池
    public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, Integer.MAX_VALUE,
                DEFAULT_KEEPALIVE_MILLIS, MILLISECONDS,
                new DelayedWorkQueue(), threadFactory);
    }

    // 创建一个指定核心线程数量和拒绝策略的线程池
    public ScheduledThreadPoolExecutor(int corePoolSize, RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE,
                DEFAULT_KEEPALIVE_MILLIS, MILLISECONDS,
                new DelayedWorkQueue(), handler);
    }

    // 创建一个指定核心线程数量和拒绝策略和线程工厂的线程池
    public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE,
                DEFAULT_KEEPALIVE_MILLIS, MILLISECONDS,
                new DelayedWorkQueue(), threadFactory, handler);
    }

    // 延迟操作的触发时间
    private long triggerTime(long delay, TimeUnit unit) {
        //delay非负处理
        return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
    }

    // 获取当前时间加上延迟时间后的结果。
    // 如果延迟时间超过了上限，会在overflowFree方法中处理
    long triggerTime(long delay) {
        return System.nanoTime() +
                ((delay < (Long.MAX_VALUE >> 1)) ? delay : overflowFree(delay));
    }

    /**
     * Constrains the values of all delays in the queue to be within
     * Long.MAX_VALUE of each other, to avoid overflow in compareTo.
     * This may occur if a task is eligible to be dequeued, but has
     * not yet been, while some other task is added with a delay of
     * Long.MAX_VALUE.
     */
    private long overflowFree(long delay) {
        // 获取队头节点（不移除）
        Delayed head = (Delayed) super.getQueue().peek();
        if (head != null) {
            // 获取队头的剩余延迟时间
            long headDelay = head.getDelay(NANOSECONDS);
            // 能走进本方法中，就说明delay是一个接近long最大值的数。此时判断如果headDelay小于0
            // 就说明延迟时间已经到了或过期了但是还没有执行，并且delay和headDelay的差值小于0，说明headDelay和delay的差值已经超过了long的范围
            if (headDelay < 0 && (delay - headDelay < 0))
                // 此时更新一下delay的值，确保其和headDelay的差值在long的范围内，同时delay也会重新变成一个正数
                delay = Long.MAX_VALUE + headDelay;
        }
        return delay;
    }

    // 创建一个Runnable定时任务，并在delay时间后执行
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        // 非空校验
        if (command == null || unit == null) {
            throw new NullPointerException();
        }

        // 包装任务
        RunnableScheduledFuture<Void> t = decorateTask(command,
                new ScheduledFutureTask<Void>(command, null,
                        triggerTime(delay, unit),
                        sequencer.getAndIncrement()));
        // 延迟执行
        delayedExecute(t);
        return t;
    }

    // 创建一个Callable定时任务，并在delay时间后执行
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (callable == null || unit == null)
            throw new NullPointerException();
        RunnableScheduledFuture<V> t = decorateTask(callable,
                new ScheduledFutureTask<V>(callable,
                        triggerTime(delay, unit),
                        sequencer.getAndIncrement()));
        delayedExecute(t);
        return t;
    }

    // 创建一个Runnable周期任务，在initialDelay时间开始执行，每隔period时间再执行一次
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        if (period <= 0L)
            throw new IllegalArgumentException();
        ScheduledFutureTask<Void> sft =
                new ScheduledFutureTask<Void>(command,
                        null,
                        triggerTime(initialDelay, unit),
                        unit.toNanos(period),
                        sequencer.getAndIncrement());
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        delayedExecute(t);
        return t;
    }

    // 创建一个Runnable周期任务，在initialDelay时间开始执行，以一次任务结束的时间为起点，每隔delay时间再执行一次
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        if (delay <= 0L)
            throw new IllegalArgumentException();
        ScheduledFutureTask<Void> sft =
                new ScheduledFutureTask<Void>(command,
                        null,
                        triggerTime(initialDelay, unit),
                        -unit.toNanos(delay),
                        sequencer.getAndIncrement());
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        delayedExecute(t);
        return t;
    }

    /**
     * Executes {@code command} with zero required delay.
     * This has effect equivalent to
     * {@link #schedule(Runnable, long, TimeUnit) schedule(command, 0, anyUnit)}.
     * Note that inspections of the queue and of the list returned by
     * {@code shutdownNow} will access the zero-delayed
     * {@link ScheduledFuture}, not the {@code command} itself.
     *
     * <p>A consequence of the use of {@code ScheduledFuture} objects is
     * that {@link ThreadPoolExecutor#afterExecute afterExecute} is always
     * called with a null second {@code Throwable} argument, even if the
     * {@code command} terminated abruptly.  Instead, the {@code Throwable}
     * thrown by such a task can be obtained via {@link Future#get}.
     *
     * @throws RejectedExecutionException at discretion of
     *                                    {@code RejectedExecutionHandler}, if the task
     *                                    cannot be accepted for execution because the
     *                                    executor has been shut down
     * @throws NullPointerException       {@inheritDoc}
     */
    public void execute(Runnable command) {
        schedule(command, 0, NANOSECONDS);
    }

    // Override AbstractExecutorService methods

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public Future<?> submit(Runnable task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Runnable task, T result) {
        return schedule(Executors.callable(task, result), 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Callable<T> task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * Sets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, executions will continue until {@code shutdownNow}
     * or the policy is set to {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @param value if {@code true}, continue after shutdown, else don't
     * @see #getContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        continueExistingPeriodicTasksAfterShutdown = value;
        if (!value && isShutdown())
            onShutdown();
    }

    /**
     * Gets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, executions will continue until {@code shutdownNow}
     * or the policy is set to {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @return {@code true} if will continue after shutdown
     * @see #setContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return continueExistingPeriodicTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @param value if {@code true}, execute after shutdown, else don't
     * @see #getExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        executeExistingDelayedTasksAfterShutdown = value;
        if (!value && isShutdown())
            onShutdown();
    }

    /**
     * Gets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @return {@code true} if will execute after shutdown
     * @see #setExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return executeExistingDelayedTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @param value if {@code true}, remove on cancellation, else don't
     * @see #getRemoveOnCancelPolicy
     * @since 1.7
     */
    public void setRemoveOnCancelPolicy(boolean value) {
        removeOnCancel = value;
    }

    /**
     * Gets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @return {@code true} if cancelled tasks are immediately removed
     * from the queue
     * @see #setRemoveOnCancelPolicy
     * @since 1.7
     */
    public boolean getRemoveOnCancelPolicy() {
        return removeOnCancel;
    }

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     *
     * <p>This method does not wait for previously submitted tasks to
     * complete execution.  Use {@link #awaitTermination awaitTermination}
     * to do that.
     *
     * <p>If the {@code ExecuteExistingDelayedTasksAfterShutdownPolicy}
     * has been set {@code false}, existing delayed tasks whose delays
     * have not yet elapsed are cancelled.  And unless the {@code
     * ContinueExistingPeriodicTasksAfterShutdownPolicy} has been set
     * {@code true}, future executions of existing periodic tasks will
     * be cancelled.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        super.shutdown();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution. These tasks are drained (removed)
     * from the task queue upon return from this method.
     *
     * <p>This method does not wait for actively executing tasks to
     * terminate.  Use {@link #awaitTermination awaitTermination} to
     * do that.
     *
     * <p>There are no guarantees beyond best-effort attempts to stop
     * processing actively executing tasks.  This implementation
     * interrupts tasks via {@link Thread#interrupt}; any task that
     * fails to respond to interrupts may never terminate.
     *
     * @return list of tasks that never commenced execution.
     * Each element of this list is a {@link ScheduledFuture}.
     * For tasks submitted via one of the {@code schedule}
     * methods, the element will be identical to the returned
     * {@code ScheduledFuture}.  For tasks submitted using
     * {@link #execute execute}, the element will be a
     * zero-delay {@code ScheduledFuture}.
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        return super.shutdownNow();
    }

    /**
     * Returns the task queue used by this executor.  Access to the
     * task queue is intended primarily for debugging and monitoring.
     * This queue may be in active use.  Retrieving the task queue
     * does not prevent queued tasks from executing.
     *
     * <p>Each element of this queue is a {@link ScheduledFuture}.
     * For tasks submitted via one of the {@code schedule} methods, the
     * element will be identical to the returned {@code ScheduledFuture}.
     * For tasks submitted using {@link #execute execute}, the element
     * will be a zero-delay {@code ScheduledFuture}.
     *
     * <p>Iteration over this queue is <em>not</em> guaranteed to traverse
     * tasks in the order in which they will execute.
     *
     * @return the task queue
     */
    public BlockingQueue<Runnable> getQueue() {
        return super.getQueue();
    }

    // 是基于小顶堆构建的（与DelayQueue和PriorityQueue类似）
    // 负责存储线程池的任务
    static class DelayedWorkQueue extends AbstractQueue<Runnable> implements BlockingQueue<Runnable> {

        // 初始化数组大小
        private static final int INITIAL_CAPACITY = 16;
        // 存储任务的数组
        private RunnableScheduledFuture<?>[] queue = new RunnableScheduledFuture<?>[INITIAL_CAPACITY];
        private final ReentrantLock lock = new ReentrantLock();
        private int size;

        // ScheduledThreadPoolExecutor中使用了Leader-Follower模式。这是一种设计思想，
        // 假如说现在有一堆等待执行的任务（一般是存放在一个队列中排好序），而所有的工作线程中只会有一个是leader线程，其他的线程都是follower线程。
        // 只有leader线程能执行任务，而剩下的follower线程则不会执行任务，它们会处在休眠中的状态。
        // 当leader线程拿到任务后执行任务前，自己会变成follower线程，同时会选出一个新的leader线程，然后才去执行任务。
        // 如果此时有下一个任务，就是这个新的leader线程来执行了，并以此往复这个过程。
        // 当之前那个执行任务的线程执行完毕再回来时，会判断如果此时已经没任务了，又或者有任务但是有其他的线程作为leader线程，那么自己就休眠了；
        // 如果此时有任务但是没有leader线程，那么自己就会重新成为leader线程来执行任务。
        private Thread leader;

        // 当一个新的任务在队列的头部变得可用或者一个新的线程可能需要成为leader时发出条件信号
        private final Condition available = lock.newCondition();

        // 如果 f 是 ScheduledFutureTask，则设置它的 heapIndex
        private static void setIndex(RunnableScheduledFuture<?> f, int idx) {
            if (f instanceof ScheduledFutureTask) {
                ((ScheduledFutureTask) f).heapIndex = idx;
            }
        }

        /**
         * Sifts element added at bottom up to its heap-ordered spot.
         * Call only when holding lock.
         */
        private void siftUp(int k, RunnableScheduledFuture<?> key) {
            while (k > 0) {
                int parent = (k - 1) >>> 1;
                RunnableScheduledFuture<?> e = queue[parent];
                if (key.compareTo(e) >= 0)
                    break;
                queue[k] = e;
                setIndex(e, k);
                k = parent;
            }
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * Sifts element added at top down to its heap-ordered spot.
         * Call only when holding lock.
         */
        private void siftDown(int k, RunnableScheduledFuture<?> key) {
            int half = size >>> 1;
            while (k < half) {
                int child = (k << 1) + 1;
                RunnableScheduledFuture<?> c = queue[child];
                int right = child + 1;
                if (right < size && c.compareTo(queue[right]) > 0)
                    c = queue[child = right];
                if (key.compareTo(c) <= 0)
                    break;
                queue[k] = c;
                setIndex(c, k);
                k = child;
            }
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * Resizes the heap array.  Call only when holding lock.
         */
        private void grow() {
            int oldCapacity = queue.length;
            int newCapacity = oldCapacity + (oldCapacity >> 1); // grow 50%
            if (newCapacity < 0) // overflow
                newCapacity = Integer.MAX_VALUE;
            queue = Arrays.copyOf(queue, newCapacity);
        }

        /**
         * Finds index of given object, or -1 if absent.
         */
        private int indexOf(Object x) {
            if (x != null) {
                if (x instanceof ScheduledFutureTask) {
                    int i = ((ScheduledFutureTask) x).heapIndex;
                    // Sanity check; x could conceivably be a
                    // ScheduledFutureTask from some other pool.
                    if (i >= 0 && i < size && queue[i] == x)
                        return i;
                } else {
                    for (int i = 0; i < size; i++)
                        if (x.equals(queue[i]))
                            return i;
                }
            }
            return -1;
        }

        public boolean contains(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return indexOf(x) != -1;
            } finally {
                lock.unlock();
            }
        }

        public boolean remove(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = indexOf(x);
                if (i < 0)
                    return false;

                setIndex(queue[i], -1);
                int s = --size;
                RunnableScheduledFuture<?> replacement = queue[s];
                queue[s] = null;
                if (s != i) {
                    siftDown(i, replacement);
                    if (queue[i] == replacement)
                        siftUp(i, replacement);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        public int size() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return size;
            } finally {
                lock.unlock();
            }
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        // 获取队头节点
        public RunnableScheduledFuture<?> peek() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return queue[0];
            } finally {
                lock.unlock();
            }
        }

        public boolean offer(Runnable x) {
            if (x == null)
                throw new NullPointerException();
            RunnableScheduledFuture<?> e = (RunnableScheduledFuture<?>) x;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = size;
                if (i >= queue.length)
                    grow();
                size = i + 1;
                if (i == 0) {
                    queue[0] = e;
                    setIndex(e, 0);
                } else {
                    siftUp(i, e);
                }
                if (queue[0] == e) {
                    leader = null;
                    available.signal();
                }
            } finally {
                lock.unlock();
            }
            return true;
        }

        public void put(Runnable e) {
            offer(e);
        }

        public boolean add(Runnable e) {
            return offer(e);
        }

        public boolean offer(Runnable e, long timeout, TimeUnit unit) {
            return offer(e);
        }

        /**
         * Performs common bookkeeping for poll and take: Replaces
         * first element with last and sifts it down.  Call only when
         * holding lock.
         *
         * @param f the task to remove and return
         */
        private RunnableScheduledFuture<?> finishPoll(RunnableScheduledFuture<?> f) {
            int s = --size;
            RunnableScheduledFuture<?> x = queue[s];
            queue[s] = null;
            if (s != 0)
                siftDown(0, x);
            setIndex(f, -1);
            return f;
        }

        public RunnableScheduledFuture<?> poll() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first = queue[0];
                return (first == null || first.getDelay(NANOSECONDS) > 0)
                        ? null
                        : finishPoll(first);
            } finally {
                lock.unlock();
            }
        }

        public RunnableScheduledFuture<?> take() throws InterruptedException {
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for (; ; ) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if (first == null)
                        available.await();
                    else {
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0L)
                            return finishPoll(first);
                        first = null; // don't retain ref while waiting
                        if (leader != null)
                            available.await();
                        else {
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                available.awaitNanos(delay);
                            } finally {
                                if (leader == thisThread)
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                if (leader == null && queue[0] != null)
                    available.signal();
                lock.unlock();
            }
        }

        public RunnableScheduledFuture<?> poll(long timeout, TimeUnit unit)
                throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for (; ; ) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if (first == null) {
                        if (nanos <= 0L)
                            return null;
                        else
                            nanos = available.awaitNanos(nanos);
                    } else {
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0L)
                            return finishPoll(first);
                        if (nanos <= 0L)
                            return null;
                        first = null; // don't retain ref while waiting
                        if (nanos < delay || leader != null)
                            nanos = available.awaitNanos(nanos);
                        else {
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                long timeLeft = available.awaitNanos(delay);
                                nanos -= delay - timeLeft;
                            } finally {
                                if (leader == thisThread)
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                if (leader == null && queue[0] != null)
                    available.signal();
                lock.unlock();
            }
        }

        public void clear() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                for (int i = 0; i < size; i++) {
                    RunnableScheduledFuture<?> t = queue[i];
                    if (t != null) {
                        queue[i] = null;
                        setIndex(t, -1);
                    }
                }
                size = 0;
            } finally {
                lock.unlock();
            }
        }

        public int drainTo(Collection<? super Runnable> c) {
            return drainTo(c, Integer.MAX_VALUE);
        }

        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            Objects.requireNonNull(c);
            if (c == this)
                throw new IllegalArgumentException();
            if (maxElements <= 0)
                return 0;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int n = 0;
                for (RunnableScheduledFuture<?> first;
                     n < maxElements
                             && (first = queue[0]) != null
                             && first.getDelay(NANOSECONDS) <= 0; ) {
                    c.add(first);   // In this order, in case add() throws.
                    finishPoll(first);
                    ++n;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        public Object[] toArray() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return Arrays.copyOf(queue, size, Object[].class);
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T[] toArray(T[] a) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                if (a.length < size)
                    return (T[]) Arrays.copyOf(queue, size, a.getClass());
                System.arraycopy(queue, 0, a, 0, size);
                if (a.length > size)
                    a[size] = null;
                return a;
            } finally {
                lock.unlock();
            }
        }

        public Iterator<Runnable> iterator() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return new Itr(Arrays.copyOf(queue, size));
            } finally {
                lock.unlock();
            }
        }

        /**
         * Snapshot iterator that works off copy of underlying q array.
         */
        private class Itr implements Iterator<Runnable> {
            final RunnableScheduledFuture<?>[] array;
            int cursor;        // index of next element to return; initially 0
            int lastRet = -1;  // index of last element returned; -1 if no such

            Itr(RunnableScheduledFuture<?>[] array) {
                this.array = array;
            }

            public boolean hasNext() {
                return cursor < array.length;
            }

            public Runnable next() {
                if (cursor >= array.length)
                    throw new NoSuchElementException();
                return array[lastRet = cursor++];
            }

            public void remove() {
                if (lastRet < 0)
                    throw new IllegalStateException();
                DelayedWorkQueue.this.remove(array[lastRet]);
                lastRet = -1;
            }
        }
    }
}
