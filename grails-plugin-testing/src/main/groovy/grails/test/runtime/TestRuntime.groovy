package grails.test.runtime;

import groovy.transform.CompileStatic
import groovy.transform.Immutable
import groovy.transform.TypeCheckingMode

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

@CompileStatic
class TestRuntime {
    Set<String> features
    List<TestPlugin> plugins
    private Map<String, Object> registry = [:]
    private boolean runtimeClosed = false
    
    public TestRuntime(Set<String> features, List<TestPlugin> plugins) {
        changeFeaturesAndPlugins(features, plugins)
    }
    
    public void changeFeaturesAndPlugins(Set<String> features, List<TestPlugin> plugins) {
        this.features = new LinkedHashSet<String>(features).asImmutable()
        this.plugins =  new ArrayList<TestPlugin>(plugins).asImmutable()
    }
    
    public Object getValue(String name, Map callerInfo = [:]) {
        if(!containsValueFor(name)) {
            publishEvent("valueMissing", [name: name, callerInfo: callerInfo], [immediateDelivery: true])
        }
        getValueIfExists(name, callerInfo)
    }
    
    public Object getValueIfExists(String name, Map callerInfo = [:]) {
        Object val = registry.get(name)
        if(val instanceof LazyValue) {
            return val.get(callerInfo)
        } else {
            return val
        }
    }
    
    public boolean containsValueFor(String name) {
        registry.containsKey(name)
    }
    
    public Object removeValue(String name) {
        Object value = registry.remove(name)
        publishEvent("valueRemoved", [name: name, value: value, lazy: (value instanceof LazyValue)])
        value
    }
    
    public void putValue(String name, Object value) {
        registry.put(name, value)
        publishEvent("valueChanged", [name: name, value: value, lazy: false])
    }
    
    public void putLazyValue(String name, Closure closure) {
        def lazyValue = new LazyValue(this, name, closure)
        registry.put(name, lazyValue)
        publishEvent("valueChanged", [name: name, value: lazyValue, lazy: true])
    }
    
    @Immutable
    static class LazyValue {
        TestRuntime runtime
        String name
        Closure closure
        
        public Object get(Map callerInfo = [:]) {
            if(closure.getMaximumNumberOfParameters()==1) {
                closure.call(runtime)
            } else if(closure.getMaximumNumberOfParameters()==2) {
                closure.call(runtime, name)
            } else if (closure.getMaximumNumberOfParameters() > 2) {
                closure.call(runtime, name, callerInfo)
            } else {
                closure.call()
            }
        }
    }
    
    protected boolean inEventLoop = false
    protected List<TestEvent> deferredEvents = new ArrayList<TestEvent>()
    
    public void publishEvent(String name, Map arguments = [:], Map extraEventProperties = [:]) {
        doPublishEvent(createEvent([runtime: this, name: name, arguments: arguments] + extraEventProperties))
    }
    
    @CompileStatic(TypeCheckingMode.SKIP)
    protected TestEvent createEvent(Map properties) {
        new TestEvent(properties)
    }

    protected synchronized doPublishEvent(TestEvent event) {
        if(inEventLoop) {
            if(event.immediateDelivery) {
                List<TestEvent> previousDeferredEvents = deferredEvents
                try {
                    deferredEvents = new ArrayList<TestEvent>()
                    processEvents(event)
                } finally {
                    deferredEvents = previousDeferredEvents
                }
            } else {
                deferredEvents.add(event)
            }
        } else {
            try {
                inEventLoop = true
                processEvents(event)
            } finally {
                inEventLoop = false
            }
        }
    }

    private processEvents(TestEvent event) {
        deliverEvent(event)
        executeEventLoop()
    }

    protected executeEventLoop() {
        while(true) {
            List<TestEvent> currentLoopEvents = new ArrayList<TestEvent>(deferredEvents)
            deferredEvents.clear()
            if(currentLoopEvents) {
                for(TestEvent deferredEvent : currentLoopEvents) {
                    deliverEvent(deferredEvent)
                }
            } else {
                break
            }
        }
    }
    
    protected void deliverEvent(TestEvent event) {
        if(event.stopDelivery) {
            return
        }
        for(TestPlugin plugin : (event.reverseOrderDelivery ? plugins.reverse() : plugins)) {
            plugin.onTestEvent(event)
            if(event.stopDelivery) {
                break
            }
        }
    }
    
    protected void before(Description description) {
        publishEvent("before", [description: description], [immediateDelivery: true])
    }

    protected void after(Description description, Throwable throwable) {
        publishEvent("after", [description: description, throwable: throwable], [immediateDelivery: true, reverseOrderDelivery: true])
    }

    protected void beforeClass(Description description) {
        publishEvent("beforeClass", [description: description], [immediateDelivery: true])
    }

    protected void afterClass(Description description, Throwable throwable) {
        publishEvent("afterClass", [description: description, throwable: throwable], [immediateDelivery: true, reverseOrderDelivery: true])
        close()
    }

    private synchronized void close() {
        if(!runtimeClosed) {
            for(TestPlugin plugin : plugins) {
                try {
                    plugin.close(this)
                } catch (Exception e) {
                    // ignore exceptions
                }
            }
            registry.clear()
            plugins = null
            runtimeClosed = true
            TestRuntimeFactory.removeRuntime(this)
        }
    }

    public void setUp(Object testInstance) {
        beforeClass(Description.createSuiteDescription(testInstance.getClass()))
        before(Description.createTestDescription(testInstance.getClass(), "setUp"))
    }

    public void tearDown(Object testInstance) {
        after(Description.createTestDescription(testInstance.getClass(), "tearDown"), null)
        afterClass(Description.createSuiteDescription(testInstance.getClass()), null)
    }

    public boolean isClosed() {
        return runtimeClosed;
    }
}
