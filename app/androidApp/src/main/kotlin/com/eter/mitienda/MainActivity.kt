package com.eter.mitienda

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.eter.mitienda.core.notifications.AvisosAnaquel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // El diseño es siempre claro: íconos oscuros en las barras del sistema
        // aunque el teléfono esté en modo oscuro.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            App((application as MiTiendaApplication).container)
        }
    }

    // Al salir de la app revisamos pronto, para no esperar los 15 minutos del ciclo.
    override fun onStop() {
        super.onStop()
        AvisosAnaquel.revisarYa(this)
    }
}
