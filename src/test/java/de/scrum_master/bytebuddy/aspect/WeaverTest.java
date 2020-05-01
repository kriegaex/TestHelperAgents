package de.scrum_master.bytebuddy.aspect;

import de.scrum_master.app.Calculator;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.Test;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.UUID;

import static de.scrum_master.testing.TestHelper.isClassLoaded;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static org.junit.Assert.*;

public class WeaverTest {
  private static final Instrumentation INSTRUMENTATION = ByteBuddyAgent.install();

  @Test
  public void weaveLoadedApplicationClass() throws IOException {
    final String CLASS_NAME = "de.scrum_master.app.Calculator";

    // Load application class
    assertFalse(isClassLoaded(CLASS_NAME));
    Calculator calculator = new Calculator();
    assertTrue(isClassLoaded(CLASS_NAME));

    // Create weaver, directly registering a target in the constructor
    Weaver weaver = new Weaver(
      INSTRUMENTATION,
      named(CLASS_NAME),
      isMethod(),
      new AroundAdvice(
        null,
        (target, method, args, proceedMode, returnValue, throwable) -> ((int) returnValue) * 11
      ),
      calculator
    );

    // Registered target is affected by aspect, unregistered one is not
    assertEquals(55, calculator.add(2, 3));
    assertNotEquals(55, new Calculator().add(2, 3));

    // After unregistering the transformer, the target is unaffected by the aspect
    weaver.unregisterTransformer();
    assertEquals(15, calculator.add(7, 8));
  }

  @Test
  public void weaveNotLoadedJREBootstrapClass() throws IOException {
    final String CLASS_NAME = "java.util.UUID";
    final String UUID_TEXT_STUB = "111-222-333-444";

    // Create weaver *before* bootstrap class is loaded (should not make a difference, but check anyway)
    assertFalse(isClassLoaded(CLASS_NAME));
    Weaver weaver = new Weaver(
      INSTRUMENTATION,
      named(CLASS_NAME),
      named("toString"),
      // Skip target method and return fixed result -> a classical stub
      new AroundAdvice(
        (target, method, args) -> false,
        (target, method, args, proceedMode, returnValue, throwable) -> UUID_TEXT_STUB
      )
    );

    // Load bootstrap class by instantiating it
    UUID uuid = UUID.randomUUID();
    assertTrue(isClassLoaded(CLASS_NAME));

    // The target instance has not been registered on the weaver yet
    assertNotEquals(UUID_TEXT_STUB, uuid.toString());

    // After registration on the weaver, the aspect affects the target instance
    weaver.addTarget(uuid);
    assertEquals(UUID_TEXT_STUB, uuid.toString());

    // Another instance is unaffected by the aspect
    assertNotEquals(UUID_TEXT_STUB, UUID.randomUUID().toString());

    // After deregistration, the target instance is also unaffected again
    weaver.removeTarget(uuid);
    assertNotEquals(UUID_TEXT_STUB, uuid.toString());

    // The same instance can be registered again
    weaver.addTarget(uuid);
    assertEquals(UUID_TEXT_STUB, uuid.toString());

    // After unregistering the whole transformer from instrumentation, the aspect is ineffective
    weaver.unregisterTransformer();
    assertNotEquals(UUID_TEXT_STUB, uuid.toString());
  }

  @Test
  public void weaveLoadedJREBootstrapClass() throws IOException {
    final String CLASS_NAME = "java.lang.String";
    final String TEXT = "To be, or not to be, that is the question";

    // Create weaver *after* bootstrap class is loaded (should not make a difference, but check anyway)
    assertTrue(isClassLoaded(CLASS_NAME));
    Weaver weaver = new Weaver(
      INSTRUMENTATION,
      named(CLASS_NAME),
      named("replaceAll").and(takesArguments(String.class, String.class)),
      createAdvice_String_equalsIgnoreCase()
    );

    // Before registering TEXT as an advice target instance, 'replaceAll' behaves normally
    assertEquals("To modify, or not to modify, that is the question", TEXT.replaceAll("be", "modify"));

    // Register target instance on weaver, then check expected aspect behaviour
    weaver.addTarget(TEXT);
    // (1) Proceed to target method without any modifications
    assertEquals("To eat, or not to eat, that is the question", TEXT.replaceAll("be", "eat"));
    // (2) Do not proceed to target method, let aspect modify input text instead
    assertEquals("T0 bε, 0r n0t t0 bε, that is thε quεsti0n", TEXT.replaceAll("be", "skip"));
    // (3) Aspect handles exception, returns dummy result
    assertEquals("caught exception from proceed", TEXT.replaceAll("be", "$1"));
    // (4) Aspect modifies replacement parameter
    assertEquals("To ❤, or not to ❤, that is the question", TEXT.replaceAll("be", "modify"));

    // Negative test: aspect has no effect on a String not registered as a target
    assertEquals("Let it go", "Let it be".replaceAll("be", "go"));
    assertEquals("Let it skip", "Let it be".replaceAll("be", "skip"));
    assertEquals("Let it modify", "Let it be".replaceAll("be", "modify"));

    // After unregistering TEXT as an advice target instance, 'replaceAll' behaves normally again
    weaver.removeTarget(TEXT);
    assertEquals("To modify, or not to modify, that is the question", TEXT.replaceAll("be", "modify"));

    // House-keeping: unregister the transformer from instrumentation, even though there are
    // no targets registered on it anymore
    weaver.unregisterTransformer();
  }

  /**
   * This is an example for a somewhat more complex aspect doing the following:
   *   1. conditionally skip proceeding to target method
   *   2. conditionally modify method argument before proceeding
   *   3. catch exception thrown by traget method and return a value instead
   *   4. in case target method was not called (proceed), return special value
   *   5. otherwise pass through return value from target method
   */
  private AroundAdvice createAdvice_String_equalsIgnoreCase() {
    return new AroundAdvice(
      // Should proceed?
      (target, method, args) -> {
        String replacement = (String) args[1];
        if (replacement.equalsIgnoreCase("skip"))
          return false;
        if (replacement.equalsIgnoreCase("modify"))
          args[1] = "❤";
        return true;
      },

      // Handle result of (optional) proceed
      (target, method, args, proceedMode, returnValue, throwable) -> {
        if (throwable != null)
          return "caught exception from proceed";
        if (!proceedMode)
          return ((String) target).replace("e", "ε").replace("o", "0");
        return returnValue;
      }
    );
  }

}
