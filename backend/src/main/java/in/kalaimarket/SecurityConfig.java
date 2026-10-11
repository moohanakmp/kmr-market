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
 @Bean UserDetailsService users(@Value("${app.admin.username}") String username,@Value("${app.admin.password}") String password) {
  if(password.length()<16) throw new IllegalArgumentException("ADMIN_PASSWORD must have at least 16 characters");
  return new InMemoryUserDetailsManager(User.withUsername(username).password("{bcrypt}"+new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
 }
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
  return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/api/**").authenticated().requestMatchers("/actuator/health").permitAll().requestMatchers("/actuator/**").denyAll().anyRequest().permitAll())
   .httpBasic(b->b.authenticationEntryPoint((req,res,e)->res.sendError(401))).build();
 }
}
