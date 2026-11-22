package com.eventflow.config;

import com.eventflow.model.User;
import com.eventflow.repository.UserRepository;
import com.eventflow.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Runs only with the {@code prod} profile.
 * <ol>
 *   <li>Refuses to start with missing or unsafe secrets (no predictable default is ever used in production).</li>
 *   <li>Optionally creates the first real administrator from {@code OCCAVIE_BOOTSTRAP_ADMIN_EMAIL/PASSWORD}.</li>
 *   <li>Once a real administrator exists, disables the three public demo accounts so their published passwords
 *       cannot be used on the live site.</li>
 * </ol>
 */
@Component
@Profile("prod")
public class ProductionSetup implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ProductionSetup.class);
    private static final Set<String> DEMO_EMAILS = Set.of("admin@eventflow.com", "organizer@eventflow.com", "participant@eventflow.com");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserService userService;
    private final String rememberKey;
    private final String ticketSecret;
    private final String mongoUri;
    private final String bootstrapEmail;
    private final String bootstrapPassword;
    private final String publicUrl;

    public ProductionSetup(UserRepository userRepository, PasswordEncoder passwordEncoder, UserService userService,
                           @Value("${occavie.security.remember-key:}") String rememberKey,
                           @Value("${occavie.ticket.secret:}") String ticketSecret,
                           @Value("${spring.mongodb.uri:}") String mongoUri,
                           @Value("${OCCAVIE_BOOTSTRAP_ADMIN_EMAIL:}") String bootstrapEmail,
                           @Value("${OCCAVIE_BOOTSTRAP_ADMIN_PASSWORD:}") String bootstrapPassword,
                           @Value("${occavie.public-base-url:}") String publicUrl) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userService = userService;
        this.rememberKey = rememberKey;
        this.ticketSecret = ticketSecret;
        this.mongoUri = mongoUri;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
        this.publicUrl = publicUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems = secretProblems(rememberKey, ticketSecret, mongoUri, publicUrl);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration:\n - " + String.join("\n - ", problems));
        }
        bootstrapAdmin();
        disableDemoAccounts();
    }

    /** Pure check, unit-tested: returns what is wrong with the configuration (empty = safe). */
    public static List<String> secretProblems(String rememberKey, String ticketSecret, String mongoUri, String publicUrl) {
        List<String> problems = new ArrayList<>();
        if (weak(rememberKey)) problems.add("OCCAVIE_REMEMBER_KEY must be set to a random value of 24+ characters (not the development default).");
        if (weak(ticketSecret) || ticketSecret.equals(rememberKey)) problems.add("OCCAVIE_TICKET_SECRET must be set to its own random value of 24+ characters.");
        if (mongoUri == null || mongoUri.isBlank() || mongoUri.contains("USERNAME:PASSWORD")) problems.add("MONGODB_URI must point at your MongoDB database.");
        if (publicUrl == null || !publicUrl.startsWith("https://")) problems.add("OCCAVIE_PUBLIC_URL must be the public https:// address of the site (used in emails, sitemap and social previews).");
        return problems;
    }

    private static boolean weak(String secret) {
        return secret == null || secret.length() < 24 || secret.startsWith("occavie-local") || secret.startsWith("eventflow-local")
                || secret.startsWith("replace-with");
    }

    private void bootstrapAdmin() {
        if (bootstrapEmail.isBlank() || bootstrapPassword.isBlank()) return;
        if (userRepository.findByRole("ADMIN").stream().anyMatch(this::isRealAdmin)) return;
        userService.validateNewPassword(bootstrapPassword, bootstrapPassword);   // enforces the password policy
        userRepository.save(User.builder().fullName("Administrator").email(bootstrapEmail.trim().toLowerCase(Locale.ROOT))
                .password(passwordEncoder.encode(bootstrapPassword)).role("ADMIN").enabled(true).build());
        log.info("Created the first administrator account from OCCAVIE_BOOTSTRAP_ADMIN_EMAIL. Remove the password variable now.");
    }

    private boolean isRealAdmin(User user) {
        return user.isEnabled() && user.getEmail() != null && !DEMO_EMAILS.contains(user.getEmail().toLowerCase(Locale.ROOT));
    }

    private void disableDemoAccounts() {
        boolean realAdmin = userRepository.findByRole("ADMIN").stream().anyMatch(this::isRealAdmin);
        if (!realAdmin) {
            log.warn("No real administrator exists, so the public demo accounts stay ACTIVE. Set OCCAVIE_BOOTSTRAP_ADMIN_EMAIL and "
                    + "OCCAVIE_BOOTSTRAP_ADMIN_PASSWORD for the first start to create one and lock the demo accounts.");
            return;
        }
        for (String email : DEMO_EMAILS) {
            userRepository.findByEmailIgnoreCase(email).filter(User::isEnabled).ifPresent(u -> {
                u.setEnabled(false);
                userRepository.save(u);
                log.info("Disabled public demo account {}", email);
            });
        }
    }
}
