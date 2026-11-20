-- Sesiones de la app móvil: el token viaja como "Authorization: Bearer" y se
-- guarda cifrado en el teléfono (Android Keystore / Keychain de iOS). Duran
-- más que las del navegador porque el teléfono es personal y está bloqueado.
ALTER TABLE sesiones ADD COLUMN tipo TEXT NOT NULL DEFAULT 'web' CHECK (tipo IN ('web', 'app'));
