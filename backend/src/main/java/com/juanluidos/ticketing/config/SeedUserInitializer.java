package com.juanluidos.ticketing.config;

import com.juanluidos.ticketing.domain.AppUser;
import com.juanluidos.ticketing.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pone la contraseña del usuario sembrado la primera vez que arranca.
 *
 * <p>La migración inserta el centinela {@code NEEDS_INIT} en vez de un hash, para
 * no dejar ninguna contraseña en el repositorio. Para cambiarla: poner
 * {@code SEED_USER_PASSWORD} y devolver el hash al centinela.
 */
/**
 * No corre bajo el perfil de test. {@code @SpringBootTest} ejecuta los
 * ApplicationRunner igual que el arranque normal, y como dev y test comparten
 * base, lanzar los tests dejaba la contraseña del usuario puesta a la del
 * application.yml de test sin que nada lo dijera.
 */
@Component
@Profile("!test")
public class SeedUserInitializer implements ApplicationRunner {

    static final String SENTINEL = "NEEDS_INIT";

    private static final Logger log = LoggerFactory.getLogger(SeedUserInitializer.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TicketingProperties properties;

    public SeedUserInitializer(AppUserRepository users,
                               PasswordEncoder passwordEncoder,
                               TicketingProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (AppUser user : users.findByPasswordHash(SENTINEL)) {
            user.setPasswordHash(passwordEncoder.encode(properties.seedUserPassword()));
            users.save(user);
            log.warn("Contraseña inicializada para el usuario '{}'. Cámbiala con SEED_USER_PASSWORD.",
                    user.getUsername());
        }
    }
}
