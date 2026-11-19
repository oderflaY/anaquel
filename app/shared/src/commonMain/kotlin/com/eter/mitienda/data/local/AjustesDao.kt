package com.eter.mitienda.data.local

/** Ajustes sueltos clave/valor (v5): token y datos de la sesión de Anaquel. */
class AjustesDao(private val db: PapeleriaDatabase) {

    suspend fun leer(clave: String): String? = db.withConnection { conn ->
        conn.prepare("SELECT valor FROM ajustes WHERE clave = ?").use { st ->
            st.bindText(1, clave)
            if (st.step()) st.getText(0) else null
        }
    }

    suspend fun guardar(clave: String, valor: String?) = db.withConnection { conn ->
        if (valor == null) {
            conn.prepare("DELETE FROM ajustes WHERE clave = ?").use { it.bindText(1, clave); it.step() }
        } else {
            conn.prepare("INSERT INTO ajustes (clave, valor) VALUES (?, ?) ON CONFLICT(clave) DO UPDATE SET valor = excluded.valor")
                .use { it.bindText(1, clave); it.bindText(2, valor); it.step() }
        }
        Unit
    }
}
