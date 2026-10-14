package in.kalaimarket;
import org.springframework.security.core.context.SecurityContextHolder;
/** Identity only; credentials and tokens must never enter persisted audit data. */
public final class SignalActor {
 private SignalActor() {}
 public static String current(){var a=SecurityContextHolder.getContext().getAuthentication();return a!=null&&a.isAuthenticated()&&!"anonymousUser".equals(a.getName())?"USER:"+a.getName():"SYSTEM:SCANNER";}
}
