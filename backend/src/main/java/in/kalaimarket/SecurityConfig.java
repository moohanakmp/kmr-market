package in.kalaimarket;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.config.http.SessionCreationPolicy;
@Configuration public class SecurityConfig {
 @Bean UserDetailsService users(@Value("${app.admin.username}") String username,@Value("${app.admin.password}") String password,
  @Value("${MT5_FEED_PASSWORD:}") String feedPassword) {
  if(password.length()<10) throw new IllegalArgumentException("ADMIN_PASSWORD must have at least 10 characters");
  var users=new InMemoryUserDetailsManager(User.withUsername(username).password("{bcrypt}"+new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
  if(!feedPassword.isBlank()){
   if(feedPassword.length()<7)throw new IllegalArgumentException("MT5_FEED_PASSWORD must have at least 7 characters; this is the KMR bridge password, not your broker login password");
   if(feedPassword.equals(password))throw new IllegalArgumentException("MT5_FEED_PASSWORD must differ from ADMIN_PASSWORD");
   if(username.equalsIgnoreCase("mt5_feed"))throw new IllegalArgumentException("ADMIN_USERNAME must differ from mt5_feed when the MT5 bridge is enabled");
   users.createUser(User.withUsername("mt5_feed").password("{bcrypt}"+new BCryptPasswordEncoder().encode(feedPassword)).roles("FEED").build());
  }
  return users;
 }
 private static boolean cookieCsrfRequired(jakarta.servlet.http.HttpServletRequest req){
  if(java.util.Set.of("GET","HEAD","OPTIONS","TRACE").contains(req.getMethod()))return false;
  var session=req.getSession(false);if(session==null)return false;
  var saved=session.getAttribute(org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
  boolean cookieAdmin=saved instanceof org.springframework.security.core.context.SecurityContext ctx&&ctx.getAuthentication()!=null&&ctx.getAuthentication().getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));
  var header=req.getHeader("Authorization");boolean basic=header!=null&&header.startsWith("Basic ");
  return cookieAdmin||!basic;
 }
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
  return http.csrf(c->c.requireCsrfProtectionMatcher(SecurityConfig::cookieCsrfRequired))
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
   .securityContext(c->c.requireExplicitSave(true).securityContextRepository(new org.springframework.security.web.context.HttpSessionSecurityContextRepository()))
   .authorizeHttpRequests(a->a.requestMatchers(org.springframework.http.HttpMethod.GET,"/api/session").permitAll().requestMatchers("/api/feed/**").hasAnyRole("ADMIN","FEED").requestMatchers("/api/**").hasRole("ADMIN").requestMatchers("/actuator/health").permitAll().requestMatchers("/actuator/**").denyAll().anyRequest().permitAll())
   .httpBasic(b->b.authenticationEntryPoint((req,res,e)->res.sendError(401))).build();
 }
}
