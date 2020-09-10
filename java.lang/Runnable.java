package java.lang;

/**
 * Runnable接口应该由任何类实现，该类的实例将由线程执行。类必须定义一个没有参数的方法，称为run().
 * 
 * 该接口设计是为那些希望在活动状态下执行代码的对象提供一个通用协议.例如，Runnable是由类Thread实现的。活动状态仅仅意味着一个线程已经启动，还没有被停止。
 * 
 * 另外，Runnable提供了在不子类化Thread的情况下使类处于活动状态的方法。实现Runnable的类可以通过实例化Thread实例并将自身作为目标传递来运行，而无需子类化Thread。
 * 在大多数情况下，如果只打算覆盖run()方法而不打算覆盖其他线程方法，那么应该使用Runnable接口。这很重要，因为除非程序员打算修改或增强类的基本行为，否则不应该子类化类。
 */
@FunctionalInterface
public interface Runnable {

    /**
     * 当使用实现接口Runnable的对象创建线程时，启动线程将导致在单独执行的线程中调用对象的run方法。
     * 方法运行的一般约定是它可以采取任何操作。
     */
    public abstract void run();
}