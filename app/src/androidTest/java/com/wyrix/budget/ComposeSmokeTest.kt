package com.wyrix.budget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.domain.model.ThemeMode
import com.wyrix.budget.presentation.components.ChartSlice
import com.wyrix.budget.presentation.components.DonutChart
import com.wyrix.budget.presentation.components.EmptyState
import com.wyrix.budget.presentation.components.MoneyText
import com.wyrix.budget.presentation.components.ProgressBar
import com.wyrix.budget.presentation.theme.WyrixTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposeSmokeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun emptyStateShowsItsActionAndReactsToTaps() {
        var tapped = false
        rule.setContent { WyrixTheme(ThemeMode.LIGHT) { EmptyState("No budgets yet", "Set a limit", actionLabel = "Create budget", onAction = { tapped = true }) } }
        rule.onNodeWithText("No budgets yet").assertIsDisplayed()
        rule.onNodeWithText("Create budget").performClick()
        assertTrue(tapped)
    }

    @Test fun moneyIsMaskedWhenAmountsAreHidden() {
        rule.setContent { WyrixTheme(ThemeMode.DARK) { MoneyText(minor = 12_345, hidden = true) } }
        rule.onNodeWithText("••••••").assertIsDisplayed()
    }

    @Test fun moneyUsesTheCurrencyFormat() {
        rule.setContent { WyrixTheme(ThemeMode.DARK) { MoneyText(minor = 1_234_567, hidden = false, currency = CurrencyInfo.INR) } }
        rule.onNodeWithText("₹12,345.67").assertIsDisplayed()
    }

    @Test fun chartsExposeTextAlternativesForScreenReaders() {
        rule.setContent {
            WyrixTheme(ThemeMode.LIGHT) {
                ProgressBar(0.5f, Color.Green, description = "50 percent of budget used")
                DonutChart(listOf(ChartSlice("Food", 60, Color.Red), ChartSlice("Rent", 40, Color.Blue)), description = "Spending by category: Food 60 percent, Rent 40 percent")
            }
        }
        rule.onNodeWithContentDescription("50 percent of budget used").assertExists()
        rule.onNodeWithContentDescription("Spending by category: Food 60 percent, Rent 40 percent").assertExists()
    }
}
