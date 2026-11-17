package com.paymentstraininghub.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.paymentstraininghub.app.auth.AppStore
import com.paymentstraininghub.app.data.CompetencyDimension
import com.paymentstraininghub.app.data.ContentRepository
import com.paymentstraininghub.app.features.assessment.AssessmentScreen
import com.paymentstraininghub.app.features.assessment.CertificatesScreen
import com.paymentstraininghub.app.features.assessment.CourseCertificateScreen
import com.paymentstraininghub.app.features.assessment.FinalAssessmentScreen
import com.paymentstraininghub.app.features.assessment.PathCertificateScreen
import com.paymentstraininghub.app.features.assessment.TestQuizScreen
import com.paymentstraininghub.app.features.challenge.DailyChallengeScreen
import com.paymentstraininghub.app.features.growth.CommunityChallengeScreen
import com.paymentstraininghub.app.features.growth.WeeklyFreeCaseScreen
import com.paymentstraininghub.app.features.habit.HabitScreen
import com.paymentstraininghub.app.features.investigation.InvestigationLabCaseRoute
import com.paymentstraininghub.app.features.investigation.InvestigationSituationRoute
import com.paymentstraininghub.app.features.investigation.InvestigationWeeklyRoute
import com.paymentstraininghub.app.features.iso.IsoExplorerScreen
import com.paymentstraininghub.app.features.lab.ExceptionLabScreen
import com.paymentstraininghub.app.features.onboarding.OnboardingFlowScreen
import com.paymentstraininghub.app.features.practice.PracticeScreen
import com.paymentstraininghub.app.features.revision.RevisionCentreScreen
import com.paymentstraininghub.app.features.settings.AboutScreen
import com.paymentstraininghub.app.features.settings.AppearanceScreen
import com.paymentstraininghub.app.features.settings.DiagnosticsScreen
import com.paymentstraininghub.app.features.settings.HelpSupportScreen
import com.paymentstraininghub.app.features.simulator.SimulatorScreen
import com.paymentstraininghub.app.features.studio.StudioScreen
import com.paymentstraininghub.app.features.tour.GuidedTourOverlay
import com.paymentstraininghub.app.ui.auth.AuthScreen
import com.paymentstraininghub.app.ui.auth.EmailSignupScreen
import com.paymentstraininghub.app.ui.auth.ForgotPasswordScreen
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
import com.paymentstraininghub.app.ui.theme.PfTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Render harness: every top-level screen from AppNavGraph, composed
 * with realistic fake data, on SDK levels 28, 33, 34 and 36. Any
 * composition crash fails the build. Screens load through the
 * ContentRepository test seam, so no test touches the network.
 * The plain Application avoids the real app init, which preloads
 * live content on launch. No em dashes in any string here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33, 34, 36], application = Application::class)
class RenderHarnessTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun installFakes() {
        // The real Application calls AppStore.init in onCreate;
        // the plain test Application does not, and screens that
        // read stored state throw until it has run. Idempotent,
        // and its load is joined before any read.
        AppStore.init(ApplicationProvider.getApplicationContext())
        RenderFakeData.install()
    }

    @After
    fun clearFakes() {
        ContentRepository.clearTestOverrides()
    }

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            PfTheme { content() }
        }
        composeRule.waitForIdle()
        // Fake loads resolve on background dispatchers; give them a
        // moment to land and recompose before asserting, so a crash
        // in the loaded state is caught here and not in the next test.
        Thread.sleep(SETTLE_MS)
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    private fun renderAndCheck(text: String, content: @Composable () -> Unit) {
        render(content)
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun renderAndCheckExists(text: String, content: @Composable () -> Unit) {
        // Existence, not visibility: some screens render the text
        // below the fold of the small test window, or twice (top
        // bar plus body). Either proves the fake data rendered.
        render(content)
        assertTrue(composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty())
    }

    // Tab roots.

    @Test
    fun homeScreenRenders() {
        render { HomeScreen(onNavigate = {}, onOpenDrawer = {}) }
    }

    @Test
    fun homeScreenCoursesAnchorLandsOnCourses() {
        // The merged Home: the Learn route and Learn links land
        // here, on the Courses tab.
        renderAndCheck("Courses") {
            HomeScreen(
                onNavigate = {},
                onOpenDrawer = {},
                initialSection = "courses"
            )
        }
    }

    @Test
    fun homeScreenJourneysAnchorLandsOnJourneys() {
        // The journeys section lives on the Learning Journey
        // tab, which is the default: the anchor lands at the
        // top of that tab, with the greeting in view.
        renderAndCheck("Master the world of payments, one lesson at a time.") {
            HomeScreen(
                onNavigate = {},
                onOpenDrawer = {},
                initialSection = "journeys"
            )
        }
    }

    @Test
    fun homeScreenCapabilitiesAnchorOpensTheDrawer() {
        // The capability entries live in the drawer now: the
        // capabilities anchor opens the drawer instead of
        // scrolling Home, and Home shows no capability list.
        var drawerOpened = false
        render {
            HomeScreen(
                onNavigate = {},
                onOpenDrawer = { drawerOpened = true },
                initialSection = "capabilities"
            )
        }
        assertTrue(drawerOpened)
        assertTrue(
            composeRule.onAllNodesWithText("Everything inside")
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test
    fun searchScreenRenders() {
        render { SearchScreen(onNavigate = {}, onOpenDrawer = {}) }
    }

    @Test
    fun situationsScreenRendersWithFreeSamples() {
        // The fake data carries ABH-01 and JEB-C01 as free samples
        // plus one situation per stage, so the duplicate-key crash
        // (a free sample keyed twice in one LazyColumn) is reachable
        // here and fails this test when the grouping regresses.
        renderAndCheck("Returned payment: closed beneficiary account") {
            SituationsScreen(onNavigate = {}, onOpenDrawer = {})
        }
        // Scroll through the list: the stage rows below the fold
        // are never composed until they scroll into view, so
        // without scrolling the duplicate key never registers and
        // the crash stays hidden. Scrolling composes the free
        // sample rows and their stage-group duplicates in the same
        // window when the grouping regresses, and Compose throws
        // on the repeated key.
        val list = composeRule.onNode(hasScrollToIndexAction())
        repeat(3) {
            list.performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }
    }

    @Test
    fun situationsScreenTallViewportRendersWithoutDuplicateKeys() {
        // Tall-viewport composition: the test window's list
        // viewport is shorter than the gap between a free sample
        // row and its stage-group duplicate, so the two never
        // compose together at window size, even while scrolling.
        // A 3000dp-tall container makes the LazyColumn compose
        // every row at once, so a repeated key must collide here
        // when the grouping regresses, and each free sample title
        // must appear exactly once in the composed tree.
        // requiredHeight, not height: the window constraints cap
        // a plain height request at the window size.
        composeRule.setContent {
            PfTheme {
                Box(modifier = Modifier.requiredHeight(3000.dp)) {
                    SituationsScreen(onNavigate = {}, onOpenDrawer = {})
                }
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(SETTLE_MS)
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
        composeRule.onAllNodesWithText("Returned payment: closed beneficiary account")
            .assertCountEquals(1)
        composeRule.onAllNodesWithText("Batch rejection: one bad payment stops payroll")
            .assertCountEquals(1)
    }

    @Test
    fun progressScreenRenders() {
        render { ProgressScreen(onNavigate = {}, onOpenDrawer = {}) }
    }

    // Detail screens.

    @Test
    fun lessonScreenRenders() {
        renderAndCheck("What a payment is") {
            LessonScreen(lessonId = "ch1s1")
        }
    }

    @Test
    fun situationDetailScreenRenders() {
        renderAndCheck("Returned payment: closed beneficiary account") {
            SituationDetailScreen(situationId = "ABH-01", onBack = {}, onNavigate = {})
        }
    }

    @Test
    fun situationDetailWeeklyScreenRenders() {
        renderAndCheck("Batch rejection: one bad payment stops payroll") {
            SituationDetailScreen(
                situationId = "JEB-C01",
                onBack = {},
                onNavigate = {},
                isWeeklyFreeCase = true
            )
        }
    }

    // Investigation experience.

    @Test
    fun investigationSituationRouteRenders() {
        render {
            InvestigationSituationRoute(
                situationId = "ABH-01",
                isWeeklyFreeCase = false,
                onBack = {},
                onNavigate = {}
            )
        }
    }

    @Test
    fun investigationSituationWeeklyRouteRenders() {
        render {
            InvestigationSituationRoute(
                situationId = "JEB-C01",
                isWeeklyFreeCase = true,
                onBack = {},
                onNavigate = {}
            )
        }
    }

    @Test
    fun investigationLabCaseRouteRenders() {
        render {
            InvestigationLabCaseRoute(labCaseId = "lab-abh-01", onBack = {}, onNavigate = {})
        }
    }

    @Test
    fun investigationWeeklyRouteRenders() {
        render { InvestigationWeeklyRoute(onBack = {}, onNavigate = {}) }
    }

    // Checkout and profile.

    @Test
    fun checkoutScreenRenders() {
        render { CheckoutScreen(productId = null, onBack = {}) }
    }

    @Test
    fun checkoutProductScreenRenders() {
        render { CheckoutScreen(productId = "com.paymentstraininghub.app.part-1", onBack = {}) }
    }

    @Test
    fun profileScreenRenders() {
        render { ProfileScreen(onBack = {}, onNavigate = {}) }
    }

    @Test
    fun notificationSettingsScreenRenders() {
        render { NotificationSettingsScreen(onBack = {}) }
    }

    // Auth.

    @Test
    fun authScreenRenders() {
        render { AuthScreen() }
    }

    @Test
    fun emailSignupScreenRenders() {
        render { EmailSignupScreen() }
    }

    @Test
    fun forgotPasswordScreenRenders() {
        render { ForgotPasswordScreen() }
    }

    @Test
    fun setNewPasswordScreenRenders() {
        render { SetNewPasswordScreen() }
    }

    // Feature screens.

    @Test
    fun simulatorScreenRenders() {
        render { SimulatorScreen() }
    }

    @Test
    fun isoExplorerScreenRenders() {
        render { IsoExplorerScreen() }
    }

    @Test
    fun isoExplorerMessageScreenRenders() {
        render { IsoExplorerScreen(initialMessageId = "pain.001") }
    }

    @Test
    fun exceptionLabScreenRenders() {
        render { ExceptionLabScreen(onNavigate = {}) }
    }

    @Test
    fun studioScreenRenders() {
        render { StudioScreen() }
    }

    @Test
    fun weeklyFreeCaseScreenRenders() {
        render { WeeklyFreeCaseScreen() }
    }

    @Test
    fun communityChallengeScreenRenders() {
        render { CommunityChallengeScreen() }
    }

    @Test
    fun dailyChallengeScreenRenders() {
        render { DailyChallengeScreen(onBack = {}, onNavigate = {}) }
    }

    @Test
    fun habitScreenRenders() {
        render { HabitScreen() }
    }

    @Test
    fun practiceScreenRenders() {
        render { PracticeScreen(onNavigate = {}, onBack = {}) }
    }

    @Test
    fun practiceSituationScreenRenders() {
        render {
            PracticeScreen(onNavigate = {}, onBack = {}, initialSituationId = "ABH-01")
        }
    }

    // Settings.

    @Test
    fun appearanceScreenRenders() {
        render { AppearanceScreen(onBack = {}) }
    }

    @Test
    fun helpSupportScreenRenders() {
        render { HelpSupportScreen(onBack = {}, onReplayTour = {}, onOpenDiagnostics = {}) }
    }

    @Test
    fun aboutScreenRenders() {
        render { AboutScreen(onBack = {}) }
    }

    @Test
    fun diagnosticsScreenRenders() {
        render { DiagnosticsScreen(onBack = {}) }
    }

    // Learn destinations.

    @Test
    fun pathDetailScreenRenders() {
        render { PathDetailScreen(pathId = "fundamentals", onBack = {}, onNavigate = {}) }
    }

    @Test
    fun sessionDetailScreenRenders() {
        render { SessionDetailScreen(sessionId = 1, onBack = {}, onNavigate = {}) }
    }

    @Test
    fun chapterDetailScreenRenders() {
        renderAndCheckExists("What a payment is") {
            ChapterDetailScreen(chapterNumber = 1, onBack = {}, onNavigate = {})
        }
    }

    @Test
    fun guideListScreenRenders() {
        render { GuideListScreen(onBack = {}, onNavigate = {}) }
    }

    @Test
    fun labListScreenRenders() {
        render { LabListScreen(onBack = {}, onNavigate = {}) }
    }

    @Test
    fun testListScreenRenders() {
        render { TestListScreen(onBack = {}, onNavigate = {}) }
    }

    @Test
    fun testQuizScreenRenders() {
        render { TestQuizScreen(entryId = "test-foundations", onBack = {}, onNavigate = {}) }
    }

    @Test
    fun entryDetailScreenRenders() {
        renderAndCheckExists("Guide: pacs.002 Explained") {
            EntryDetailScreen(entryId = "guide-pacs002", onBack = {})
        }
    }

    @Test
    fun interviewQuestionsScreenRenders() {
        render { InterviewQuestionsScreen(onBack = {}, onNavigate = {}) }
    }

    @Test
    fun interviewCategoryScreenRenders() {
        render {
            InterviewCategoryScreen(categoryId = "swift-iso20022", onBack = {}, onNavigate = {})
        }
    }

    // Assessment and certificates.

    @Test
    fun assessmentScreenRenders() {
        render {
            AssessmentScreen(
                dimension = CompetencyDimension.ARCHITECTURE,
                chapterNumbers = listOf(1, 2),
                sourceTitle = "Payment architecture",
                onBack = {},
                onNavigate = {}
            )
        }
    }

    @Test
    fun certificatesScreenRenders() {
        render { CertificatesScreen(onBack = {}, onNavigate = {}) }
    }

    @Test
    fun finalAssessmentScreenRenders() {
        render {
            FinalAssessmentScreen(
                productId = "com.paymentstraininghub.app.part-1",
                onBack = {},
                onNavigate = {}
            )
        }
    }

    @Test
    fun courseCertificateScreenRenders() {
        render {
            CourseCertificateScreen(
                productId = "com.paymentstraininghub.app.part-1",
                isSample = false,
                onBack = {}
            )
        }
    }

    @Test
    fun courseCertificateSampleScreenRenders() {
        render {
            CourseCertificateScreen(productId = "", isSample = true, onBack = {})
        }
    }

    @Test
    fun pathCertificateScreenRenders() {
        render {
            PathCertificateScreen(pathId = "fundamentals", onBack = {}, onNavigate = {})
        }
    }

    @Test
    fun revisionCentreScreenRenders() {
        render { RevisionCentreScreen(onBack = {}, onNavigate = {}) }
    }

    // Onboarding and tour.

    @Test
    fun onboardingFlowScreenRenders() {
        render { OnboardingFlowScreen(onFinish = {}, onFinishAndNavigate = {}) }
    }

    @Test
    fun guidedTourOverlayRenders() {
        render {
            GuidedTourOverlay(
                tabBarBounds = Rect(0f, 1000f, 1080f, 1200f),
                tabBarVisible = true,
                onStepChanged = {},
                onFinish = {}
            )
        }
    }

    private companion object {
        const val SETTLE_MS = 1200L
    }
}
