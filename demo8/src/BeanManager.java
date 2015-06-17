import static com.github.forax.proxy2.MethodBuilder.methodBuilder;
import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.AbstractMap;
import java.util.HashMap;

import com.github.forax.proxy2.MethodBuilder;
import com.github.forax.proxy2.Proxy2;
import com.github.forax.proxy2.Proxy2.ProxyContext;
import com.github.forax.proxy2.Proxy2.ProxyHandler;

public class BeanManager {
  final ClassValue<MethodHandle> beanFactories = new ClassValue<MethodHandle>() {
    @Override
    protected MethodHandle computeValue(Class<?> type) {
      return Proxy2.createAnonymousProxyFactory(publicLookup(), methodType(type, HashMap.class), new ProxyHandler.Default() {
        @Override
        public CallSite bootstrap(ProxyContext context) throws Throwable {
          MethodHandle target;
          Method method = context.method();
          MethodBuilder builder = methodBuilder(context.type());
          switch(method.getName()) {
          case "toString":
            target = builder
                .dropFirst()
                .convertTo(String.class, AbstractMap.class)  // FIXME
                .unreflect(publicLookup(), HashMap.class.getMethod("toString"));
            break;
          default:
            if (method.getParameterCount() == 0) { 
              target = builder                     // getter
                  .dropFirst()
                  .insertAt(1, Object.class, method.getName())
                  .convertTo(Object.class, HashMap.class, Object.class)
                  .unreflect(publicLookup(), HashMap.class.getMethod("get", Object.class));
            } else {                               
              target = builder                     // setter
                  .before(b -> b
                      .dropFirst()
                      .insertAt(1, Object.class, method.getName())
                      .convertTo(Object.class, HashMap.class, Object.class, Object.class)
                      .unreflect(publicLookup(), HashMap.class.getMethod("put", Object.class, Object.class)))
                  .dropAt(1)
                  .dropAt(1)
                  .convertTo(method.getReturnType(), Object.class)
                  .callIdentity();
            }
          }
          return new ConstantCallSite(target);
        }
      });
    }
  };

  public <T> T newBean(Class<T> type) {
    try {
      return type.cast(beanFactories.get(type).invoke(new HashMap<String,Object>()));
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  
  
  // --- example
  
  public interface User {
    public String firstName();
    public User firstName(String name);
    public String lastName();
    public User lastName(String name);
    public int age();
    public User age(int age);

    @Override
    public String toString();
  }

  public static void main(String[] args) {
    BeanManager beanManager = new BeanManager();
    User user = beanManager.newBean(User.class)
        .firstName("Fox").lastName("Mulder").age(30);
    System.out.println(user.lastName());
    System.out.println(user);
  }
}
