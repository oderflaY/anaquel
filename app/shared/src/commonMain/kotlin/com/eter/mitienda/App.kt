package com.eter.mitienda

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.feature.orders.DueUrgency
import com.eter.mitienda.feature.orders.OrderDates
import com.eter.mitienda.feature.orders.OrderFormRoute
import com.eter.mitienda.feature.orders.OrderFormViewModel
import com.eter.mitienda.feature.orders.OrdersRoute
import com.eter.mitienda.feature.orders.OrdersViewModel
import com.eter.mitienda.feature.stats.StatsRoute
import com.eter.mitienda.feature.stats.StatsViewModel
import kotlinx.coroutines.flow.catch
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_inventory
import mitienda.shared.generated.resources.ic_shelf
import com.eter.mitienda.feature.anaquel.AnaquelRoute
import com.eter.mitienda.feature.anaquel.ChatsRoute
import com.eter.mitienda.feature.anaquel.MapaRoute
import com.eter.mitienda.feature.anaquel.InicioRoute
import com.eter.mitienda.feature.anaquel.BarraTienda
import com.eter.mitienda.feature.anaquel.HojaHerramientas
import com.eter.mitienda.feature.anaquel.PestanaTienda
import com.eter.mitienda.feature.anaquel.SeccionAnaquel
import com.eter.mitienda.feature.anaquel.herramientasDe
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mitienda.shared.generated.resources.ic_chat
import mitienda.shared.generated.resources.ic_home
import com.eter.mitienda.feature.anaquel.AnaquelViewModel
import org.jetbrains.compose.resources.painterResource
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.eter.mitienda.core.designsystem.theme.MiTiendaTheme
import com.eter.mitienda.core.image.LocalImagePathResolver
import com.eter.mitienda.di.AppContainer
import com.eter.mitienda.feature.categories.CategoriesRoute
import com.eter.mitienda.feature.categories.CategoriesViewModel
import com.eter.mitienda.feature.dashboard.DashboardRoute
import com.eter.mitienda.feature.dashboard.DashboardViewModel
import com.eter.mitienda.feature.productform.ProductFormRoute
import com.eter.mitienda.feature.productform.ProductFormViewModel
import com.eter.mitienda.feature.sale.CartViewModel
import com.eter.mitienda.feature.sale.SaleRoute
import com.eter.mitienda.feature.sale.TicketRoute
import com.eter.mitienda.feature.sale.TicketViewModel
import com.eter.mitienda.feature.sync.SyncRoute
import com.eter.mitienda.feature.sync.SyncViewModel

/** Rutas de navegación. */
private object Routes {
    const val DASHBOARD = "dashboard"
    const val ORDERS = "orders"
    const val STATS = "stats"
    const val ANAQUEL = "anaquel"
    const val INICIO = "inicio"
    const val CHATS = "chats"
    const val MAPA = "mapa"
    const val ARG_ORDER_ID = "orderId"
    const val ORDER_FORM = "order_form?$ARG_ORDER_ID={$ARG_ORDER_ID}"
    fun orderForm(orderId: Long?) = "order_form?$ARG_ORDER_ID=${orderId ?: NEW_PRODUCT}"

    /** Pantallas con barra inferior. */
    val TOP_LEVEL = setOf(INICIO, CHATS, ANAQUEL, DASHBOARD, ORDERS, STATS)
    const val SYNC = "sync"
    const val CATEGORIES = "categories"
    const val SALE = "sale"
    const val ARG_SALE_ID = "saleId"
    const val TICKET = "ticket/{$ARG_SALE_ID}"

    fun ticket(saleId: Long) = "ticket/$saleId"
    const val ARG_PRODUCT_ID = "productId"
    const val ARG_BARCODE = "barcode"
    const val PRODUCT_FORM =
        "product_form?$ARG_PRODUCT_ID={$ARG_PRODUCT_ID}&$ARG_BARCODE={$ARG_BARCODE}"

    /**
     * @param productId `null` = alta; con id = edición.
     * @param barcode código a prellenar en el alta (viene de un escaneo).
     */
    fun productForm(productId: Long?, barcode: String? = null) = buildString {
        append("product_form?$ARG_PRODUCT_ID=${productId ?: NEW_PRODUCT}")
        if (barcode != null) append("&$ARG_BARCODE=${encodeQueryValue(barcode)}")
    }

    const val NEW_PRODUCT = -1L
}

/**
 * Codifica un valor para la URL de la ruta. Code 128 admite símbolos como
 * `&`, `?` o `/` que romperían la ruta si viajaran sin codificar.
 */
private fun encodeQueryValue(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~") {
            append(c)
        } else {
            append('%')
            append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/**
 * Raíz de la app (compartida por Android e iOS).
 *
 * Cada destino de NavHost tiene su propio ViewModelStore: el ViewModel del
 * formulario nace al abrirlo y se destruye al cerrarlo, mientras que ambos
 * comparten el mismo repositorio del [container].
 */
@Composable
fun App(container: AppContainer) {
    val repository = container.productRepository

    // Al abrir la app se reprograman los avisos de pedidos (cubre reinstalaciones y relojes cambiados).
    LaunchedEffect(Unit) { container.orderRepository.rescheduleAllReminders() }

    // Pedidos que vencen hoy/mañana o ya están atrasados: burbuja en la pestaña "Pedidos".
    val urgentOrders by produceState(0) {
        container.orderRepository.observeOrders()
            .catch { emit(emptyList()) }
            .collect { orders ->
                value = orders.count { o ->
                    o.status.isOpen && OrderDates.due(o.deliveryAt, true).urgency in
                        setOf(DueUrgency.OVERDUE, DueUrgency.TODAY, DueUrgency.TOMORROW)
                }
            }
    }

    // Inicio, Chats y Mi tienda comparten la misma sesión y datos de la nube.
    val anaquel = viewModel { AnaquelViewModel(container.anaquelApi, container.ajustesDao, repository) }
    val nube by anaquel.state.collectAsStateWithLifecycle()
    val chatsSinLeer = nube.inicio?.chatsSinLeer ?: nube.resumen?.chatsSinLeer ?: 0

    var herramientas by remember { mutableStateOf(false) }
    // Si algo se abrió desde el menú ＋, «atrás» regresa al menú (no hasta Inicio).
    // Guarda de qué pantalla se partió y a cuál se fue.
    var menuOrigen by remember { mutableStateOf<String?>(null) }
    var menuDestino by remember { mutableStateOf<String?>(null) }

    MiTiendaTheme {
        // Las imágenes guardan rutas relativas; aquí se indica cómo resolverlas.
        CompositionLocalProvider(
            LocalImagePathResolver provides container.imageStorage::resolve,
            com.eter.mitienda.feature.anaquel.LocalBajarFoto provides container.anaquelApi::bajarFoto,
        ) {
            val navController = rememberNavController()
            val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
            val showBottomBar = currentRoute in Routes.TOP_LEVEL
            // Pantalla empujada (Cobrar, Nuevo producto…): al volver al origen se reabre el menú.
            LaunchedEffect(currentRoute) {
                val destino = menuDestino ?: return@LaunchedEffect
                when {
                    currentRoute == destino -> Unit
                    currentRoute == menuOrigen && destino !in Routes.TOP_LEVEL && destino != Routes.ORDERS -> {
                        menuOrigen = null; menuDestino = null; herramientas = true
                    }
                    else -> { menuOrigen = null; menuDestino = null }
                }
            }
            // Pestaña (Mi tienda, Inventario, Encargos…): «atrás» vuelve a donde estaba y abre el menú.
            NavigationBackHandler(
                state = rememberNavigationEventState(NavigationEventInfo.None),
                isBackEnabled = !herramientas && menuDestino != null && currentRoute == menuDestino &&
                    (menuDestino in Routes.TOP_LEVEL || menuDestino == Routes.ORDERS),
                onBackCompleted = {
                    val origen = menuOrigen
                    menuOrigen = null; menuDestino = null
                    if (origen != null && origen != currentRoute) {
                        navController.navigate(origen) {
                            popUpTo(Routes.INICIO) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                    herramientas = true
                },
            )

            Column(Modifier.fillMaxSize().background(NeuColors.Background)) {
                Box(
                    Modifier
                        .weight(1f)
                        // La barra inferior ya ocupa la zona de navegación del sistema.
                        .then(if (showBottomBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                ) {
                    NavHost(
                        navController = navController, startDestination = Routes.INICIO,
                        // Entre pestañas: desvanecido con un leve acercamiento. Al entrar a una
                        // pantalla de detalle: llega desde la derecha; al volver, regresa.
                        enterTransition = {
                            if (targetState.destination.route in Routes.TOP_LEVEL) fadeIn(tween(260)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 0.96f)
                            else slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(260))
                        },
                        exitTransition = {
                            if (targetState.destination.route in Routes.TOP_LEVEL) fadeOut(tween(180))
                            else slideOutHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 5 } + fadeOut(tween(260))
                        },
                        popEnterTransition = { slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 5 } + fadeIn(tween(260)) },
                        popExitTransition = { slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 3 } + fadeOut(tween(220)) + scaleOut(tween(320), targetScale = 0.96f) },
                    ) {
                        composable(Routes.INICIO) {
                            InicioRoute(anaquel, onAbrirChats = {
                                navController.navigate(Routes.CHATS) {
                                    popUpTo(Routes.INICIO) { saveState = true }
                                    launchSingleTop = true
                                }
                            }, onAbrirMapa = { navController.navigate(Routes.MAPA) })
                        }
                        composable(Routes.CHATS) { ChatsRoute(anaquel) }
                        composable(Routes.MAPA) { MapaRoute(anaquel, onVolver = { navController.popBackStack() }) }
                        composable(Routes.DASHBOARD) {
                            DashboardRoute(
                                viewModel = viewModel { DashboardViewModel(repository, container.categoryRepository) },
                                onAddProductClick = { navController.navigate(Routes.productForm(null)) },
                                onProductClick = { navController.navigate(Routes.productForm(it.id)) },
                                onCreateProduct = { barcode ->
                                    navController.navigate(Routes.productForm(null, barcode))
                                },
                                onBluetoothClick = { navController.navigate(Routes.SYNC) },
                                onManageCategories = { navController.navigate(Routes.CATEGORIES) },
                                onSellClick = { navController.navigate(Routes.SALE) },
                                onStatsClick = { navController.navigate(Routes.STATS) },
                            )
                        }

                        composable(Routes.ORDERS) {
                            OrdersRoute(
                                viewModel = viewModel { OrdersViewModel(container.orderRepository) },
                                onNewOrder = { navController.navigate(Routes.orderForm(null)) },
                                onOpenOrder = { navController.navigate(Routes.orderForm(it.id)) },
                            )
                        }

                        composable(
                            route = Routes.ORDER_FORM,
                            arguments = listOf(
                                navArgument(Routes.ARG_ORDER_ID) {
                                    type = NavType.LongType
                                    defaultValue = Routes.NEW_PRODUCT
                                },
                            ),
                        ) {
                            OrderFormRoute(
                                viewModel = viewModel {
                                    OrderFormViewModel(
                                        container.orderRepository,
                                        createSavedStateHandle().get<Long>(Routes.ARG_ORDER_ID)?.takeIf { it != Routes.NEW_PRODUCT },
                                    )
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable(Routes.ANAQUEL) {
                            AnaquelRoute(viewModel = anaquel, onHerramientas = { herramientas = true })
                        }

                        composable(Routes.STATS) {
                            StatsRoute(
                                viewModel = viewModel { StatsViewModel(container.statsRepository) },
                                onOpenTicket = { navController.navigate(Routes.ticket(it)) },
                            )
                        }

                        composable(Routes.SALE) {
                            SaleRoute(
                                viewModel = viewModel { CartViewModel(repository, container.saleRepository) },
                                onBack = { navController.popBackStack() },
                                // El carrito sale de la pila: "atrás" desde el ticket vuelve al inventario.
                                onSaleCompleted = { saleId ->
                                    navController.navigate(Routes.ticket(saleId)) {
                                        popUpTo(Routes.SALE) { inclusive = true }
                                    }
                                },
                            )
                        }

                        composable(
                            route = Routes.TICKET,
                            arguments = listOf(navArgument(Routes.ARG_SALE_ID) { type = NavType.LongType }),
                        ) {
                            TicketRoute(
                                viewModel = viewModel {
                                    TicketViewModel(
                                        container.saleRepository,
                                        createSavedStateHandle().get<Long>(Routes.ARG_SALE_ID) ?: -1L,
                                    )
                                },
                                onDone = { navController.popBackStack() },
                                onNewSale = {
                                    navController.navigate(Routes.SALE) {
                                        popUpTo(Routes.DASHBOARD)
                                    }
                                },
                            )
                        }

                        composable(Routes.CATEGORIES) {
                            CategoriesRoute(
                                viewModel = viewModel { CategoriesViewModel(container.categoryRepository) },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable(Routes.SYNC) {
                            SyncRoute(
                                viewModel = viewModel { SyncViewModel(container.peerSyncService, container.syncManager) },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable(
                            route = Routes.PRODUCT_FORM,
                            arguments = listOf(
                                navArgument(Routes.ARG_PRODUCT_ID) {
                                    type = NavType.LongType
                                    defaultValue = Routes.NEW_PRODUCT
                                },
                                navArgument(Routes.ARG_BARCODE) {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                            ),
                        ) {
                            ProductFormRoute(
                                viewModel = viewModel {
                                    val args = createSavedStateHandle()
                                    ProductFormViewModel(
                                        repository = repository,
                                        categoryRepository = container.categoryRepository,
                                        imageStorage = container.imageStorage,
                                        productId = args.get<Long>(Routes.ARG_PRODUCT_ID)
                                            ?.takeIf { it != Routes.NEW_PRODUCT },
                                        initialBarcode = args.get<String>(Routes.ARG_BARCODE),
                                    )
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }

                val irPestana = { route: String ->
                    navController.navigate(route) {
                        // Pestañas: una sola copia de cada una y conservan su scroll/estado.
                        popUpTo(Routes.INICIO) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                // Una oferta, un trato o el mapa piden ir al chat: se va desde cualquier pantalla.
                val navegar = anaquel.navegar
                LaunchedEffect(navegar) {
                    if (navegar == "chats") {
                        anaquel.onNavegado()
                        irPestana(Routes.CHATS)
                    }
                }
                // La barra se esconde hacia abajo en las pantallas de detalle y vuelve a subir.
                AnimatedVisibility(
                    showBottomBar,
                    enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
                ) {
                    BarraTienda(
                        izquierda = listOf(
                            PestanaTienda(Routes.INICIO, "Inicio", painterResource(Res.drawable.ic_home)),
                            PestanaTienda(Routes.CHATS, "Chats", painterResource(Res.drawable.ic_chat), chatsSinLeer),
                        ),
                        derecha = listOf(
                            PestanaTienda(Routes.DASHBOARD, "Inventario", painterResource(Res.drawable.ic_inventory)),
                            PestanaTienda(Routes.ANAQUEL, "Mi tienda", painterResource(Res.drawable.ic_shelf), (nube.resumen?.sinPedir ?: 0) + urgentOrders),
                        ),
                        // Gráficas y encargos se abren desde Inventario / herramientas: se marca la pestaña más cercana.
                        activa = when (currentRoute) { Routes.STATS -> Routes.DASHBOARD; Routes.ORDERS -> Routes.ANAQUEL; else -> currentRoute },
                        onElegir = { irPestana(it) },
                        onMas = { herramientas = true },
                    )
                }
                if (herramientas) {
                    HojaHerramientas(herramientasDe(nube, urgentOrders), onCerrar = { herramientas = false }) { id ->
                        herramientas = false
                        menuOrigen = currentRoute
                        menuDestino = when (id) {
                            "vender" -> Routes.SALE; "agregar" -> Routes.PRODUCT_FORM; "inventario" -> Routes.DASHBOARD
                            "pedidos_clientes" -> Routes.ORDERS; "chats" -> Routes.CHATS; "mapa" -> Routes.MAPA; "graficas" -> Routes.STATS
                            "categorias" -> Routes.CATEGORIES; "bluetooth" -> Routes.SYNC; "sincronizar" -> null
                            else -> Routes.ANAQUEL
                        }
                        val seccion = when (id) {
                            "hoy" -> SeccionAnaquel.HOY; "foto" -> SeccionAnaquel.ANAQUELES; "pedidos" -> SeccionAnaquel.PEDIDOS
                            "bodega" -> SeccionAnaquel.BODEGA; "red" -> SeccionAnaquel.RED; "tratos" -> SeccionAnaquel.TRATOS
                            "asesor" -> SeccionAnaquel.ASESOR; "mayoreo" -> SeccionAnaquel.MAYOREO; else -> null
                        }
                        if (seccion != null) {
                            anaquel.elegirSeccion(seccion)
                            irPestana(Routes.ANAQUEL)
                        } else when (id) {
                            "vender" -> navController.navigate(Routes.SALE)
                            "agregar" -> navController.navigate(Routes.productForm(null))
                            "inventario" -> irPestana(Routes.DASHBOARD)
                            "pedidos_clientes" -> irPestana(Routes.ORDERS)
                            "chats" -> irPestana(Routes.CHATS)
                            "mapa" -> navController.navigate(Routes.MAPA)
                            "graficas" -> navController.navigate(Routes.STATS)
                            "categorias" -> navController.navigate(Routes.CATEGORIES)
                            "bluetooth" -> navController.navigate(Routes.SYNC)
                            "sincronizar" -> anaquel.sincronizarInventario()
                        }
                    }
                }
            }
        }
    }
}
