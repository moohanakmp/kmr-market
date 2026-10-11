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
  if(password.length()<16) throw new IllegalArgumentException("ADMIN_PASSWORD must have at least 16 characters");
  var users=new InMemoryUserDetailsManager(User.withUsername(username).password("{bcrypt}"+new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
  if(!feedPassword.isBlank()){
   if(feedPassword.length()<16||feedPassword.equals(password)||username.equals("mt5_feed"))throw new IllegalArgumentException("Use a distinct MT5_FEED_PASSWORD (16+ characters) and admin username other than mt5_feed");
   users.createUser(User.withUsername("mt5_feed").password("{bcrypt}"+new BCryptPasswordEncoder().encode(feedPassword)).roles("FEED").build());
  }
  return users;
 }
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
  return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a.requestMatchers("/api/feed/**").hasAnyRole("ADMIN","FEED").requestMatchers("/api/**").hasRole("ADMIN").requestMatchers("/actuator/health").permitAll().requestMatchers("/actuator/**").denyAll().anyRequest().permitAll())
   .httpBasic(b->b.authenticationEntryPoint((req,res,e)->res.sendError(401))).build();
 }
}
