package dev.sarek.agent.constructor_mock;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * The constructor mock registry keeps track of a set of target class names registered to be targets for constructor
 * mocking. Constructor mocking as such only works if the corresponding target class and its super classes have been
 * transformed by {@link ConstructorMockTransformer}.
 */
public class ConstructorMockRegistry {
  /**
   * Numeric Java version like 8, 9, ... 14, determined by parsing string returned by
   * {@code System.getProperty("java.version")}
   */
  public static final int JAVA_VERSION = Integer.parseInt(
    System.getProperty("java.version")
      .replaceFirst("^1[.]", "")
      .replaceFirst("[.].*", "")
  );
  /**
   * Specifies if the Java version is greater or equal 9, e.g. 9, 10, 11, ...
   */
  public static final boolean IS_JAVA_9 = JAVA_VERSION > 8;

  // TODO:
  //   Should we support support both Class<?> (cLass instances) and String (class names)? The former would be class
  //   loader specific, the latter would apply to all class loaders. Maybe there are situations in which only the class
  //   name is known when at mock configuration time because the actual target class is loaded later by another class
  //   loader.
  private static final Map<Class<?>, BlockingQueue<Object>> registry = new HashMap<>();
  private static final ClassContextExposingSecurityManager securityManager = new ClassContextExposingSecurityManager();

  /**
   * Determine whether a given class has been registered for constructor mocking
   *
   * @param targetClass class for which to find out constructor mocking state
   * @return constructor mocking state for given class
   */
  public static boolean isMock(Class<?> targetClass) {
    return registry.containsKey(targetClass);
  }

  /**
   * Register a class as a constructor mocking target. Of course, constructor mocking as such only works if the
   * corresponding class has been transformed by {@link ConstructorMockTransformer}.
   *
   * @param targetClass class to be registered as a constructor mocking target
   * @return {@code true} if constructor mocking for given class was not active before; {@code false} otherwise
   */
  public static boolean activate(Class<?> targetClass) {
    if (isMock(targetClass))
      return false;
    registry.put(targetClass, new LinkedBlockingQueue<>());
    return true;
  }

  /**
   * Unregister a class as a constructor mocking target.
   *
   * @param targetClass class to be unregistered as a constructor mocking target
   */
  public static void deactivate(Class<?> targetClass) {
    registry.remove(targetClass);
  }

  /**
   * This method is not meant to be called directly by Sarek users. It is used internally and called by constructor
   * instrumentation code generated by {@link ConstructorMockTransformer} in order to find out whether to bypass normal
   * constructor execution (constructor mocking active) or not (constructor mocking inactive) for the object under
   * construction.
   * <p></p>
   * <i>API note: This method must be public because otherwise it cannot be called by constructors in other
   * packages.</i>
   *
   * @param callingConstructorClass an instrumented constructor calling this method is expected to hand over its own
   *                                defining class
   * @return boolean value specifying if the object under construction ought to be a mock or not
   */
  public static int isMockUnderConstruction(Class<?> callingConstructorClass) {
    // TODO primary:
    //   Parameter 'callingConstructorClass' is not used -> maybe delete it again.
    //   Background: It is more or less irrelevant by which constructor this method was called because we have to look
    //   at the top level constructor, i.e. the actual class of the instance under construction. First I though it would
    //   be helpful to know the currently executed constructor, but now it seems I do not need it.
    //
    // TODO secondary:
    //   - Under Java 8, according to Rafael Winterhalter it would be quicker and more efficient (as in not
    //     materialising the whole stack) to use sun.misc.JavaLangAccess, e.g.
    //       sun.misc.SharedSecrets.getJavaLangAccess().getStackTraceElement(new Throwable(), 2)
    //   - Java 9+ has the Stack Walking API (https://www.baeldung.com/java-9-stackwalking-api).
    //   - In oder to dynamically switch between the two, look into method handles for dispatching to the appropriate
    //     API (https://www.baeldung.com/java-method-handles).

    StackTraceElement[] stackTrace = new Throwable().getStackTrace();
    Class<?>[] stackTraceClasses = securityManager.getClassContextPretty();

    // Caveat: stackTrace.length >= stackTraceClasses.length because reflective method invocation frames are being
    // omitted when generating the class context array. But that should not be an impediment when only parsing the
    // constructor calls on top of the stack because the diverging frames are right below those calls.
    // System.out.printf(
    //   "Stack trace size = %s, class context size = %s%n",
    //   stackTrace.length, stackTraceClasses.length
    // );

    int constructorIndex = -1;
    for (int i = 1; i < stackTrace.length; i++) {
      if (!stackTrace[i].getMethodName().equals("<init>"))
        break;
      // Stack trace must be in sync with class context
      // assert stackTrace[i].getClassName().equals(stackTraceClasses[i].getName());
      constructorIndex = i;
    }
    return constructorIndex > 0 && isMock(stackTraceClasses[constructorIndex])
      ? constructorIndex
      : -1;
  }

  /**
   * Check for available mock instances via synchronous polling according to {@link Queue#poll()}. In asynchronous
   * scenarios, please use {@link #pollMockInstance(Class, int)} instead.
   * <p></p>
   * <i>API note: The design decision not to use a blocking fetch operation like {@link BlockingQueue#take()} was made
   * under consideration of possible problems users might have, being unaware of any blocking behaviour and/or having
   * bugs in their tests, making tests block forever. This would potentially be hard to debug.</i>
   *
   * @param targetClass class for which to poll mock instance
   * @return mock instance, if available in the queue; {@code null} otherwise
   */
  public static Object pollMockInstance(Class<?> targetClass) {
    return isMock(targetClass)
      ? registry.get(targetClass).poll()
      : null;
  }

  /**
   * Check for available mock instances via asynchronous polling with a timeout. This works according to
   * {@link BlockingQueue#poll(long, TimeUnit)}. In synchronous scenarios, please use {@link #pollMockInstance(Class)}
   * instead.
   * <p></p>
   * <i>API note: The design decision not to use a blocking fetch operation like {@link BlockingQueue#take()} was made
   * under consideration of possible problems users might have, being unaware of any blocking behaviour and/or having
   * bugs in their tests, making tests block forever without. This would potentially be hard to debug.</i>
   *
   * @param targetClass   class for which to poll mock instance
   * @param timeoutMillis polling timeout in milliseconds
   * @return mock instance, if available in the queue before the timeout expires; {@code null} otherwise
   */
  public static Object pollMockInstance(Class<?> targetClass, int timeoutMillis)
    throws InterruptedException
  {
    return isMock(targetClass)
      ? registry.get(targetClass).poll(timeoutMillis, MILLISECONDS)
      : null;
  }

  /**
   * This method is not meant to be called directly by Sarek users. It is used internally and called by constructor
   * instrumentation code generated by {@link ConstructorMockTransformer} in order to register the instance under
   * construction right after its super constructor has returned and the instance has thus moved from <i>"uninitialised
   * this"</i> to initialised state. Immediately after calling this method, the constructor is expected to terminate
   * (return).
   * <p></p>
   * <i>API note: This method must be public because otherwise it cannot be called by constructors in other
   * packages.</i>
   *
   * @param mockInstance mock instance under creation which is to be queued up, so later a user can fetch it by
   *                     {@link #pollMockInstance(Class)} or {@link #pollMockInstance(Class, int)}.
   */
  public static void registerMockInstance(Object mockInstance) {
    // Caveat: Do not log anything here, especially not mock objects with possibly stubbed toString methods. Otherwise
    // you might see strange exceptions in then failing tests.
    registry.get(mockInstance.getClass()).add(mockInstance);
    // TODO:
    //   - add 'unregister' method?
    //   - make registration optional (default: off) via fluent mock API?
  }

  public static class ClassContextExposingSecurityManager extends SecurityManager {
    @Override
    public Class<?>[] getClassContext() {
      return super.getClassContext();
    }

    public Class<?>[] getClassContextPretty() {
      Class<?>[] classContext = super.getClassContext();
      return Arrays.copyOfRange(classContext, 1, classContext.length);
    }
  }

}
