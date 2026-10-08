package vn.anta.booking.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import vn.anta.booking.Logger
import vn.anta.booking.data.OtpPurpose
import vn.anta.booking.data.VenueSort
import vn.anta.booking.ui.account.AccountScreen
import vn.anta.booking.ui.account.DocumentScreen
import vn.anta.booking.ui.auth.CodeFlowScreen
import vn.anta.booking.ui.auth.LoginScreen
import vn.anta.booking.ui.booking.BookingScreen
import vn.anta.booking.ui.booking.BookingsScreen
import vn.anta.booking.ui.booking.SlotPickerScreen
import vn.anta.booking.ui.components.LocalFavoriteIds
import vn.anta.booking.ui.components.appContainer
import vn.anta.booking.ui.explore.ExploreScreen
import vn.anta.booking.ui.favorites.FavoritesScreen
import vn.anta.booking.ui.home.HomeScreen
import vn.anta.booking.ui.map.MapScreen
import vn.anta.booking.ui.search.SearchScreen
import vn.anta.booking.ui.venue.VenueScreen
import vn.anta.booking.ui.venues.VenueListScreen

private object Routes {
    const val HOME = "home"
    const val EXPLORE = "explore"
    const val MAP = "map"
    const val BOOKINGS = "bookings"
    const val ACCOUNT = "account"
    const val SEARCH = "search"
    const val VENUES = "venues?sort={sort}&sport={sport}"
    const val VENUE = "venue/{id}"
    const val SLOTS = "venue/{id}/slots"
    const val BOOKING = "booking/{id}"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val RESET_PASSWORD = "reset-password"
    const val TERMS = "terms"
    const val FAVORITES = "favorites"

    fun venues(sportId: String?, sort: VenueSort) = "venues?sort=${sort.name}" + if (sportId != null) "&sport=$sportId" else ""
    fun venue(id: String) = "venue/$id"
    fun slots(venueId: String) = "venue/$venueId/slots"
    fun booking(id: String) = "booking/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "Trang chủ", Icons.Rounded.Home),
    Tab(Routes.EXPLORE, "Khám phá", Icons.Rounded.Explore),
    Tab(Routes.MAP, "Bản đồ", Icons.Rounded.Map),
    Tab(Routes.BOOKINGS, "Lịch đặt", Icons.Rounded.CalendarMonth),
    Tab(Routes.ACCOUNT, "Tài khoản", Icons.Rounded.Person)
)

/** Switches tab the way a bottom bar should: one copy of each tab, each keeping its own scroll position and state. */
private fun NavHostController.openTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun AntaRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    LaunchedEffect(route) { route?.let(Logger::screen) }
    val favorites by appContainer().favorites.venues.collectAsStateWithLifecycle()
    val favoriteIds = remember(favorites) { favorites.mapTo(HashSet()) { it.id } }

    CompositionLocalProvider(LocalFavoriteIds provides favoriteIds) { AntaScaffold(nav, route) }
}

@Composable
private fun AntaScaffold(nav: NavHostController, route: String?) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (TABS.any { it.route == route }) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = { Logger.click(tab.route, "bottom-bar"); nav.openTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    ) { padding ->
        val openVenue: (String) -> Unit = { nav.navigate(Routes.venue(it)) }
        // Leaves the sign-in screens for wherever the user came from, whether they arrived by one screen or two.
        val leaveAuth: () -> Unit = { if (!nav.popBackStack(Routes.LOGIN, inclusive = true)) nav.popBackStack() }

        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
            composable(Routes.HOME) {
                HomeScreen(padding, onSearch = { nav.navigate(Routes.SEARCH) }, onVenue = openVenue, onFeatured = { nav.navigate(Routes.venues(it, VenueSort.RATING)) }, onAccount = { nav.openTab(Routes.ACCOUNT) })
            }
            composable(Routes.EXPLORE) {
                ExploreScreen(
                    padding, onSearch = { nav.navigate(Routes.SEARCH) }, onVenue = openVenue,
                    onBrowse = { sportId, sort -> nav.navigate(Routes.venues(sportId, sort)) }, onFavorites = { nav.navigate(Routes.FAVORITES) }
                )
            }
            composable(Routes.MAP) { MapScreen(padding, onSearch = { nav.navigate(Routes.SEARCH) }, onVenue = openVenue) }
            composable(Routes.BOOKINGS) {
                BookingsScreen(padding, onLogin = { nav.navigate(Routes.LOGIN) }, onBooking = { nav.navigate(Routes.booking(it)) }, onExplore = { nav.openTab(Routes.EXPLORE) })
            }
            composable(Routes.ACCOUNT) {
                AccountScreen(
                    padding, onLogin = { nav.navigate(Routes.LOGIN) }, onRegister = { nav.navigate(Routes.REGISTER) }, onBookings = { nav.openTab(Routes.BOOKINGS) },
                    onFavorites = { nav.navigate(Routes.FAVORITES) }, onTerms = { nav.navigate(Routes.TERMS) }
                )
            }

            composable(Routes.SEARCH) { SearchScreen(onBack = nav::popBackStack, onVenue = openVenue) }
            composable(
                Routes.VENUES,
                arguments = listOf(navArgument("sort") { type = NavType.StringType; defaultValue = VenueSort.RATING.name }, navArgument("sport") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { stack ->
                val sort = VenueSort.entries.firstOrNull { it.name == stack.arguments?.getString("sort") } ?: VenueSort.RATING
                VenueListScreen(stack.arguments?.getString("sport"), sort, onBack = nav::popBackStack, onVenue = openVenue)
            }
            composable(Routes.VENUE) { stack ->
                val id = stack.arguments?.getString("id").orEmpty()
                VenueScreen(id, onBack = nav::popBackStack, onBook = { nav.navigate(Routes.slots(id)) }, onLogin = { nav.navigate(Routes.LOGIN) })
            }
            composable(Routes.SLOTS) { stack ->
                SlotPickerScreen(
                    stack.arguments?.getString("id").orEmpty(), onBack = nav::popBackStack, onLogin = { nav.navigate(Routes.LOGIN) },
                    // The timetable is done with once a booking exists: back from the booking leads to the venue.
                    onBooked = { bookingId -> nav.navigate(Routes.booking(bookingId)) { popUpTo(Routes.SLOTS) { inclusive = true } } }
                )
            }
            composable(Routes.BOOKING) { stack -> BookingScreen(stack.arguments?.getString("id").orEmpty(), onBack = nav::popBackStack, onVenue = openVenue) }

            composable(Routes.FAVORITES) {
                FavoritesScreen(onBack = nav::popBackStack, onLogin = { nav.navigate(Routes.LOGIN) }, onVenue = openVenue, onExplore = { nav.openTab(Routes.EXPLORE) })
            }

            composable(Routes.LOGIN) {
                LoginScreen(onBack = nav::popBackStack, onDone = leaveAuth, onRegister = { nav.navigate(Routes.REGISTER) }, onForgot = { nav.navigate(Routes.RESET_PASSWORD) })
            }
            composable(Routes.REGISTER) { CodeFlowScreen(OtpPurpose.REGISTER, onBack = nav::popBackStack, onDone = leaveAuth) }
            // A new password signs nobody in: the user goes back to the sign-in screen to use it.
            composable(Routes.RESET_PASSWORD) { CodeFlowScreen(OtpPurpose.RESET_PASSWORD, onBack = nav::popBackStack, onDone = nav::popBackStack) }
            composable(Routes.TERMS) { DocumentScreen("terms", "Điều khoản sử dụng", onBack = nav::popBackStack) }
        }
    }
}
