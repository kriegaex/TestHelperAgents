package de.scrum_master.bytebuddy.aspect;

public class StaticInitialiserAroundAdvice extends AroundAdvice<Class<?>> {
  private final Before before;
  private final After after;

  public static final Before BEFORE_DEFAULT = (method) -> true;

  public static final After AFTER_DEFAULT = (method, proceedMode, throwable) -> {
    if (throwable != null)
      throw throwable;
  };

  public StaticInitialiserAroundAdvice(Before before, After after) {
    this.before = before == null ? BEFORE_DEFAULT : before;
    this.after = after == null ? AFTER_DEFAULT : after;
  }

  public boolean before(Class<?> clazz) {
    return before.apply(clazz);
  }

  public void after(Class<?> clazz, boolean proceedMode, Throwable throwable) throws Throwable {
    after.apply(clazz, proceedMode, throwable);
  }

  public interface Before {
    /**
     * @param clazz class for which static class initialiser (static block) is to be executed
     * @return true if intercepted static initialiser should be called, false if you want to skip the call
     */
    boolean apply(Class<?> clazz);
  }

  public interface After {
    /**
     * @param clazz       class for which static initialiser (static block) was to be executed
     * @param proceedMode true if intercepted static initialiser was called, false if it was skipped because
     *                    {@link Before#apply(Class)} returned false
     * @param throwable   exception thrown by intercepted static initialiser, if any
     * @throws Throwable Feel free to not throw any exception, to re-throw <i>throwable</i> or to throw any other
     *                   exception type. Note: static initialisers normally should not throw any exceptions.
     */
    void apply(Class<?> clazz, boolean proceedMode, Throwable throwable)
      throws Throwable;
  }
}
