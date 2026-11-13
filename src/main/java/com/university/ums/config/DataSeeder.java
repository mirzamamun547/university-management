package com.university.ums.config;

import com.university.ums.model.AppUser;
import com.university.ums.model.Role;
import com.university.ums.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first admin so there is someone who can log in and create other users. */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String username, password, fullName, email;

    public DataSeeder(UserRepository users, PasswordEncoder encoder,
                      @Value("${app.admin.username}") String username,
                      @Value("${app.admin.password}") String password,
                      @Value("${app.admin.full-name}") String fullName,
                      @Value("${app.admin.email}") String email) {
        this.users = users;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
        this.fullName = fullName;
        this.email = email;
    }

    @Override
    public void run(String... args) {
        if (users.existsByRole(Role.ADMIN)) return;
        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setPassword(encoder.encode(password));
        admin.setFullName(fullName);
        admin.setEmail(email);
        admin.setRole(Role.ADMIN);
        users.save(admin);
        log.info("Created initial admin account '{}'", username);
    }
}
