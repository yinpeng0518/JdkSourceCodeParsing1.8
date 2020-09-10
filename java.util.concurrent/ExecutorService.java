package java.util.concurrent;
import java.util.List;
import java.util.Collection;

/**
 * @since 1.5
 * @author Doug Lea
 */
public interface ExecutorService extends Executor {

    /**
     * 关闭线程池，已提交的任务继续执行，不继续接受提交的新任务
     *
     * @throws SecurityException 如果存在一个安全管理器，并且关闭这个ExecutorService可能会操作调用者不允许修改的线程，因为它不持有RuntimePermission(“modifyThread”)，或者安全管理器的checkAccess方法拒绝访问
     */
    void shutdown();

    /**
     * 尝试停止所有正在执行的任务，停止等待任务的处理，并返回等待执行的任务列表。 此方法不等待正在执行的任务终止。使用awaitTermination来完成此操作。
     * 除了尽力尝试停止处理正在积极执行的任务之外，没有其他保证。例如，典型的实现将通过Thread.interrupt取消，因此任何无法响应中断的任务都可能永远不会终止。
     * 
     * @return 正在等待的任务列表
     * @throws SecurityException 如果存在一个安全管理器，并且关闭这个ExecutorService可能会操作调用者不允许修改的线程，因为它不持有RuntimePermission(“modifyThread”)，或者安全管理器的checkAccess方法拒绝访问
     */
    List<Runnable> shutdownNow();

    //线程池是否已关闭
    boolean isShutdown();

     //当executor被shutdown 或shutdownNow，并且所有任务都被执行完，则返回ture，否则返回false
    boolean isTerminated();

    /**
     * 阻塞直到所有任务在关闭请求后完成执行，或超过timeout指定的时间，或当前线程被中断(以最先发生的为准)
     *
     * @param timeout 等待的最大时间
     * @param unit 超时参数的时间单位
     * @return 如果终止此执行程序，则为true; 如果终止前超时已过，则为false
     * @throws InterruptedException 如果在等待时被中断
     */
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * 提交一个返回值的任务以执行，并返回一个表示该任务挂起结果的Future。Future的get方法将在任务成功完成时返回任务的结果。
     * 如果你想立即阻塞等待一个任务，你可以使用表单的结构result = exec.submit(aCallable).get();
     *  
     * @param task 要提交的任务
     * @param <T> 任务结果的类型
     * @throws RejectedExecutionException 如果任务不能被调度执行
     * @throws NullPointerException 如果task为null
     */
    <T> Future<T> submit(Callable<T> task);

    //提交一个可运行任务以执行，并返回一个表示该任务的Future。Future的get方法将在成功完成时返回给定的结果
    <T> Future<T> submit(Runnable task, T result);

    //提交一个可运行任务以执行，并返回一个表示该任务的Future。将来的get方法在成功完成时将返回null
    Future<?> submit(Runnable task);

    /**
     * 执行给定的任务,当所有任务完成时, 返回包含其状态和结果的Future列表,对于返回列表中的每个元素，isDone都为真。
     * 
     * 注意: 
     *      一个已完成的任务可以正常终止，也可以通过抛出异常终止或被取消.
     *      如果在执行此操作时修改了给定的集合，则此方法的结果未定义。
     *
     * @param tasks 任务的集合
     * @param <T> 任务结果的类型
     * @return 表示任务的Future列表，其顺序与迭代器为给定任务列表生成的顺序相同，每个任务都已完成
     * @throws InterruptedException  如果在等待时中断，未完成的任务将被取消
     * @throws NullPointerException 如果任何一个task为null
     * @throws RejectedExecutionException 如果任何一个task不能被调度执行
     */
    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException;

    /**
     * 执行给定的任务,当所有任务完成或超时过期间(以最先发生的为准)时, 返回包含其状态和结果的Future列表。
     * 对于返回列表中的每个元素，isDone都为真。在返回时，未完成的任务将被取消。
     * 
     * 注意: 
     *      一个已完成的任务可以正常终止，也可以通过抛出异常终止或被取消.
     *      如果在执行此操作时修改了给定的集合，则此方法的结果未定义。
     *
     * @param tasks 任务的集合
     * @param timeout 等待的最大时间
     * @param unit 超时参数的时间单位
     * @param <T> 任务结果的类型
     * @return 表示任务的Future列表，其顺序与迭代器为给定任务列表生成的顺序相同。如果操作没有超时，则每个任务都已完成。如果它超时了，这些任务中的一些将没有完成。
     * @throws InterruptedException 如果在等待时中断，未完成的任务将被取消
     * @throws NullPointerException 如果任何一个task为null
     * @throws RejectedExecutionException 如果任何一个task不能被调度执行
     */
    <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException;

    /**
     * 执行给定的任务，如果有的话，返回一个已成功完成的任务的结果(例如，没有抛出异常)。在正常或异常返回时，未完成的任务将被取消。如果在执行此操作时修改了给定的集合，则此方法的结果未定义。
     *
     * @param tasks 任务的集合
     * @param <T> 任务结果的类型
     * @return 其中一个任务返回的结果
     * @throws InterruptedException 如果在等待时中断
     * @throws NullPointerException 如果要执行的任务或任何元素任务为空
     * @throws IllegalArgumentException 如果tasks为空
     * @throws ExecutionException 如果没有任务成功完成
     * @throws RejectedExecutionException 如果任务不能被调度执行
     */
    <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException;

    /**
     * 执行给定的任务，如果有任务在给定超时结束之前完成，返回已成功完成的任务的结果(例如，没有抛出异常)。在正常或异常返回时，未完成的任务将被取消。如果在执行此操作时修改了给定的集合，则此方法的结果未定义。 
     *
     * @param tasks 任务的集合
     * @param timeout  等待的最大时间
     * @param unit  超时参数的时间单位
     * @param <T> 任务结果的类型
     * @return 其中一个任务返回的结果
     * @throws InterruptedException 如果在等待时中断
     * @throws NullPointerException 如果要执行的tasks、unit或任何元素task为null
     * @throws TimeoutException 如果给定的超时在任何任务成功完成之前就已过期
     * @throws ExecutionException 如果没有任务成功完成
     * @throws RejectedExecutionException 如果任务不能被调度执行
     */
    <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                    long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException;
}
