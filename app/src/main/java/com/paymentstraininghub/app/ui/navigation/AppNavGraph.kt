// AppNavGraph.kt
// Payments Training Hub Android - root navigation: Scaffold with the 4 tab
// bottom bar (matching the iOS tab order) plus every detail and
// feature destination. Deep links arrive through initialRoute and
// through the one-shot DeepLinkRouter events flow. The local notification system
// is wired here, mirroring TrainingHubApp.swift: the service is
// initialised once, the schedule is refreshed on every foreground,
// preferences reload on every identity change, pending deep links
// (including notification taps) are routed, and the one-time
// permission gate is mounted. No em dashes in any user-facing text.
//
// ACCESS MODEL: content is browsable without a session. The app
// opens to browsable content whether or not a session exists; the
// first-run introduction shows on first launch regardless of
// session. Sign-in is prompted only when the learner taps a Buy
// action while signed out: the purchase route is remembered, the
// sign-in screen shows, and after sign-in the learner returns to
// the purchase. Signing out returns the app to guest browsing.
// A content deep link lands directly, with no session, and a warm
// link waits in the deep-link consumer only while the first-run
// introduction shows. A reset link routes to the set-new-password
// screen, which lives in the same graph. An admin session unlocks
// all content and is never prompted to buy (see AdminClaims and
// the entitlement checks).
//
// MERGED by the v2 coordinator from the four port versions: PfTheme
// wrap + Studio/Weekly onNavigate (gating), email signup and
// password reset destinations (auth), notification wiring +
// notifications destination
// (notifications), investigation destinations + ISO message route +
// tab indicator + legal restyle (investigation), plus coordinator
// wiring of the BillingManager sign-in/sign-out entitlement hooks.

package com.paymentstraininghub.app.ui.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.auth.AuthManager
import com.paymentstraininghub.app.auth.MigrationOfferDialog
import com.paymentstraininghub.app.auth.MigrationTracker
import com.paymentstraininghub.app.billing.BillingManager
import com.paymentstraininghub.app.data.AgendaStore
import com.paymentstraininghub.app.data.CompetencyDimension
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.data.LearningPaths
import com.paymentstraininghub.app.features.analytics.AnalyticsService
import com.paymentstraininghub.app.features.assessment.AssessmentScreen
import com.paymentstraininghub.app.features.assessment.CertificatesScreen
import com.paymentstraininghub.app.features.assessment.CourseCertificateScreen
import com.paymentstraininghub.app.features.assessment.FinalAssessmentScreen
import com.paymentstraininghub.app.features.assessment.PathCertificateScreen
import com.paymentstraininghub.app.features.assessment.TestQuizScreen
import com.paymentstraininghub.app.features.challenge.DailyChallengeScreen
import com.paymentstraininghub.app.features.growth.CommunityChallengeScreen
import com.paymentstraininghub.app.features.growth.CommunityChallengeStore
import com.paymentstraininghub.app.features.growth.DeepLinkRouter
import com.paymentstraininghub.app.features.growth.WeeklyFreeCaseScreen
import com.paymentstraininghub.app.features.growth.WeeklyFreeCaseStore
import com.paymentstraininghub.app.features.habit.HabitScreen
import com.paymentstraininghub.app.features.investigation.InvestigationLabCaseRoute
import com.paymentstraininghub.app.features.investigation.InvestigationSituationRoute
import com.paymentstraininghub.app.features.investigation.InvestigationStore
import com.paymentstraininghub.app.features.investigation.InvestigationWeeklyRoute
import com.paymentstraininghub.app.features.iso.IsoExplorerScreen
import com.paymentstraininghub.app.features.lab.ExceptionLabScreen
import com.paymentstraininghub.app.features.lab.ExceptionLabStore
import com.paymentstraininghub.app.features.notifications.HabitReminderScheduler
import com.paymentstraininghub.app.features.notifications.LocalNotificationService
import com.paymentstraininghub.app.features.notifications.NotificationPermissionGate
import com.paymentstraininghub.app.features.notifications.NotificationSchedulers
import com.paymentstraininghub.app.features.onboarding.OnboardingFlowScreen
import com.paymentstraininghub.app.features.onboarding.OnboardingStore
import com.paymentstraininghub.app.features.practice.PracticeScreen
import com.paymentstraininghub.app.features.practice.PracticeStore
import com.paymentstraininghub.app.features.revision.RevisionCentreScreen
import com.paymentstraininghub.app.features.settings.AboutScreen
import com.paymentstraininghub.app.features.settings.AppearanceScreen
import com.paymentstraininghub.app.features.settings.DiagnosticsScreen
import com.paymentstraininghub.app.features.settings.HelpSupportScreen
import com.paymentstraininghub.app.features.tour.TourStore
import com.paymentstraininghub.app.security.EventLog
import com.paymentstraininghub.app.security.PremiumAccessGate
import com.paymentstraininghub.app.sync.SyncService
import com.paymentstraininghub.app.features.simulator.SimulatorScreen
import com.paymentstraininghub.app.features.studio.StudioDraftStore
import com.paymentstraininghub.app.features.studio.StudioScreen
import com.paymentstraininghub.app.ui.auth.AuthScreen
import com.paymentstraininghub.app.ui.auth.EmailSignupScreen
import com.paymentstraininghub.app.ui.auth.ForgotPasswordScreen
import com.paymentstraininghub.app.ui.auth.RegistrationWelcomePopup
import com.paymentstraininghub.app.ui.auth.SetNewPasswordScreen
import com.paymentstraininghub.app.ui.cases.SituationDetailScreen
import com.paymentstraininghub.app.ui.cases.SituationsScreen
import com.paymentstraininghub.app.ui.checkout.CheckoutScreen
import com.paymentstraininghub.app.ui.home.HomeScreen
import com.paymentstraininghub.app.ui.learn.ChapterDetailScreen
import com.paymentstraininghub.app.ui.learn.EntryDetailScreen
import com.paymentstraininghub.app.ui.learn.GuideListScreen
import com.paymentstraininghub.app.ui.learn.InterviewCategoryScreen
import com.paymentstraininghub.app.ui.learn.InterviewQuestionsScreen
import com.paymentstraininghub.app.ui.learn.LabListScreen
import com.paymentstraininghub.app.ui.learn.PathDetailScreen
import com.paymentstraininghub.app.ui.learn.SessionDetailScreen
import com.paymentstraininghub.app.ui.learn.TestListScreen
import com.paymentstraininghub.app.ui.lesson.LessonScreen
import com.paymentstraininghub.app.ui.profile.NotificationSettingsScreen
import com.paymentstraininghub.app.ui.profile.ProfileScreen
import com.paymentstraininghub.app.ui.progress.ProgressScreen
import com.paymentstraininghub.app.ui.search.SearchScreen
import com.paymentstraininghub.app.ui.theme.PfColors
import com.paymentstraininghub.app.ui.theme.PfPrimaryButton
import com.paymentstraininghub.app.ui.theme.PfSpacing
import com.paymentstraininghub.app.ui.theme.PfTextStyles
import com.paymentstraininghub.app.ui.theme.PfTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val TAB_ITEMS = listOf(
    TabItem(Routes.HOME, "Home", Icons.Filled.Home),
    TabItem(Routes.SEARCH, "Search", Icons.Filled.Search),
    TabItem(Routes.CASES, "Payment Cases", Icons.Filled.Work),
    TabItem(Routes.PROGRESS, "My Progress", Icons.Filled.BarChart)
)

private val TAB_BASES = TAB_ITEMS.map { it.route }.toSet()

/**
 * The Home destination's route pattern, exactly as declared in
 * the NavHost below. Tab popUpTo targets use this
 * pattern, never findStartDestination(): the graph's
 * startDestinationId hashes the bare "home" route while the Home
 * destination's id hashes this pattern, so the id lookup throws.
 * The pattern matches the Home entry whatever section argument
 * it carries. Declared once so the NavHost route and every
 * popUpTo target can never drift apart.
 */
private val HOME_DESTINATION_ROUTE = Routes.HOME + "?section={section}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavGraph(initialRoute: String?) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    // Auth state. The graph never gates on it: content shows
    // whether or not a session exists. The state drives the
    // sign-in prompt on Buy actions, the notification prompt
    // mount, and the first-sign-in overlays below.
    val authState by AuthManager.authState.collectAsState()
    val signedIn = authState is AuthManager.AuthState.SignedIn
    val showWelcome by AuthManager.showRegistrationWelcome.collectAsState()
    val welcomeCaseID by AuthManager.registrationWelcomeCaseID.collectAsState()
    // The guest-to-account migration offer, hoisted from the auth
    // screens: signing in pops them, so the offer is raised here
    // on a fresh sign-in and rendered above the app, after the
    // introduction.
    var showMigrationOffer by remember { mutableStateOf(false) }

    // Onboarding gate: the flow shows once per install, before the
    // app chrome, and deep-link routing holds until it completes,
    // mirroring MainTabView.resolvePendingDeepLink.
    var showOnboarding by remember {
        mutableStateOf(!TourStore.isOnboardingCompleted(appContext))
    }
    var postOnboardingRoute by remember { mutableStateOf<String?>(null) }

    // Side drawer state. The drawer matches the iOS SideDrawerView
    // entries.
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Increments on every Home bottom-bar tap while Home is
    // already showing: Home resets to the top of the Learning
    // Journey tab. Home ignores the starting value.
    var homeReselectTick by remember { mutableIntStateOf(0) }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    // Centralised tab navigation with source logging (solved-APK
    // defect 3): the bottom bar and the drawer share this one
    // path, so every tab switch carries the trigger that caused
    // it in the Diagnostics event log.
    fun navigateToTab(route: String, source: String) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        logNavigationEvent(source, route, currentRoute)
        NavTrace.navigate(navController, source, route) {
            // Route-based popUpTo, never findStartDestination():
            // the graph's startDestinationId is the id hash of
            // the bare "home" route, while the Home destination's
            // id is the hash of the "home?section={section}"
            // pattern, so findStartDestination() throws
            // NoSuchElementException on this graph (proven in
            // the harness: every tab switch crashed). The
            // pattern matches the Home entry exactly
            // (NavDestination.hasRoute), whatever section
            // argument it carries.
            popUpTo(HOME_DESTINATION_ROUTE) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Centralised content navigation: every screen's onNavigate
    // lambda funnels through here. A double tap on the same
    // destination within half a second navigates once (the second
    // tap is dropped), and launchSingleTop keeps a re-tap on the
    // destination already on top from stacking a duplicate. A
    // different destination, or the same destination after the
    // window, always navigates: this is a tap debounce, not a
    // route block.
    // The purchase route remembered when a signed-out learner
    // taps a Buy action: the sign-in screen shows instead, and
    // after sign-in the learner returns to this route.
    var pendingPurchaseRoute by remember { mutableStateOf<String?>(null) }

    var lastContentNavRoute by remember { mutableStateOf<String?>(null) }
    var lastContentNavMs by remember { mutableLongStateOf(0L) }
    fun navigateContent(route: String, source: String = "CONTENT_NAV") {
        val nowMs = SystemClock.elapsedRealtime()
        if (route == lastContentNavRoute && nowMs - lastContentNavMs < 500L) {
            NavTrace.logDrop(source, route, "debounce-500ms")
            return
        }
        lastContentNavRoute = route
        lastContentNavMs = nowMs
        // Buy actions prompt sign-in while signed out: the
        // purchase route is remembered and the sign-in screen
        // shows instead. Every other route opens directly, with
        // no session and no prompt.
        val signedInNow = AuthManager.authState.value is AuthManager.AuthState.SignedIn
        val target = AccessPolicy.routeForPurchaseAttempt(route, signedInNow)
        if (target != route) {
            pendingPurchaseRoute = route
            NavTrace.navigate(navController, source, target, "redirectFrom=$route") {
                launchSingleTop = true
            }
            return
        }
        NavTrace.navigate(navController, source, route) {
            launchSingleTop = true
        }
    }

    // After a successful sign-in from the auth screens: leave
    // the auth flow and return to the remembered purchase, when
    // one is waiting. Popping the auth screens returns to the
    // content underneath; when the sign-in screen is the start
    // destination (a cold-start OAuth callback), it is replaced
    // with Home instead, so Back can never return to it. The
    // pending purchase navigates directly, bypassing the tap
    // debounce, so a fast sign-in can never drop the return.
    fun afterAuthSignedIn() {
        val pending = pendingPurchaseRoute
        pendingPurchaseRoute = null
        if (pending != null) {
            navController.popBackStack(Routes.AUTH, inclusive = true)
            NavTrace.navigate(navController, "AFTER_AUTH_PENDING", pending) {
                launchSingleTop = true
            }
            return
        }
        if (!navController.popBackStack()) {
            NavTrace.navigate(navController, "AFTER_AUTH_HOME", Routes.HOME) {
                popUpTo(Routes.AUTH) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Drawer navigation: tab routes switch tabs with the same
    // save/restore semantics as the tab bar; everything else is a
    // plain push, matching the iOS sheet presentation. Both paths
    // log their source, like the tab bar. The push uses
    // launchSingleTop so re-tapping the drawer row for the
    // screen already on top (Courses while on Courses, for
    // example) does not stack a duplicate entry that Back
    // would return to with identical content.
    fun navigateFromDrawer(route: String) {
        if (route in TAB_BASES) {
            // The drawer source carries the tab, so the drawer
            // path to My Progress reads APP_DRAWER_TAB_PROGRESS
            // and is told apart from the bottom bar and the Home
            // card. Tab routes are bare constants, so uppercasing
            // is exact.
            navigateToTab(route, "APP_DRAWER_TAB_${route.uppercase()}")
        } else {
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            logNavigationEvent("APP_DRAWER_PUSH", route, currentRoute)
            NavTrace.navigate(navController, "APP_DRAWER_PUSH", route) {
                launchSingleTop = true
            }
        }
    }

    // Drawer selection is serialised (solved-APK defect 3): one
    // selection closes the drawer and then navigates, in a single
    // coroutine, and a second tap while that is in progress is
    // ignored, so rapid repeated selections navigate exactly
    // once. This replaces the at-once navigation (Tester 2 E4):
    // the close animation is short, and one guarded navigation
    // beats two racing ones.
    var drawerNavInProgress by remember { mutableStateOf(false) }
    fun selectFromDrawer(route: String) {
        if (drawerNavInProgress) return
        drawerNavInProgress = true
        scope.launch {
            try {
                drawerState.close()
                navigateFromDrawer(route)
            } finally {
                drawerNavInProgress = false
            }
        }
    }

    // NavTrace destination listener: every landing, including
    // Back presses and pops, is logged with its arguments and
    // a back-stack snapshot, so a redirect seen on a device can
    // be traced to the exact trigger. Logging only; it changes
    // no route, option, or timing.
    DisposableEffect(Unit) {
        val listener = NavTrace.install(navController)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    // Start Google Play Billing once for the app session. BillingManager
    // guards repeat calls; prices stay unavailable until this has run.
    // The premium access gate refreshes alongside it: only a Blocked
    // verdict denies premium (Unknown unlocks, matching iOS), so the
    // refresh must start with the graph, not on first premium use.
    LaunchedEffect(Unit) {
        BillingManager.startBillingClient(context.applicationContext)
        PremiumAccessGate.refresh(appContext)
        ContentRepository.refreshReadingOrder(appContext.filesDir)
        AgendaStore.refreshIfNeeded(appContext.filesDir)
    }

    // The account-deletion sync hook (AuthManager.onDeleteAccountLocal)
    // is wired in TrainingHubApp.onCreate on the application scope,
    // which survives navigation and Activity recreation; it must not
    // be wired here on the composition scope.

    // Local notifications, mirroring TrainingHubApp.swift: initialise
    // the service once and enqueue the daily schedule reconciler.
    // WorkManager persists the reconciler across reboots.
    LaunchedEffect(Unit) {
        LocalNotificationService.init(appContext)
        NotificationSchedulers.enqueueReconciler(appContext)
    }

    // On every foreground, refresh the system permission state and
    // make the scheduled local notifications match it and the
    // current preferences, plus re-apply the habit reminder
    // schedule. Both refreshes are cheap and idempotent, and the
    // permission refresh runs first so the schedule uses fresh
    // state. Nothing here prompts: the one-time explanation lives
    // in NotificationPermissionGate below. Mirrors the iOS
    // scenePhase .active handler.
    DisposableEffect(Unit) {
        val owner = ProcessLifecycleOwner.get()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                LocalNotificationService.refreshAuthorizationStatus(appContext)
                LocalNotificationService.refreshScheduledNotifications(appContext)
                scope.launch {
                    HabitReminderScheduler.applyCurrentSchedule(appContext)
                }
                // Re-check the premium gate on every foreground
                // return: root status, installer and the integrity
                // verdict can all change while the app is stopped.
                // A fresh cached pass makes this cheap.
                scope.launch {
                    PremiumAccessGate.refresh(appContext)
                }
                // Re-check Play and server entitlements on every
                // foreground return too: refreshEntitlements is
                // the refund and revocation path, and its doc
                // says the coordinator wires it here. Without
                // this call, a refunded or expired purchase kept
                // unlocking until the next restore or sign-in.
                // The call is idempotent and swallows its own
                // failures, so a failed refresh keeps the last
                // known state.
                scope.launch {
                    BillingManager.refreshEntitlements()
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // Identity changed (sign-in or sign-out): reload notification
    // preferences for the new namespace, and re-sync or clear
    // entitlements for the new identity. The first emission is the
    // launch state, which init already loaded, so it is skipped.
    // Mirrors the iOS onChange(of: authService.currentUser) handler.
    // Guest preferences are never copied into the new account.
    LaunchedEffect(Unit) {
        var first = true
        var previousUserId: String? = null
        var previousKnown = false
        AuthManager.authState.collect { state ->
            val userId = when (state) {
                is AuthManager.AuthState.Guest -> null
                is AuthManager.AuthState.SignedIn -> state.userId
            }
            if (first) {
                first = false
                previousUserId = userId
                previousKnown = true
            } else if (previousKnown && userId != previousUserId) {
                previousUserId = userId
                // Coordinator wiring (gating port requirement):
                // sign-in re-syncs entitlements from both sources,
                // sign-out drops the account-scoped entitlements so
                // the next account never inherits them.
                if (userId != null) {
                    BillingManager.syncEntitlementsAfterSignIn()
                    // Fresh sign-in migration offer: when guest
                    // progress exists on this device and the offer
                    // was never resolved for this account, raise
                    // it here, above the graph. The first emission
                    // is skipped above, so a launch restore never
                    // raises it. A failed check never blocks
                    // sign-in; the offer is simply not made.
                    try {
                        if (MigrationTracker.shouldOffer(userId) && AppStore.guestHasData()) {
                            showMigrationOffer = true
                        }
                    } catch (_: Exception) {
                        // The offer is best effort; sign-in stands.
                    }
                } else {
                    BillingManager.clearAccountEntitlements()
                }
                // Coordinator wiring (v1 assumption 30, never wired
                // until now): every account-namespaced store caches
                // its data in a StateFlow loaded once at init, so
                // each one must reload for the new namespace or the
                // previous account's lab results, studio drafts,
                // challenge attempts, weekly-case opens,
                // investigation results and analytics consent stay
                // on screen and leak into the new account's saves.
                // HabitStore reloads itself when the habit screen
                // opens; ReferralStore reloads inside AuthManager.
                CommunityChallengeStore.reloadForCurrentAccount()
                WeeklyFreeCaseStore.reloadForCurrentAccount()
                ExceptionLabStore.reloadForCurrentAccount()
                StudioDraftStore.reloadForCurrentAccount()
                InvestigationStore.reloadForCurrentAccount()
                PracticeStore.reloadForCurrentAccount()
                OnboardingStore.reloadForCurrentAccount()
                AnalyticsService.reloadForCurrentAccount()
                LocalNotificationService.reloadForCurrentAccount(appContext)
                scope.launch {
                    HabitReminderScheduler.applyCurrentSchedule(appContext)
                }
            }
        }
    }

    // Start destination, resolved by the pure access rules: the
    // cold-start link itself when it names a known route, Home
    // otherwise. Content links land directly, with no session.
    val mainStartDestination = AccessPolicy.mainStartDestination(initialRoute)

    // Content gate for one-shot content-link events, mirroring
    // showOnboarding as a flow: the persistent consumer below
    // waits on this instead of restarting when the introduction
    // finishes, so the flip can never replay a stale event. A
    // link that arrives while the first-run introduction shows
    // waits in the consumer until it finishes, so no fresh event
    // is ever lost. Sign-in never holds a link: content opens
    // with no session. Reset links do not wait on this gate:
    // they travel on their own stream and only wait for the
    // session restore, so a reset link routes to the
    // set-new-password screen without delay.
    val contentGate = remember { MutableStateFlow(false) }
    LaunchedEffect(showOnboarding) {
        contentGate.value = !showOnboarding
    }

    // Shared decision and navigation for one-shot deep link
    // events. Validation drops duplicates, expired events, and
    // unknown routes before any navigation. The event is marked
    // consumed before navigating, so exactly one navigation
    // happens per event. Recovery takes precedence: while a
    // reset token waits, a content link is dropped rather than
    // routed over it. A link whose route is already the start
    // destination is consumed without navigating, so a
    // cold-start link handled through initialRoute is never
    // routed twice. Events always route through the one main
    // graph, whether or not a session exists. Returns the
    // updated last-consumed id.
    val routeEvent: suspend (DeepLinkRouter.PendingEvent, Long) -> Long =
        { event, lastConsumedId ->
            val decision = NavigationEventValidator.decide(
                event = event,
                lastConsumedId = lastConsumedId,
                nowMs = System.currentTimeMillis(),
                startDestination = mainStartDestination,
                recoveryTokenPending =
                    DeepLinkRouter.pendingRecoveryToken.value != null,
                isKnownRoute = { Routes.isKnownRoute(it) }
            )
            val nextConsumedId = maxOf(lastConsumedId, event.id)
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            when (decision) {
                is NavigationDecision.Navigate -> {
                    val source = if (event is DeepLinkRouter.PendingEvent.Recovery) {
                        "DEEPLINK_RECOVERY_RECEIVED"
                    } else {
                        "DEEPLINK_RECEIVED"
                    }
                    logNavigationEvent(source, decision.route, currentRoute)
                    // The NavHost may not be mounted yet: a link
                    // decided while the introduction covers the
                    // main graph must wait for the NavHost to
                    // exist before navigating, or navigate()
                    // throws on a controller with no graph. When
                    // the graph is already mounted this returns
                    // at once.
                    navController.currentBackStackEntryFlow.first { it != null }
                    NavTrace.navigate(navController, source, decision.route)
                }
                is NavigationDecision.Drop -> {
                    EventLog.record(
                        "NAV",
                        "DEEPLINK_DROPPED ${decision.reason} from=${currentRoute ?: "?"}"
                    )
                }
            }
            nextConsumedId
        }

    // Persistent content-link consumer: collects the one-shot
    // DeepLinkRouter events for the lifetime of the graph. A
    // link that arrives while the introduction shows waits here
    // until it finishes, so no fresh event is ever lost; the
    // wait reads the single gate flow only. A reset link never
    // queues behind this wait: reset links travel on their own
    // stream with their own consumer below.
    LaunchedEffect(Unit) {
        var lastConsumedId = 0L
        while (true) {
            val event = DeepLinkRouter.receiveEvent()
            if (!contentGate.value) {
                contentGate.first { it }
            }
            lastConsumedId = routeEvent(event, lastConsumedId)
        }
    }

    // Persistent reset-link consumer: reset links wait only for
    // the session restore, never for sign-in or the
    // introduction, so a reset link routes to the
    // set-new-password screen even while a content link is
    // still waiting on the gate above.
    LaunchedEffect(Unit) {
        var lastConsumedId = 0L
        while (true) {
            val event = DeepLinkRouter.receiveRecoveryEvent()
            AuthManager.authReady.first { it }
            lastConsumedId = routeEvent(event, lastConsumedId)
        }
    }

    // A summary-card tap during onboarding finishes the flow and
    // then opens the content. Runs once the NavHost exists.
    LaunchedEffect(postOnboardingRoute, showOnboarding) {
        val route = postOnboardingRoute
        if (!showOnboarding && route != null) {
            postOnboardingRoute = null
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            logNavigationEvent("POST_ONBOARDING_NAVIGATION", route, currentRoute)
            NavTrace.navigate(navController, "POST_ONBOARDING_NAVIGATION", route)
        }
    }

    // The one-time notification permission explanation. It renders
    // nothing except the dialog, and only after onboarding has
    // completed. See NotificationPermissionPrompt.kt.
    // It is mounted only with a session: guests are never asked.
    if (signedIn) {
        NotificationPermissionGate()
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentBase = backStackEntry?.destination?.route
        ?.substringBefore("/")
        ?.substringBefore("?")
        .orEmpty()
    // The Learn route renders the merged Home, so for tab-bar
    // purposes it counts as Home: the bar stays visible and the
    // Home tab stays selected. The drawer keeps the raw base so
    // its Learn row still highlights.
    val tabBase = if (currentBase == Routes.LEARN) Routes.HOME else currentBase
    val showBottomBar = tabBase in TAB_BASES

    // The whole app renders inside PfTheme. The pf-painted cards
    // and lesson pages stay light; the stored Dark mode preference
    // changes Material surfaces only, and Larger text scales fonts.
    // The side drawer wraps the Scaffold.
    PfTheme {
        // No sign-in gate: the app opens to browsable content
        // whether or not a session exists. The first-run
        // introduction shows on first launch regardless of
        // session, and sign-in is prompted only when the
        // learner taps a Buy action while signed out.
        if (showOnboarding) {
            OnboardingFlowScreen(
                onFinish = {
                    showOnboarding = false
                },
                onFinishAndNavigate = { route ->
                    postOnboardingRoute = route
                    showOnboarding = false
                }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                // Edge gestures only on the tab roots, EXCEPT
                // Payment Cases: a deliberate behaviour change
                // (solved-APK defect 3) to isolate the fling
                // gesture fight on that screen. The menu button
                // still opens the drawer there, and the inner
                // Categories drawer is unchanged. On auth and
                // pushed screens a swipe must never drag drawer
                // chrome over the form underneath.
                gesturesEnabled = showBottomBar && currentBase != Routes.CASES,
                scrimColor = Color.Black.copy(alpha = 0.45f),
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .widthIn(max = 360.dp),
                        drawerContainerColor = PfColors.card,
                        drawerContentColor = PfColors.navy
                    ) {
                        AppDrawer(
                            currentRoute = currentBase,
                            // Drawer taps are serialised
                            // (selectFromDrawer): close, then
                            // navigate, one at a time.
                            onSelect = { destination ->
                                destination.route?.let { route ->
                                    selectFromDrawer(route)
                                }
                            },
                            // The guided tour was removed: the
                            // drawer keeps its tour parameters
                            // for call-site compatibility, but
                            // nothing starts the tour anymore.
                            onStartTour = {},
                            onContinueLearning = {
                                selectFromDrawer(Routes.HOME)
                            },
                            // The moved capability rows navigate
                            // through the same serialised drawer
                            // path as the destinations.
                            onNavigateRoute = { route -> selectFromDrawer(route) }
                        )
                    }
                }
            ) {
                Scaffold(
            containerColor = PfColors.background,
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(
                        containerColor = PfColors.card
                    ) {
                        TAB_ITEMS.forEach { tab ->
                            val selected = tabBase == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    // Re-tapping Home while Home is
                                    // showing resets Home to the top
                                    // of the Learning Journey tab.
                                    if (tab.route == Routes.HOME && tabBase == Routes.HOME) {
                                        homeReselectTick++
                                    }
                                    navigateToTab(tab.route, "BOTTOM_BAR_${tab.route.uppercase()}")
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = PfColors.royal,
                                    selectedTextColor = PfColors.royal,
                                    unselectedIconColor = PfColors.secondaryText,
                                    unselectedTextColor = PfColors.secondaryText,
                                    // Royal tint, not gold: the iOS tab bar marks
                                    // the selected tab in royal on white, with no
                                    // gold pill indicator.
                                    indicatorColor = PfColors.royal.copy(alpha = 0.10f)
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = mainStartDestination,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(
                    route = HOME_DESTINATION_ROUTE,
                    arguments = listOf(
                        navArgument("section") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { entry ->
                    HomeScreen(
                        onNavigate = { navigateContent(it) },
                        onOpenDrawer = openDrawer,
                        initialSection = entry.arguments?.getString("section"),
                        reselectTick = homeReselectTick,
                        onOpenProgress = { navigateContent(Routes.PROGRESS, "HOME_PROGRESS_CARD") }
                    )
                }
                // No separate Learn page: the Learn route renders
                // the merged Home on the Courses tab, so every
                // existing Learn link keeps landing on the course
                // browsing it promised.
                composable(Routes.LEARN) {
                    HomeScreen(
                        onNavigate = { navigateContent(it) },
                        onOpenDrawer = openDrawer,
                        initialSection = Routes.HOME_SECTION_COURSES,
                        reselectTick = homeReselectTick,
                        onOpenProgress = { navigateContent(Routes.PROGRESS, "HOME_PROGRESS_CARD") }
                    )
                }
                composable(Routes.SEARCH) {
                    SearchScreen(
                        onNavigate = { navigateContent(it) },
                        onOpenDrawer = openDrawer
                    )
                }
                composable(Routes.CASES) {
                    SituationsScreen(
                        onNavigate = { navigateContent(it) },
                        onOpenDrawer = openDrawer,
                        isOuterDrawerOpen = { drawerState.isOpen }
                    )
                }
                composable(Routes.PROGRESS) {
                    ProgressScreen(
                        onNavigate = { navigateContent(it) },
                        onOpenDrawer = openDrawer
                    )
                }
                composable(
                    route = Routes.LESSON,
                    arguments = listOf(navArgument("lessonId") { type = NavType.StringType })
                ) { entry ->
                    LessonScreen(
                        lessonId = entry.arguments?.getString("lessonId").orEmpty(),
                        onNavigateToCheckout = { productId ->
                            navigateContent(Routes.checkout(productId))
                        },
                        onNavigateToLesson = { lessonId ->
                            navigateContent(Routes.lesson(lessonId))
                        },
                        onBack = { navController.popBackStack() },
                        onNavigateToCheckoutList = { navigateContent(Routes.CHECKOUT) }
                    )
                }
                composable(
                    route = Routes.SITUATION,
                    arguments = listOf(navArgument("situationId") { type = NavType.StringType })
                ) { entry ->
                    SituationDetailScreen(
                        situationId = entry.arguments?.getString("situationId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.SITUATION_WEEKLY,
                    arguments = listOf(navArgument("situationId") { type = NavType.StringType })
                ) { entry ->
                    SituationDetailScreen(
                        situationId = entry.arguments?.getString("situationId").orEmpty(),
                        isWeeklyFreeCase = true,
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.INVESTIGATION_SITUATION,
                    arguments = listOf(navArgument("situationId") { type = NavType.StringType })
                ) { entry ->
                    InvestigationSituationRoute(
                        situationId = entry.arguments?.getString("situationId").orEmpty(),
                        isWeeklyFreeCase = false,
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.INVESTIGATION_SITUATION_WEEKLY,
                    arguments = listOf(navArgument("situationId") { type = NavType.StringType })
                ) { entry ->
                    InvestigationSituationRoute(
                        situationId = entry.arguments?.getString("situationId").orEmpty(),
                        isWeeklyFreeCase = true,
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.INVESTIGATION_LAB,
                    arguments = listOf(navArgument("labCaseId") { type = NavType.StringType })
                ) { entry ->
                    InvestigationLabCaseRoute(
                        labCaseId = entry.arguments?.getString("labCaseId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.INVESTIGATION_WEEKLY) {
                    InvestigationWeeklyRoute(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.CHECKOUT) {
                    CheckoutScreen(
                        productId = null,
                        onBack = { navController.popBackStack() },
                        onShowAllCourses = { navigateContent(Routes.CHECKOUT) },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.CHECKOUT_PRODUCT,
                    arguments = listOf(navArgument("productId") { type = NavType.StringType })
                ) { entry ->
                    CheckoutScreen(
                        productId = entry.arguments?.getString("productId"),
                        onBack = { navController.popBackStack() },
                        onShowAllCourses = { navigateContent(Routes.CHECKOUT) },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.NOTIFICATIONS) {
                    NotificationSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.AUTH) {
                    AuthScreen(
                        onSignedIn = { afterAuthSignedIn() },
                        onCreateAccount = { navigateContent(Routes.EMAIL_SIGNUP) },
                        onForgotPassword = { navigateContent(Routes.FORGOT_PASSWORD) }
                    )
                }
                composable(Routes.EMAIL_SIGNUP) {
                    EmailSignupScreen(
                        onSignedIn = { afterAuthSignedIn() },
                        onBack = { navController.popBackStack() },
                        onSignInInstead = { navController.popBackStack() },
                        onForgotPassword = { navigateContent(Routes.FORGOT_PASSWORD) }
                    )
                }
                composable(Routes.FORGOT_PASSWORD) {
                    ForgotPasswordScreen(
                        onBack = { navController.popBackStack() },
                        onSignIn = { navController.popBackStack() }
                    )
                }
                composable(Routes.RESET_PASSWORD) {
                    SetNewPasswordScreen(
                        onBackToSignIn = { navigateContent(Routes.AUTH) },
                        onRequestNewLink = { navigateContent(Routes.FORGOT_PASSWORD) }
                    )
                }
                composable(Routes.SIMULATOR) { SimulatorScreen(onNavigate = { navigateContent(it) }) }
                composable(Routes.ISO) { IsoExplorerScreen() }
                composable(
                    route = Routes.ISO_MESSAGE,
                    arguments = listOf(navArgument("messageId") { type = NavType.StringType })
                ) { entry ->
                    IsoExplorerScreen(
                        initialMessageId = entry.arguments?.getString("messageId")
                    )
                }
                composable(Routes.LAB) { ExceptionLabScreen(onNavigate = { navigateContent(it) }) }
                composable(Routes.STUDIO) { StudioScreen(onNavigate = { navigateContent(it) }) }
                composable(Routes.WEEKLY_CASE) { WeeklyFreeCaseScreen(onNavigate = { navigateContent(it) }) }
                composable(Routes.CHALLENGE) { CommunityChallengeScreen(onNavigate = { navigateContent(it) }) }
                composable(Routes.DAILY_CHALLENGE) {
                    DailyChallengeScreen(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.HABIT) { HabitScreen(onBack = { navController.popBackStack() }) }
                composable(Routes.PRIVACY) {
                    LegalScreen(
                        title = "Privacy Policy",
                        body = "The Privacy Policy explains how your data is used. The full document opens in your browser.",
                        url = "https://www.paymentstraininghub.com/privacy",
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.TERMS) {
                    LegalScreen(
                        title = "Terms of Use",
                        body = "The Terms of Use are the terms for using Payments Training Hub. The full document opens in your browser.",
                        url = "https://www.paymentstraininghub.com/terms",
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.PRACTICE) {
                    PracticeScreen(
                        onNavigate = { navigateContent(it) },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = Routes.PRACTICE_SITUATION,
                    arguments = listOf(navArgument("situationId") { type = NavType.StringType })
                ) { entry ->
                    PracticeScreen(
                        onNavigate = { navigateContent(it) },
                        onBack = { navController.popBackStack() },
                        initialSituationId = entry.arguments?.getString("situationId")
                    )
                }
                composable(Routes.APPEARANCE) {
                    AppearanceScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.HELP) {
                    HelpSupportScreen(
                        onBack = { navController.popBackStack() },
                        onReplayTour = {},
                        onOpenDiagnostics = { navigateContent(Routes.DIAGNOSTICS) },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.ABOUT) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.DIAGNOSTICS) {
                    DiagnosticsScreen(onBack = { navController.popBackStack() })
                }
                // Learn destinations: paths, sessions, chapters,
                // the guides/labs/tests library, the entry reading
                // view, the interview bank, and assessments.
                composable(
                    route = Routes.PATH,
                    arguments = listOf(navArgument("pathId") { type = NavType.StringType })
                ) { entry ->
                    PathDetailScreen(
                        pathId = entry.arguments?.getString("pathId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.SESSION,
                    arguments = listOf(navArgument("sessionId") { type = NavType.IntType })
                ) { entry ->
                    SessionDetailScreen(
                        sessionId = entry.arguments?.getInt("sessionId") ?: 0,
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.CHAPTER,
                    arguments = listOf(navArgument("chapterNumber") { type = NavType.IntType })
                ) { entry ->
                    ChapterDetailScreen(
                        chapterNumber = entry.arguments?.getInt("chapterNumber") ?: 0,
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.GUIDES) {
                    GuideListScreen(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.LABS) {
                    LabListScreen(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.TESTS) {
                    TestListScreen(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.TEST_QUIZ,
                    arguments = listOf(navArgument("entryId") { type = NavType.StringType })
                ) { entry ->
                    TestQuizScreen(
                        entryId = entry.arguments?.getString("entryId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.ENTRY,
                    arguments = listOf(navArgument("entryId") { type = NavType.StringType })
                ) { entry ->
                    EntryDetailScreen(
                        entryId = entry.arguments?.getString("entryId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.INTERVIEW) {
                    InterviewQuestionsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.INTERVIEW_CATEGORY,
                    arguments = listOf(navArgument("categoryId") { type = NavType.StringType })
                ) { entry ->
                    InterviewCategoryScreen(
                        categoryId = entry.arguments?.getString("categoryId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.ASSESSMENT_PATH,
                    arguments = listOf(navArgument("pathId") { type = NavType.StringType })
                ) { entry ->
                    val path = LearningPaths.all.firstOrNull {
                        it.id == entry.arguments?.getString("pathId")
                    }
                    if (path != null) {
                        AssessmentScreen(
                            dimension = path.assessmentDimension,
                            chapterNumbers = path.chapterNumbers,
                            sourceTitle = path.title,
                            onBack = { navController.popBackStack() },
                            onNavigate = { navigateContent(it) }
                        )
                    } else {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    }
                }
                composable(
                    route = Routes.ASSESSMENT_DIMENSION,
                    arguments = listOf(navArgument("dimension") { type = NavType.StringType })
                ) { entry ->
                    val dimension = CompetencyDimension.values().firstOrNull {
                        it.name == entry.arguments?.getString("dimension")
                    }
                    if (dimension != null) {
                        AssessmentScreen(
                            dimension = dimension,
                            chapterNumbers = dimension.chapterNumbers,
                            sourceTitle = dimension.label,
                            onBack = { navController.popBackStack() },
                            onNavigate = { navigateContent(it) }
                        )
                    } else {
                        LaunchedEffect(Unit) { navController.popBackStack() }
                    }
                }
                composable(Routes.CERTIFICATES) {
                    CertificatesScreen(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.FINAL_ASSESSMENT,
                    arguments = listOf(navArgument("productId") { type = NavType.StringType })
                ) { entry ->
                    FinalAssessmentScreen(
                        productId = entry.arguments?.getString("productId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(
                    route = Routes.COURSE_CERTIFICATE,
                    arguments = listOf(navArgument("productId") { type = NavType.StringType })
                ) { entry ->
                    CourseCertificateScreen(
                        productId = entry.arguments?.getString("productId").orEmpty(),
                        isSample = false,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.COURSE_CERTIFICATE_SAMPLE) {
                    CourseCertificateScreen(
                        productId = "",
                        isSample = true,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = Routes.PATH_CERTIFICATE,
                    arguments = listOf(navArgument("pathId") { type = NavType.StringType })
                ) { entry ->
                    PathCertificateScreen(
                        pathId = entry.arguments?.getString("pathId").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
                composable(Routes.REVISION) {
                    RevisionCentreScreen(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navigateContent(it) }
                    )
                }
            }
        }
            }
        }
        }
        // First-sign-in overlays, hoisted from the auth screens:
        // signing in pops those screens, so the migration offer
        // and the registration welcome popup live here, above
        // the app, and show only once the introduction is done.
        // The welcome popup shows first; the migration offer
        // waits until it has gone away, preserving the old
        // screens' sequencing.
        if (showMigrationOffer && signedIn && !showOnboarding && !showWelcome) {
            MigrationOfferDialog(
                guestHasProgress = true,
                onMigrate = {
                    showMigrationOffer = false
                    scope.launch {
                        val userId = AuthManager.currentUserId()
                        if (userId != null) {
                            try {
                                AppStore.migrateGuestToAccount(userId)
                            } catch (_: Exception) {
                                // Migration failure never blocks
                                // sign-in. Guest data stays in
                                // place for a retry.
                            }
                            MigrationTracker.markOffered(userId)
                        }
                    }
                },
                onDecline = {
                    showMigrationOffer = false
                    scope.launch {
                        AuthManager.currentUserId()?.let { MigrationTracker.markOffered(it) }
                    }
                }
            )
        }
        val welcomeCaseId = welcomeCaseID
        if (showWelcome && welcomeCaseId != null && signedIn && !showOnboarding) {
            // The popup auto-dismisses after 3 seconds; leaving
            // this composition inside that window cancels the
            // auto-dismiss and the flag stays set, so the popup
            // repeats on the next visit. Dismissing when the
            // popup leaves composition consumes the one-shot
            // exactly once.
            DisposableEffect(Unit) {
                onDispose { AuthManager.dismissRegistrationWelcome() }
            }
            RegistrationWelcomePopup(
                caseID = welcomeCaseId,
                profileIncomplete = true,
                onDismiss = { AuthManager.dismissRegistrationWelcome() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalScreen(title: String, body: String, url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, color = PfColors.navy) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = PfColors.card,
                titleContentColor = PfColors.navy,
                navigationIconContentColor = PfColors.navy
            )
        )
        Column(modifier = Modifier.padding(PfSpacing.medium)) {
            Text(body, style = PfTextStyles.body, color = PfColors.navy)
            Spacer(modifier = Modifier.height(PfSpacing.small))
            Text(url, style = PfTextStyles.callout, color = PfColors.secondaryText)
            Spacer(modifier = Modifier.height(PfSpacing.medium))
            PfPrimaryButton(
                title = "Open in browser",
                onClick = { openUrlInBrowser(context, url) }
            )
        }
    }
}

private fun openUrlInBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // No browser available. The URL stays visible on screen.
    }
}
