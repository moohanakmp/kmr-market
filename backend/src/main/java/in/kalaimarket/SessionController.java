package in.kalaimarket;
import java.util.Map;import jakarta.servlet.http.*;import org.springframework.web.bind.annotation.*;import org.springframework.security.core.Authentication;import org.springframework.security.core.context.SecurityContextHolder;import org.springframework.security.web.context.HttpSessionSecurityContextRepository;import org.springframework.security.web.csrf.CsrfToken;
@RestController @RequestMapping("/api/session") public class SessionController {
 @GetMapping public Map<String,Object> state(Authentication a,CsrfToken csrf){boolean signed=a!=null&&a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ROLE_ADMIN"));return Map.of("authenticated",signed,"csrfToken",csrf.getToken());}
 @PostMapping public Map<String,String> login(HttpServletRequest req,HttpServletResponse res){
  if(req.getSession(false)!=null)req.changeSessionId();
  new HttpSessionSecurityContextRepository().saveContext(SecurityContextHolder.getContext(),req,res);return Map.of("status","SIGNED_IN");
 }
 @DeleteMapping public Map<String,String> logout(HttpServletRequest req){var s=req.getSession(false);if(s!=null)s.invalidate();SecurityContextHolder.clearContext();return Map.of("status","SIGNED_OUT");}
}
