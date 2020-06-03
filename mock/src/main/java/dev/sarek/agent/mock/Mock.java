package dev.sarek.agent.mock;

import dev.sarek.agent.aspect.MethodAroundAdvice;
import dev.sarek.agent.aspect.Weaver;
import dev.sarek.agent.constructor_mock.ConstructorMockRegistry;
import dev.sarek.agent.constructor_mock.ConstructorMockTransformer;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class Mock implements AutoCloseable {
  public static Instrumentation INSTRUMENTATION = ByteBuddyAgent.install();

  private ConstructorMockTransformer constructorMockTransformer;
  private Weaver weaver;
  private Set<Class<?>> classes;

  public Mock(Class<?>... classes) throws IOException {
    constructorMockTransformer = new ConstructorMockTransformer(classes);
    INSTRUMENTATION.addTransformer(constructorMockTransformer, true);
    this.classes = Arrays
      .stream(classes)
      .collect(Collectors.toSet());
    this.classes
      .stream()
      .map(Class::getName)
      .forEach(ConstructorMockRegistry::activate);
    // Automatically retransforms, thus also applies constructorMockTransformer -> TODO: test!
    weaver = new Weaver(
      INSTRUMENTATION,
      anyOf(classes),
      // By default exclude some Object methods
      // TODO: think about which Object methods we really need
      // TODO: make list more precise with regard to method signatures
      // TODO: provide an override option for this default black list
      not(
        named("getClass")
          .or(named("hashCode"))
          .or(named("equals"))
          .or(named("clone"))
          .or(named("notify"))
          .or(named("notifyAll"))
          .or(named("wait"))
          .or(named("finalize"))
        //.or(named("toString"))
      ),
      MethodAroundAdvice.MOCK,
      (Object[]) classes
    );
    // INSTRUMENTATION.retransformClasses(classes);
  }

  @Override
  public void close() {
    System.out.println("Closing Mock");
    classes
      .stream()
      .map(Class::getName)
      .forEach(ConstructorMockRegistry::deactivate);
    classes = null;
    INSTRUMENTATION.removeTransformer(constructorMockTransformer);
    constructorMockTransformer = null;
    // Automatically retransforms, thus also unapplies constructorMockTransformer -> TODO: test!
    weaver.unregisterTransformer();
    // INSTRUMENTATION.retransformClasses(classes);
    weaver = null;
    System.out.println("Mock closed");
  }
}