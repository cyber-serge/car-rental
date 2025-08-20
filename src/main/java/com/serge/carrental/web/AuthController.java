package com.serge.carrental.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.serge.carrental.domain.UserAccount;
import com.serge.carrental.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody @Valid RegisterReq req, @RequestHeader(value = "X-Base-Url", required = false) String baseUrl) {
        String b = (baseUrl != null ? baseUrl : "http://localhost:8080");
        log.info("auth.register email={} baseUrl={}", req.getEmail(), b);
        UserAccount u = userService.register(req.getEmail(), req.getPassword(), req.getFirstName(), req.getLastName(), req.getPhone(), b);
        log.info("auth.register.success userId={}", u.getId());
        return ResponseEntity.status(201).body(Map.of("userId", u.getId(), "status","PENDING_VERIFICATION"));
    }

    @GetMapping("/verify")
    public ResponseEntity<?> verify(@RequestParam String token) {
        String tokPreview = token == null ? "-" : token.substring(0, Math.min(8, token.length()));
        log.info("auth.verify token.preview={}", tokPreview);
        boolean ok = userService.verify(token);
        if (ok) {
            log.info("auth.verify.success token.preview={}", tokPreview);
            return ResponseEntity.ok(Map.of("status","VERIFIED"));
        }
        log.warn("auth.verify.failed token.preview={}", tokPreview);
        return ResponseEntity.badRequest().body(Map.of("error","INVALID_TOKEN"));
    }

    @Data
    public static class RegisterReq {
        @Email @NotBlank
        private String email;
        @NotBlank
        private String password;
        private String firstName;
        private String lastName;
        private String phone;
    }
}
