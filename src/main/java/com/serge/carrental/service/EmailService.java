package com.serge.carrental.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private final JavaMailSender mailSender;

    @Value("${MAIL_FROM:noreply@car-rental.local}")
    private String from;

    public void send(String to, String subject, String html) {
        try {
            log.debug("email.send to={} subject={}", to, subject);
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true);
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(msg);
            log.info("email.send.success to={} subject={}", to, subject);
        } catch (Exception e) {
            log.error("email.send.failed to={} subject={} error={}", to, subject, e.toString(), e);
            throw new RuntimeException("Failed to send email", e);
        }
    }
}
