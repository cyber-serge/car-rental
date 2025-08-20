package com.serge.carrental.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.serge.carrental.domain.UserAccount;
import com.serge.carrental.domain.VerificationToken;
import com.serge.carrental.repo.UserAccountRepository;
import com.serge.carrental.repo.VerificationTokenRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserAccountRepository users;
    private final VerificationTokenRepository tokens;
    private final EmailService emailService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Transactional
    public UserAccount register(String email, String password, String firstName, String lastName, String phone, String baseUrl) {
        log.info("user.register email={}", email);
        if (users.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already registered");
        }
        UserAccount u = UserAccount.builder()
                .email(email)
                .passwordHash(encoder.encode(password))
                .firstName(firstName)
                .lastName(lastName)
                .phone(phone)
                .emailVerified(false)
                .createdAt(OffsetDateTime.now())
                .build();
        users.save(u);

        VerificationToken token = VerificationToken.builder()
                .token(UUID.randomUUID().toString())
                .user(u)
                .expiresAt(OffsetDateTime.now().plusDays(1))
                .build();
        tokens.save(token);

        String link = baseUrl + "/api/auth/verify?token=" + token.getToken();
        log.debug("user.register.token_created userId={} token.preview={}", u.getId(), token.getToken().substring(0, 8));
        emailService.send(email, "Verify your Car Rental account",
                "<p>Hello " + (firstName != null ? firstName : "") + ",</p>" +
                        "<p>Please verify your email by clicking the link below:</p>" +
                        "<p><a href=\"" + link + "\">Verify Email</a></p>");
        return u;
    }

    @Transactional
    public boolean verify(String tokenValue) {
        log.info("user.verify token.preview={}", tokenValue == null ? "-" : tokenValue.substring(0, Math.min(8, tokenValue.length())));
        Optional<VerificationToken> tok = tokens.findByToken(tokenValue);
        if (tok.isEmpty()) return false;
        VerificationToken t = tok.get();
        if (t.getExpiresAt().isBefore(OffsetDateTime.now())) return false;
        UserAccount u = t.getUser();
        u.setEmailVerified(true);
        users.save(u);
        tokens.delete(t);
        log.info("user.verify.success userId={}", u.getId());
        return true;
    }

    public UserAccount requireVerifiedUser(String email) {
        log.debug("user.requireVerified email={}", email);
        UserAccount u = users.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!u.isEmailVerified()) throw new IllegalStateException("Email not verified");
        return u;
        }
}
