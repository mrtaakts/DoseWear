package com.example.dosewear.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.example.dosewear.presentation.screens.HistoryScreen
import com.example.dosewear.presentation.screens.HomeScreen
import com.example.dosewear.presentation.screens.ReminderEditScreen
import com.example.dosewear.presentation.screens.RemindersScreen
import com.example.dosewear.presentation.screens.SettingsScreen
import com.example.dosewear.presentation.screens.SupplementDetailScreen
import com.example.dosewear.presentation.screens.SupplementEditScreen
import com.example.dosewear.presentation.screens.SupplementsScreen
import com.example.dosewear.presentation.theme.DoseWearTheme

object Routes {
    const val HOME = "home"
    const val SUPPLEMENTS = "supplements"
    const val SUPPLEMENT_DETAIL = "supplement"
    const val SUPPLEMENT_EDIT = "supplement_edit"
    const val REMINDERS = "reminders"
    const val REMINDER_EDIT = "reminder_edit"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    fun supplementDetail(id: Long) = "$SUPPLEMENT_DETAIL/$id"
    fun supplementEdit(id: Long) = "$SUPPLEMENT_EDIT/$id"
    fun reminderEdit(id: Long) = "$REMINDER_EDIT/$id"
}

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        askNotificationPermission()
        setContent { DoseWearNav() }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun DoseWearNav() {
    val nav = rememberSwipeDismissableNavController()

    DoseWearTheme {
        SwipeDismissableNavHost(
            navController = nav,
            startDestination = Routes.HOME
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onSupplements = { nav.navigate(Routes.SUPPLEMENTS) },
                    onReminders = { nav.navigate(Routes.REMINDERS) },
                    onHistory = { nav.navigate(Routes.HISTORY) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onSupplement = { id -> nav.navigate(Routes.supplementDetail(id)) }
                )
            }

            composable(Routes.SUPPLEMENTS) {
                SupplementsScreen(
                    onAdd = {
                        SupplementDraft.startNew()
                        nav.navigate(Routes.supplementEdit(0L))
                    },
                    onOpen = { id -> nav.navigate(Routes.supplementDetail(id)) }
                )
            }

            composable(
                route = "${Routes.SUPPLEMENT_DETAIL}/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                SupplementDetailScreen(
                    supplementId = id,
                    onEdit = {
                        SupplementDraft.invalidate()
                        nav.navigate(Routes.supplementEdit(id))
                    },
                    onDeleted = { nav.popBackStack() }
                )
            }

            composable(
                route = "${Routes.SUPPLEMENT_EDIT}/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                SupplementEditScreen(
                    supplementId = id,
                    onDone = { nav.popBackStack() }
                )
            }

            composable(Routes.REMINDERS) {
                RemindersScreen(
                    onAdd = {
                        ReminderDraft.pendingCopy = false
                        ReminderDraft.invalidate()
                        nav.navigate(Routes.reminderEdit(0L))
                    },
                    onOpen = { id ->
                        ReminderDraft.invalidate()
                        nav.navigate(Routes.reminderEdit(id))
                    }
                )
            }

            composable(
                route = "${Routes.REMINDER_EDIT}/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                ReminderEditScreen(
                    reminderId = id,
                    onDone = {
                        // Kopyalama zinciri birikmesin diye dogrudan listeye don.
                        if (!nav.popBackStack(Routes.REMINDERS, false)) nav.popBackStack()
                    },
                    onAddSupplement = {
                        SupplementDraft.startNew()
                        nav.navigate(Routes.supplementEdit(0L))
                    },
                    // Kopyala: taslak dolu halde yeni hatirlatici ekranina gecer.
                    onCopy = { nav.navigate(Routes.reminderEdit(0L)) }
                )
            }

            composable(Routes.HISTORY) { HistoryScreen() }

            composable(Routes.SETTINGS) { SettingsScreen() }
        }
    }
}
