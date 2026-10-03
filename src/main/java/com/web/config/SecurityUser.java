package com.web.config;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.time.LocalDateTime;
import java.util.Collection;

// UserDetails de la aplicación: además del estado y el rol lleva la fecha del último cambio de contraseña,
// que el filtro JWT compara con el "iat" del token
@Getter
public class SecurityUser extends User {

    private final LocalDateTime passwordChangedAt;

    public SecurityUser(String username, String password, boolean enabled,
                        Collection<? extends GrantedAuthority> authorities, LocalDateTime passwordChangedAt) {
        super(username, password, enabled, true, true, true, authorities);
        this.passwordChangedAt = passwordChangedAt;
    }
}
