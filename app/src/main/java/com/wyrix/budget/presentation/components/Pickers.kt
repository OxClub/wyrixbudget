package com.wyrix.budget.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.model.Category

/** Account chips; tapping the selected chip again clears it when [optional]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountChips(label: String, accounts: List<AccountWithBalance>, selectedId: Long?, onSelect: (Long?) -> Unit, optional: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            accounts.forEach { a ->
                FilterChip(
                    selected = a.account.id == selectedId,
                    onClick = { onSelect(if (optional && a.account.id == selectedId) null else a.account.id) },
                    label = { Text(a.account.name) },
                    leadingIcon = { ColorDot(Color(a.account.colorArgb), size = 10) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryChips(label: String, categories: List<Category>, selectedId: Long?, onSelect: (Long?) -> Unit, optional: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { c ->
                FilterChip(
                    selected = c.id == selectedId,
                    onClick = { onSelect(if (optional && c.id == selectedId) null else c.id) },
                    label = { Text(c.name) },
                    leadingIcon = { ColorDot(Color(c.colorArgb), size = 10) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderChips(selected: List<Int>, onChange: (List<Int>) -> Unit, options: List<Int> = listOf(7, 3, 1, 0)) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Remind me")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { d ->
                FilterChip(
                    selected = d in selected,
                    onClick = { onChange(if (d in selected) selected - d else (selected + d).sortedDescending()) },
                    label = { Text(when (d) { 0 -> "On the day"; 1 -> "1 day before"; else -> "$d days before" }) },
                )
            }
        }
    }
}
