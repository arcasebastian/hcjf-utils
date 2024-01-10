package org.hcjf.service;

import org.hcjf.errors.HCJFRuntimeException;
import org.hcjf.errors.HCJFServiceTimeoutException;
import org.hcjf.log.Log;
import org.hcjf.properties.SystemProperties;

import java.util.*;
import java.util.concurrent.*;

/**
 * This abstract class contains all the implementations and
 * the interfaces that describe the behavior of the system service.
 * @author javaito
 * @param <C> Service consumer type
 */
public abstract class Service<C extends ServiceConsumer> {

    protected static final String SERVICE_LOG_TAG = "SERVICE";
    private static final String MAIN_EXECUTOR_NAME = "Main Thread Pool %s";

    private final String serviceName;
    private final ExecutorService serviceExecutor;
    private final Map<String, ExecutorService> registeredExecutors;
    private final Integer priority;

    /**
     * Service constructor.
     * @param serviceName Name of the service, can't be null.
     * @param priority Service execution priority.
     * @throws NullPointerException If the name is null.
     */
    protected Service(String serviceName, Integer priority) {
        if(serviceName == null) {
            throw new NullPointerException("Service name can't be null");
        }
        if(SystemServices.instance.exist(serviceName)) {
            throw new IllegalArgumentException("The service name (" + serviceName + ") is already register");
        }

        this.serviceName = serviceName;
        this.priority = priority;
        this.serviceExecutor = createExecutorService();
        this.registeredExecutors = new HashMap<>();
        init();
        if(!getClass().equals(Log.class)) {
            SystemServices.instance.register(this);
        } else {
            SystemServices.instance.setLog((Log)this);
        }
    }

    /**
     * Creates an instance of executor service using defaults parameters.
     * @return Executor service instance.
     */
    protected ExecutorService createExecutorService() {
        return createExecutorService(
                SystemProperties.getInteger(SystemProperties.Service.THREAD_POOL_CORE_SIZE),
                SystemProperties.getInteger(SystemProperties.Service.THREAD_POOL_MAX_SIZE),
                SystemProperties.getLong(SystemProperties.Service.THREAD_POOL_KEEP_ALIVE_TIME)
        );
    }

    /**
     * Creates an instance of executor service
     * @param corePoolSize Core pool size
     * @param maximumPoolSize Maximum pool size
     * @param keepAliveTime Keep alive time
     * @return Executor service instance.
     */
    protected ExecutorService createExecutorService(Integer corePoolSize, Integer maximumPoolSize, Long keepAliveTime) {
        ExecutorService result;
        if (!SystemProperties.getBoolean(SystemProperties.Service.VIRTUAL_THREAD_POOL)) {
            ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
            threadPoolExecutor.setCorePoolSize(corePoolSize);
            threadPoolExecutor.setMaximumPoolSize(maximumPoolSize);
            threadPoolExecutor.setKeepAliveTime(keepAliveTime, TimeUnit.SECONDS);
            result = threadPoolExecutor;
        } else {
            result = Executors.newVirtualThreadPerTaskExecutor();
            System.out.println("Virtual thread executor for service");
        }
        return result;
    }

    /**
     * Return the internal thread pool threadPoolExecutor of the service.
     * @return Thread pool threadPoolExecutor.
     */
    private ExecutorService getServiceExecutor() {
        return serviceExecutor;
    }

    /**
     * This method execute any callable over service thread with a service session.
     * @param callable Callable to execute.
     * @param <R> Expected result.
     * @return Callable's future.
     */
    protected final <R extends Object> Future<R> fork(Callable<R> callable) {
        return fork(callable, null, getServiceExecutor());
    }

    /**
     * This method register a new thread pool executor into the service instance.
     * @param executorName Executor name.
     * @param executor Executor instance.
     */
    private void registerExecutor(String executorName, ExecutorService executor) {
        if(!executor.equals(serviceExecutor)) {
            if(executorName == null) {
                throw new NullPointerException("Executor name is null");
            }
            synchronized (this) {
                if (!registeredExecutors.containsKey(executorName)) {
                    registeredExecutors.put(executorName, executor);
                }
            }
        }
    }

    /**
     * Returns the service session instance. If the current thread is not a service thread instance then the session
     * instance is the guest session instance.
     * @return Service session instance.
     */
    private ServiceSession getSession() {
        ServiceSession session = ServiceThread.getServiceThreadInstance().getSession();
        if (session == null) {
            session = ServiceSession.getGuestSession();
        }
        return session;
    }

    /**
     * Returns the invoker properties.
     * @return Invoker properties.
     */
    private Map<String,Object> getInvokerProperties() {
        Map<String,Object> result = getSession().getProperties();
        return result;
    }

    /**
     * This method execute any callable over service thread with a service session using an
     * custom thread pool threadPoolExecutor. This thread pool threadPoolExecutor must create only Service thread implementations.
     * @param callable Callable to execute.
     * @param executorName Name of the executor.
     * @param executor Custom thread pool threadPoolExecutor.
     * @param <R> Expected return type.
     * @return Callable's future.
     */
    protected final <R extends Object> Future<R> fork(Callable<R> callable, String executorName, ExecutorService executor) {
        registerExecutor(executorName, executor);
        return executor.submit(new CallableWrapper<>(callable, getSession(), getInvokerProperties()));
    }

    /**
     * This method execute any runnable over service thread with a service session.
     * @param runnable Runnable to execute.
     * @return Runnable's future.
     */
    protected final Future fork(Runnable runnable) {
        return fork(runnable, null, getServiceExecutor());
    }

    /**
     * This method execute any runnnable over service thread with a service session using an
     * custom thread pool threadPoolExecutor. This thread pool threadPoolExecutor must create only Service thread implementations.
     * @param runnable Runnable to execute.
     * @param executorName Name of the executor.
     * @param executor Custom thread pool threadPoolExecutor.
     * @return Runnable's future.
     */
    protected final Future fork(Runnable runnable, String executorName, ExecutorService executor) {
        registerExecutor(executorName, executor);
        return executor.submit(new RunnableWrapper(runnable, getSession(), getInvokerProperties()));
    }

    /**
     * Return the service name.
     * @return Service name.
     */
    public final String getServiceName() {
        return serviceName;
    }

    /**
     * Return the service priority.
     * @return Service priority.
     */
    public final Integer getPriority() {
        return priority;
    }

    /**
     * This method will be called immediately after
     * of the execution of the service's constructor method
     */
    protected void init(){}

    /**
     * This method will be called for the global shutdown process
     * in each stage.
     * @param stage Shutdown stage.
     */
    protected void shutdown(ShutdownStage stage) {}

    /**
     * This method will be called for the global shutdown process
     * when the process try to finalize the registered thread pool executors.
     * @param executor Thread pool threadPoolExecutor to finalize.
     */
    protected void shutdownExecutor(ExecutorService executor) {
        long shutdownTimeout = SystemProperties.getLong(SystemProperties.Service.SHUTDOWN_TIME_OUT);

        //In the first attempt the shutdown procedure wait for all the thread
        //ends naturally.
        executor.shutdown();
        long startTime = System.currentTimeMillis();
        while (!executor.isTerminated() && (System.currentTimeMillis() - startTime) < shutdownTimeout) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }

        if(!executor.isTerminated()) {
            //If some threads do not end naturally then the shutdown procedure
            //send the interrupt signal for all the pool.
            executor.shutdownNow();
            startTime = System.currentTimeMillis();
            while (!executor.isTerminated() &&
                    (System.currentTimeMillis() - startTime) < shutdownTimeout) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    /**
     * This method register the consumer in the service.
     * @param consumer Object with the logic to consume the service.
     * @throws RuntimeException It contains exceptions generated by
     * the particular logic of each implementation.
     */
    public abstract void registerConsumer(C consumer);

    /**
     * Unregister a specific consumer.
     * @param consumer Consumer to unregister.
     */
    public abstract void unregisterConsumer(C consumer);

    /**
     * This method start the global shutdown process.
     */
    public static final void systemShutdown() {
        SystemServices.instance.shutdown();
    }

    /**
     * This method is the gateway to the service subsystem from context out of
     * the hcjf domain.
     * @param runnable Custom runnable.
     * @param session Custom session.
     */
    public static final void run(Runnable runnable, ServiceSession session) {
        run(runnable, session, false, 0);
    }

    /**
     * This method is the gateway to the service subsystem from context out of
     * the hcjf domain.
     * @param runnable Custom runnable.
     * @param session Custom session.
     * @param waitFor Wait fot the execution ends.
     * @param timeout Wait time out, if this value is lower than 0 then the timeout is infinite.
     */
    public static final void run(Runnable runnable, ServiceSession session, boolean waitFor, long timeout) {
        RunnableWrapper serviceRunnable = new RunnableWrapper(runnable, session.getClone());
        Future future = SystemServices.instance.serviceExecutor.submit(serviceRunnable);
        if(waitFor) {
            try {
                if(timeout > 0) {
                    future.get(timeout, TimeUnit.MILLISECONDS);
                } else {
                    future.get();
                }
            } catch (TimeoutException ex) {
                future.cancel(true);
                throw new HCJFServiceTimeoutException("Service run timout", ex);
            } catch (Exception ex) {
                throw new HCJFRuntimeException("Service run fail", ex);
            }
        }
    }

    /**
     * This method execute a callable instance and wait for the response.
     * @param callable Callable instance.
     * @param serviceSession Service session.
     * @param <O> Expected response.
     * @return Result instance.
     */
    public static final <O extends Object> O call(Callable<O> callable, ServiceSession serviceSession) {
        return call(callable, serviceSession, 0);
    }

    /**
     * This method execute a callable instance and wait for the response.
     * @param callable Callable instance.
     * @param serviceSession Service session.
     * @param timeout Max time for the execution.
     * @param <O> Expected response.
     * @return Result instance.
     */
    public static final <O extends Object> O call(Callable<O> callable, ServiceSession serviceSession, long timeout) {
        O result;
        CallableWrapper callableWrapper = new CallableWrapper(callable, serviceSession.getClone());
        Future<O> future = SystemServices.instance.serviceExecutor.submit(callableWrapper);
        try {
            if (timeout > 0) {
                result = future.get(timeout, TimeUnit.MILLISECONDS);
            } else {
                result = future.get();
            }
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new HCJFServiceTimeoutException("Service call timout", ex);
        } catch (Exception ex) {
            throw new HCJFRuntimeException("Service call fail", ex);
        }
        return result;
    }

    /**
     * This internal class contains all the services registered
     * in the system.
     */
    private static class SystemServices {

        private static final SystemServices instance;

        static {
            instance = new SystemServices();
        }

        private final ThreadPoolExecutor serviceExecutor;
        private final Map<String, Service> services;
        private Log log;

        /**
         * Constructor.
         */
        private SystemServices() {
            if (SystemProperties.getBoolean(SystemProperties.Service.STATIC_VIRTUAL_THREAD_POOL)) {
                this.serviceExecutor = (ThreadPoolExecutor) Executors.newCachedThreadPool();
                this.serviceExecutor.setCorePoolSize(SystemProperties.getInteger(SystemProperties.Service.STATIC_THREAD_POOL_CORE_SIZE));
                this.serviceExecutor.setMaximumPoolSize(SystemProperties.getInteger(SystemProperties.Service.STATIC_THREAD_POOL_MAX_SIZE));
                this.serviceExecutor.setKeepAliveTime(SystemProperties.getLong(SystemProperties.Service.STATIC_THREAD_POOL_KEEP_ALIVE_TIME), TimeUnit.SECONDS);
            } else {
                this.serviceExecutor = (ThreadPoolExecutor) Executors.newVirtualThreadPerTaskExecutor();
            }
            services = new HashMap<>();

            //Adding service shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown()));
        }

        /**
         * Set the unique instance of the system log service.
         * @param log Instance of the system log.
         */
        public void setLog(Log log) {
            this.log = log;
        }

        /**
         * This method save the instance of the service in the internal
         * map indexed by the name of the service.
         * @param service Instance of the service.
         */
        private void register(Service service) {
            services.put(service.getServiceName(), service);
            Log.i(Service.SERVICE_LOG_TAG, "Service registered: %s", service.getServiceName());
        }

        /**
         * Return true if the service name exist in the registered services.
         * @param serviceName Name of the service.
         * @return Return true if exist and false in otherwise
         */
        private boolean exist(String serviceName) {
            return services.containsKey(serviceName);
        }

        /**
         * Start the shutdown process for all the services registered
         */
        private void shutdown() {
            Set<Service> sortedServices = new TreeSet<>((s1, s2) -> {
                int result = s1.getPriority() - s2.getPriority();

                if(result == 0) {
                    result = s1.hashCode() - s2.hashCode();
                }

                return result * -1;
            });

            int errors = 0;
            Log.i(Service.SERVICE_LOG_TAG, "Starting shutdown");
            sortedServices.addAll(services.values());
            for(Service<?> service : sortedServices) {
                Log.i(Service.SERVICE_LOG_TAG, "Starting service shutdown (%s)", service.getServiceName());
                Log.i(Service.SERVICE_LOG_TAG, "Starting service shutdown custom process");
                try {
                    service.shutdown(ShutdownStage.START);
                    Log.i(Service.SERVICE_LOG_TAG, "Start stage: Shutdown custom process done");
                } catch (Exception ex) {
                    Log.i(Service.SERVICE_LOG_TAG, "Start stage: Shutdown custom process done with errors", ex);
                    errors++;
                }

                Log.i(Service.SERVICE_LOG_TAG, "Ending custom executors");
                service.registeredExecutors.values().forEach(service::shutdownExecutor);
                Log.i(Service.SERVICE_LOG_TAG, "Custom executors finalized");

                try {
                    service.shutdown(ShutdownStage.END);
                    Log.i(Service.SERVICE_LOG_TAG, "End stage: Shutdown custom process done");
                } catch (Exception ex) {
                    Log.i(Service.SERVICE_LOG_TAG, "End stage: Shutdown custom process done with errors", ex);
                    errors++;
                }

                Log.i(Service.SERVICE_LOG_TAG, "Ending main service threadPoolExecutor");
                service.shutdownExecutor(serviceExecutor);
                Log.i(Service.SERVICE_LOG_TAG, "Main service threadPoolExecutor finalized");
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {}
            try {
                ((Service)log).shutdown(ShutdownStage.START);
                log.shutdownExecutor(serviceExecutor);
                ((Service)log).shutdown(ShutdownStage.END);
            } catch (Exception ex) {
                errors++;
            }

            System.out.println("Shutdown completed! See you");
            Runtime.getRuntime().halt(errors);
        }

    }

    /**
     * Enum all the shutting down stages
     */
    protected enum ShutdownStage {

        START,

        END

    }

    /**
     * This comparator gets priority to service runnable.
     */
    public static class RunnableWrapperComparator implements Comparator<Runnable> {

        @Override
        public int compare(Runnable o1, Runnable o2) {
            int result = (int)(((RunnableWrapper)o1).creationTime - ((RunnableWrapper)o2).creationTime) * -1;

            if(result == 0) {
                result = o1.hashCode() - o2.hashCode();
            }

            return result;
        }
    }

    /**
     * This wrapper encapsulate any other runnable in order to set the environment
     * session on the service thread.
     */
    private static class RunnableWrapper implements Runnable {

        private final Runnable runnable;
        private final ServiceSession session;
        private final Map<String, Object> invokerProperties;
        private final long creationTime;

        public RunnableWrapper(Runnable runnable, ServiceSession session) {
            this(runnable, session, new HashMap<>());
        }

        public RunnableWrapper(Runnable runnable, ServiceSession session, Map<String, Object> invokerProperties) {
            this.runnable = runnable;
            this.invokerProperties = invokerProperties;
            this.creationTime = System.currentTimeMillis();
            if(session != null) {
                this.session = session;
            } else {
                this.session = ServiceSession.getGuestSession();
            }
        }

        @Override
        public void run() {

            try {
                ServiceThread.getServiceThreadInstance().setSession(session);
                if(invokerProperties != null) {
                    session.putAll(invokerProperties);
                }
                runnable.run();
            } finally {
                ServiceThread.getServiceThreadInstance().setSession(null);
            }
        }

    }

    /**
     * This wrapper encapsulate any other callable in order to set the environment
     * session on the service thread.
     */
    private static class CallableWrapper<O extends Object> implements Callable<O> {

        private final Callable<O> callable;
        private final ServiceSession session;
        private final Map<String, Object> invokerProperties;

        public CallableWrapper(Callable<O> callable, ServiceSession session) {
            this(callable, session, new HashMap<>());
        }

        public CallableWrapper(Callable<O> callable, ServiceSession session, Map<String, Object> invokerProperties) {
            this.callable = callable;
            this.invokerProperties = invokerProperties;
            if(session != null) {
                this.session = session;
            } else {
                this.session = ServiceSession.getGuestSession();
            }
        }

        @Override
        public O call() throws Exception {
            try {
                ServiceThread.getServiceThreadInstance().setSession(session);
                if(invokerProperties != null) {
                    session.putAll(invokerProperties);
                }
                return callable.call();
            } finally {
                ServiceThread.getServiceThreadInstance().setSession(null);
            }
        }

    }

}
