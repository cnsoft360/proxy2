package com.github.forax.proxy2;

import static org.objectweb.asm.Opcodes.*;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import sun.misc.Unsafe;

/**
 * A bunch of static factory methods to create proxy factories.
 * 
 * 
 * 
 * Unlike java.lang.reflect.Proxy, the implementation doesn't do any caching,
 * so calling {@link #createAnonymousProxyFactory(Lookup, MethodType, ProxyHandler)}
 * or its siblings with the same interface as return type of the method type
 * will generated as many proxy classes as the number of calls.
 */
public class Proxy2 {
  private Proxy2() {
    // no instance
  }
  
  /**
   * Specify how to link a proxy method to its implementation. 
   */
  public interface ProxyHandler {
    /**
     * Provide default implementations of all methods of {@link ProxyHandler} 
     * but {@link ProxyHandler#bootstrap(ProxyContext)}.
     */
    public static abstract class Default implements ProxyHandler {
      /**
       * {@inheritDoc}
       * 
       * @implSpec
       * The implementation always returns false.
       */
      @Override
      public boolean override(Method method) {
        return false;
      }
      
      /**
       * {@inheritDoc}
       * 
       * @implSpec
       * The implementation always returns false.
       */
      @Override
      public boolean isMutable(int fieldIndex, Class<?> fieldType) {
        return false;
      }
    }
    
    /**
     *  Define the bootstrap function as a functional interface.
     */
    public interface Bootstrap {
      /**
       * Called to link a proxy method to a target method handle (through a callsite's target).
       * This method is called once by method at runtime the first time the proxy method is called.
       * 
       * @param context object containing information like the method that will be linked
       *                and methods to access the fields and methods of the proxy implementation.
       * @return a callsite object indicating how to link the method to a target method handle.
       * @throws Throwable if any errors occur.
       */
      public CallSite bootstrap(ProxyContext context) throws Throwable;
    }
    
    /**
     * Returns true if the proxy field should be mutable.
     * @param fieldIndex the index of the proxy field.
     * @param fieldType the type of the proxy field.
     * @return true if the proxy field should be mutable, false otherwise.
     */
    public boolean isMutable(int fieldIndex, Class<?> fieldType);
    
    /**
     * Returns true if the method should be overridden by the proxy.
     * This method is only called for method that have an existing implementation
     * (default methods or Object's toString(), equals() and hashCode().
     * This method is called once by method when generating the proxy call.
     * 
     * @param method a method of the interface that may be overridden
     * @return true if the method should be overridden by the proxy.
     */
    public boolean override(Method method);
    
    /**
     * Called to link a proxy method to a target method handle (through a callsite's target).
     * This method is called once by method at runtime the first time the proxy method is called.
     * 
     * @param context object containing information like the method that will be linked
     *                and methods to access the fields and methods of the proxy implementation.
     * @return a callsite object indicating how to link the method to a target method handle.
     * @throws Throwable if any errors occur.
     */
    public CallSite bootstrap(ProxyContext context) throws Throwable;
  }
  
  
  /**
   * Object that encapsulate the data that are available to implement a proxy method.
   */
  public static class ProxyContext {
    private final Lookup lookup;
    private final MethodType methodType;
    private final Method method;
    
    private ProxyContext(Lookup lookup, MethodType methodType, Method method) {
      this.lookup = lookup;
      this.methodType = methodType;
      this.method = method;
    }

    /**
     * Returns the interface method about to be linked.
     * @return the interface method about to be linked.
     */
    public Method method() {
      return method;
    }
    
    /**
     * Returns the method type of the invokedynamic call inside the implementation
     * of the proxy method. 
     * This method type must also be the {@link CallSite#type() type of the callsite}
     * returned by {@link ProxyHandler#bootstrap(ProxyContext)}.
     * @return type of the invokedynamic inside the implementation of the proxy method.
     */
    public MethodType type() {
      return methodType;
    }
    
    /**
     * Returns a method handle that returns the value of a field of the proxy.
     * @param fieldIndex the index of the field.
     * @param type the type of the field
     * @return a method handle that returns the value of a field of the proxy.
     * @throws NoSuchFieldException if the field doesn't exist.
     * 
     * @see Lookup#findGetter(Class, String, Class)
     */
    public MethodHandle findFieldGetter(int fieldIndex, Class<?> type) throws NoSuchFieldException {
      try {
        return lookup.findGetter(lookup.lookupClass(), "arg" + fieldIndex, type).
            asType(MethodType.methodType(type, Object.class));
      } catch (IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
    
    /**
     * Returns a method handle that set the value of a field of the proxy.
     * The field must be {@link ProxyHandler#isMutable(int, Class) mutable}. 
     * 
     * @param fieldIndex the index of the field.
     * @param type the type of the field
     * @return a method handle that set the value of a field of the proxy..
     * @throws NoSuchFieldException if the field doesn't exist.
     * 
     * @see Lookup#findSetter(Class, String, Class)
     */
    public MethodHandle findFieldSetter(int fieldIndex, Class<?> type) throws NoSuchFieldException {
      try {
        return lookup.findSetter(lookup.lookupClass(), "arg" + fieldIndex, type).
            asType(MethodType.methodType(void.class, Object.class, type));
      } catch (IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
    
    // referenced by a method handle
    static ProxyContext create(Lookup lookup, MethodType methodType, Method method) {
      return new ProxyContext(lookup, methodType, method);
    }
  }

  /**
   * A factory of proxy implementing an interface.
   * 
   * @param <T> the type of the proxy interface.
   * @see Proxy2#createAnonymousProxyFactory(Class, Class[], ProxyHandler)
   */
  @FunctionalInterface
  public interface ProxyFactory<T> {
    /**
     * Create a proxy with a value for each field of the proxy.
     * @param fieldValues the value of each field of the proxy. 
     * @return a new proxy instance.
     */
    public T create(Object... fieldValues);
  }
  
  private static final Class<?>[] EMPTY_FIELD_TYPES = new Class<?>[0];

  /**
   * Create a factory that will create anonymous proxy instances implementing an interface {@code type} and no field.
   * The {@code handler} is used to specify the linking between a method and its implementation.
   * The created proxy class will define no field so {@link ProxyFactory#create(Object...)} should be called with no argument.
   * 
   * @param type the interface that the proxy should respect.
   * @param handler an interface that specifies how a proxy method is linked to its implementation.
   * @return a proxy factory that will create proxy instance.
   * 
   * @see #createAnonymousProxyFactory(Class, Class[], ProxyHandler)
   */
  public static <T> ProxyFactory<T> createAnonymousProxyFactory(Class<? extends T> type, ProxyHandler handler) {
    return createAnonymousProxyFactory(type, EMPTY_FIELD_TYPES, handler);
  }

  /**
   * Create a factory that will create anonymous proxy instances implementing an interface {@code type}
   * and with several fields described by {@code fieldTypes}.
   * The {@code handler} is used to specify the linking between a method and its implementation.
   * The created proxy class will define several fields so {@link ProxyFactory#create(Object...)} should be called with
   * the values of the field as argument.
   * 
   * @param type the interface that the proxy should respect.
   * @param fieldTypes type of the fields of the generated proxy.
   * @param handler an interface that specifies how a proxy method is linked to its implementation.
   * @return a proxy factory that will create proxy instance.
   * 
   * @see #createAnonymousProxyFactory(Lookup, MethodType, ProxyHandler)
   */
  public static <T> ProxyFactory<T> createAnonymousProxyFactory(Class<? extends T> type, Class<?>[] fieldTypes, ProxyHandler handler) {
    MethodHandle mh = createAnonymousProxyFactory(MethodHandles.publicLookup(), MethodType.methodType(type, fieldTypes), handler);
    return new ProxyFactory<T>() {   // don't use a lambda here to avoid cycle when retro-weaving
      @Override
      public T create(Object... fieldValues) {
        try {
          return type.cast(mh.invokeWithArguments(fieldValues));
        } catch(RuntimeException | Error e) {
          throw e;
        } catch (Throwable e) {
          throw new UndeclaredThrowableException(e);
        }
      }
    };
  }

  /**
   * Create a factory that will create anonymous proxy instances implementing an interface {@code type}
   * and with several fields described by {@code fieldTypes}.
   * The {@code bootstrap} is used to specify the linking between a method and its implementation.
   * The created proxy class will define several fields so {@link ProxyFactory#create(Object...)} should be called with
   * the values of the field as argument.
   * 
   * @param type the interface that the proxy should respect.
   * @param fieldTypes type of the fields of the generated proxy.
   * @param bootstrap an interface that specifies how a proxy method is linked to its implementation.
   * @return a proxy factory that will create proxy instance.
   * 
   * @see #createAnonymousProxyFactory(Lookup, MethodType, ProxyHandler)
   */
  public static <T> ProxyFactory<T> createAnonymousProxyFactory(Class<? extends T> type, Class<?>[] fieldTypes, ProxyHandler.Bootstrap bootstrap) {
    return createAnonymousProxyFactory(type, fieldTypes, new ProxyHandler.Default() {
      @Override
      public CallSite bootstrap(ProxyContext context) throws Throwable {
        return bootstrap.bootstrap(context);
      }
    });
  }
  
  private static final Unsafe UNSAFE;
  static {
    Unsafe unsafe;
    try {
      Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
      unsafeField.setAccessible(true);
      unsafe =  (Unsafe)unsafeField.get(null);
    } catch (NoSuchFieldException|IllegalAccessException e) {
      throw new AssertionError(e);
    }
    UNSAFE = unsafe;
  }
  
  private static String internalName(Class<?> type) {
    return type.getName().replace('.', '/');
  }
  
  private static String[] internalNames(Class<?>[] types) {
    // keep it compatible with Java 7
    //return Arrays.stream(method.getExceptionTypes()).map(Proxy2::internalName).toArray(String[]::new);
    String[] array = new String[types.length];
    for(int i = 0; i < array.length; i++) {
      array[i] = internalName(types[i]);
    }
    return array;
  }
  
  private static final boolean IS_1_8;
  static {
    boolean is1_8;
    try {
      Class.forName("java.util.Spliterator");  // 1.8 ?
      is1_8 = true;
    } catch (ClassNotFoundException e) {
      is1_8 = false;
    }
    IS_1_8 = is1_8;
  }
  
  /**
   * Create a factory that will create anonymous proxy instances with several fields described by
   * the parameter types of {@code methodType} and implementing an interface described by
   * the return type of {@code methodType}.
   * The {@code handler} is used to specify the linking between a method and its implementation.
   * The returned {@link MethodHandle} will have its type being equals to the {@code methodType}
   * taken as argument.
   * 
   * @param lookup access token used to access to the interface methods
   * @param methodType the parameter types of this {@link MethodType} described the type of the fields
   *                   and the return type the interface implemented by the proxy. 
   * @param handler an interface that specifies how a proxy method is linked to its implementation.
   * @return a method handle that if {@link MethodHandle#invokeExact(Object...) called} will create
   *         a proxy instance of a class implementing the return interfaces. 
   * @throws IllegalArgumentException if the proxy interface is not visible from the lookup object.
   * 
   * @see #createAnonymousProxyFactory(Class, Class[], ProxyHandler)
   */
  public static MethodHandle createAnonymousProxyFactory(Lookup lookup, MethodType methodType, ProxyHandler handler) {
    Class<?> interfaze = methodType.returnType();
    if (lookup.in(interfaze).lookupModes() == 0) {
      throw new IllegalArgumentException("interface " + interfaze + " is not visible from " + lookup);
    }

    // if the proxy is in java.lang.invoke and the interface is not visible, the OpenJDK 7 VM crashes !
    String proxyName = (!IS_1_8 && !Modifier.isPublic(interfaze.getModifiers()))?
        "com/github/forax/proxy2/Foo":   
        "java/lang/invoke/Foo";
    
    ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
    writer.visit(V1_7, ACC_PUBLIC|ACC_SUPER|ACC_FINAL, proxyName, null, "java/lang/Object", new String[]{ internalName(interfaze) });

    String initDesc;
    {
      initDesc = methodType.changeReturnType(void.class).toMethodDescriptorString();
      MethodVisitor init = writer.visitMethod(ACC_PUBLIC, "<init>", initDesc, null, null);
      String factoryDesc = methodType.toMethodDescriptorString();
      MethodVisitor factory = writer.visitMethod(ACC_PUBLIC|ACC_STATIC, "0-^-0", factoryDesc, null, null);
      init.visitCode();
      init.visitVarInsn(ALOAD, 0);
      init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
      factory.visitCode();
      factory.visitTypeInsn(NEW, proxyName);
      factory.visitInsn(DUP);

      int slot = 1;
      for(int i = 0; i < methodType.parameterCount(); i++) {
        Class<?> boundType = methodType.parameterType(i);
        String fieldName = "arg" + i;
        int finalFlag = handler.isMutable(i, boundType)? 0: ACC_FINAL;
        FieldVisitor fv = writer.visitField(ACC_PRIVATE|finalFlag, fieldName, Type.getDescriptor(boundType), null, null);
        fv.visitEnd();

        int loadOp = Type.getType(boundType).getOpcode(ILOAD);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(loadOp, slot);
        init.visitFieldInsn(PUTFIELD, proxyName, fieldName, Type.getDescriptor(boundType));

        factory.visitVarInsn(loadOp, slot - 1);

        slot += (boundType == long.class || boundType == double.class)? 2: 1;
      }

      init.visitInsn(RETURN);
      factory.visitMethodInsn(INVOKESPECIAL, proxyName, "<init>", initDesc, false);
      factory.visitInsn(ARETURN);

      init.visitMaxs(-1, -1);
      init.visitEnd();
      factory.visitMaxs(-1, -1);
      factory.visitEnd();
    }
    
    String mhPlaceHolder = "<<MH_HOLDER>>";
    int mhHolderCPIndex = writer.newConst(mhPlaceHolder);

    Handle BSM =
        new Handle(H_INVOKESTATIC, proxyName, "bsm",
            MethodType.methodType(CallSite.class, Lookup.class, String.class, MethodType.class,
                MethodHandle.class, Method.class).toMethodDescriptorString());
    { // bsm
      MethodVisitor mv = writer.visitMethod(ACC_PRIVATE|ACC_STATIC, "bsm", BSM.getDesc(), null, null);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 3); // mh
      mv.visitVarInsn(ALOAD, 0); // lookup
      mv.visitVarInsn(ALOAD, 2); // method type
      mv.visitVarInsn(ALOAD, 4); // method
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact",
          "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MethodType;Ljava/lang/reflect/Method;)Ljava/lang/invoke/CallSite;", false);
      mv.visitInsn(ARETURN);
      mv.visitMaxs(-1, -1);
      mv.visitEnd();
    }
    
    Method[] methods = interfaze.getMethods();
    int[] methodHolderCPIndexes = new int[methods.length];
    for(int methodIndex = 0; methodIndex < methods.length; methodIndex++) {
      Method method = methods[methodIndex];
      int modifiers = method.getModifiers();
      if (Modifier.isStatic(modifiers)) {
        continue;
      }
      //FIXME add support of public methods of java.lang.Object
      if (!Modifier.isAbstract(modifiers) && !handler.override(method)) {
        continue;
      }
      String methodDesc = Type.getMethodDescriptor(method);
      MethodVisitor mv = writer.visitMethod(ACC_PUBLIC, method.getName(), methodDesc, null,
          internalNames(method.getExceptionTypes()));
      mv.visitAnnotation("Ljava/lang/invoke/LambdaForm$Hidden;", true);
      mv.visitAnnotation("Ljava/lang/invoke/ForceInline;", true);
      mv.visitCode();
      mv.visitVarInsn(ALOAD, 0);
      for(int i = 0; i < methodType.parameterCount(); i++) {
        Class<?> fieldType = methodType.parameterType(i);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, proxyName, "arg" + i, Type.getDescriptor(fieldType));
      }
      int slot = 1;
      for(Class<?> parameterType: method.getParameterTypes()) {
        mv.visitVarInsn(Type.getType(parameterType).getOpcode(ILOAD), slot);
        slot += (parameterType == long.class || parameterType == double.class)? 2: 1;
      }
      String methodPlaceHolder = "<<METHOD_HOLDER " + methodIndex + ">>";
      methodHolderCPIndexes[methodIndex] = writer.newConst(methodPlaceHolder);
      mv.visitInvokeDynamicInsn(method.getName(),
          "(Ljava/lang/Object;" + initDesc.substring(1, initDesc.length() - 2) + methodDesc.substring(1),
          BSM, mhPlaceHolder, methodPlaceHolder);
      mv.visitInsn(Type.getReturnType(method).getOpcode(IRETURN));
      mv.visitMaxs(-1, -1);
      mv.visitEnd();
    }
    writer.visitEnd();
    byte[] data = writer.toByteArray();

    int constantPoolSize = writer.newConst("<<SENTINEL>>");
    Object[] patches = new Object[constantPoolSize];
    patches[mhHolderCPIndex] =  MethodHandles.filterReturnValue(CONTEXT_CREATE,
        MethodHandles.insertArguments(BOOTSTRAP_MH, 0, handler));
    for(int i = 0; i < methodHolderCPIndexes.length; i++) {
      patches[methodHolderCPIndexes[i]] = methods[i];
    }
    Class<?> clazz = UNSAFE.defineAnonymousClass(interfaze, data, patches);
    UNSAFE.ensureClassInitialized(clazz);
    try {
      return MethodHandles.publicLookup().findStatic(clazz, "0-^-0", methodType);
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  private static final MethodHandle BOOTSTRAP_MH, CONTEXT_CREATE;
  static {
    Lookup lookup = MethodHandles.lookup();
    try {
      BOOTSTRAP_MH = lookup.findVirtual(ProxyHandler.class, "bootstrap",
          MethodType.methodType(CallSite.class, ProxyContext.class));
      CONTEXT_CREATE = lookup.findStatic(ProxyContext.class, "create",
          MethodType.methodType(ProxyContext.class, Lookup.class, MethodType.class, Method.class));
    } catch (NoSuchMethodException|IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
}
