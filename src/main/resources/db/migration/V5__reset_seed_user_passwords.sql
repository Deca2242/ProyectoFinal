-- Los usuarios de prueba de V2 se crearon con un hash BCrypt cuya contraseña no estaba documentada,
-- así que no era posible iniciar sesión con ellos. Se les asigna la contraseña conocida "Password123".
-- Solo se actualizan las filas que conservan el hash original de V2.
UPDATE users
SET password_hash = '$2a$10$azm08cZUaOJ0QXRcJ8QomOvEov423Yy9VM8GHV0MdD7nwlAT8S3xe'
WHERE password_hash = '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy';
