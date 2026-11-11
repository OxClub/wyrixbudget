package com.wyrix.budget

/** Static app information. Replace the placeholders before publishing. */
object AppInfo {
    const val VERSION_NAME = "1.0.0"

    const val SUPPORT_EMAIL = "oxclub@proton.me"

    val PRIVACY_POLICY = """
        Budget keeps your financial information on your device.

        What we collect: nothing. Budget has no account, no servers and no analytics, and it does not request internet access. The data you enter (accounts, transactions, budgets, goals, bills, debts and receipt photos) is stored only in the app's private storage on your phone.

        Sharing: data leaves your device only when you export a report or create a backup and choose where to send it. You control that destination.

        Receipts: photos are encrypted with a key held in the Android Keystore. Text recognition on receipts runs on the device.

        Security: you can protect the app with a PIN and biometric unlock. Backups can be encrypted with a password you choose; if you lose the password the backup cannot be opened.

        Your choices: you can export your data, delete individual records, or erase everything from Settings > Delete all my data. Uninstalling the app also removes its data.

        Children: Budget is not directed at children.

        Changes: if this policy changes, the new version will appear here and in the Play Store listing.
    """.trimIndent()

    val TERMS = """
        Budget is a personal record-keeping and planning tool. Figures, forecasts, insights and calculators (including the income-tax estimate) are estimates based on what you enter. They are not financial, tax, investment or legal advice.

        You are responsible for the accuracy of the data you enter and for keeping your own backups. The app is provided "as is" without warranties, to the extent permitted by law.

        Tax rules and rates change. Always confirm with official sources or a qualified professional before relying on an estimate.
    """.trimIndent()
}
