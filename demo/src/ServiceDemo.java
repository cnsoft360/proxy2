import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

import com.github.forax.proxy2.MethodBuilder;
import com.github.forax.proxy2.MethodBuilder.Fun;
import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyFactory;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public class ServiceDemo {
  public interface Service {
    public void addUser(String user);
  }
  
  public static class ServiceImpl implements Service {
    @Override
    public void addUser(String user) {
      System.out.println("create a user " + user + " in the DB");
    }
  }
  
  @SuppressWarnings("unused")  // used by a method handle
  private static void intercept(Object[] args) {
    if (args[0].toString().contains("Evil")) {
      throw new SecurityException("don't be Evil !");
    }
  }
  
  public static void main(String[] args) throws Throwable {
    Service service = new ServiceImpl();

    final MethodHandle intercept = MethodHandles.lookup()
        .findStatic(ServiceDemo.class, "intercept", MethodType.methodType(void.class, Object[].class));
    
    ProxyFactory<Service> factory = Proxy2.createAnonymousProxyFactory(
      Service.class,                        
      new Class<?>[] { Service.class },     
      new ProxyHandler.Default() {
        @Override
        public boolean override(Method method) { return true; }

        @Override
        public CallSite bootstrap(ProxyContext context) throws Throwable {
          Method method = context.method();
          MethodHandle target = MethodBuilder.methodBuilder(context.type())   
              .dropFirst()
              .before(new Fun<MethodBuilder, MethodHandle>() {
                @Override
                public MethodHandle apply(MethodBuilder b) throws NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
                  return b.dropFirst().boxAll().call(intercept);
                }
              }) 
              .unreflect(MethodHandles.lookup(), method);                                 
          return new ConstantCallSite(target);
        }
      });
    
    Service proxy = factory.create(service);
    proxy.addUser("James Bond");
    proxy.addUser("Dr Evil");
  }
}
