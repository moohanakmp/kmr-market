package in.kalaimarket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import static org.junit.jupiter.api.Assertions.*;

class SecurityConfigTest {
 private final SecurityConfig config = new SecurityConfig();

 @ParameterizedTest
 @ValueSource(strings={"1234567", "12345678"})
 void acceptsTenCharacterAdminAndSevenOrEightCharacterFeed(String feedPassword) {
  var users = config.users("kalai", "1234567890", feedPassword);
  var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
  assertTrue(encoder.matches("1234567890", users.loadUserByUsername("kalai").getPassword()));
  assertTrue(encoder.matches(feedPassword, users.loadUserByUsername("mt5_feed").getPassword()));
 }

 @Test void rejectsShortAdminPassword() {
  var error = assertThrows(IllegalArgumentException.class, () -> config.users("kalai", "123456789", "1234567"));
  assertTrue(error.getMessage().contains("ADMIN_PASSWORD"));
 }

 @Test void rejectsShortFeedPassword() {
  var error = assertThrows(IllegalArgumentException.class, () -> config.users("kalai", "1234567890", "123456"));
  assertTrue(error.getMessage().contains("MT5_FEED_PASSWORD"));
 }

 @Test void rejectsSharedPasswords() {
  var error = assertThrows(IllegalArgumentException.class, () -> config.users("kalai", "1234567890", "1234567890"));
  assertTrue(error.getMessage().contains("must differ"));
 }

 @Test void rejectsReservedAdminUsernameIgnoringCase() {
  var error = assertThrows(IllegalArgumentException.class, () -> config.users("MT5_FEED", "1234567890", "1234567"));
  assertTrue(error.getMessage().contains("ADMIN_USERNAME"));
 }

 @Test void allowsFeedToRemainDisabled() {
  var users = config.users("kalai", "1234567890", "");
  assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException.class, () -> users.loadUserByUsername("mt5_feed"));
 }
}
