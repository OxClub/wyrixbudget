# Wyrix

Offline-first personal finance app for Android: Kotlin, Jetpack Compose (Material 3), Room, Hilt, WorkManager.
Package `com.wyrix.budget`. No internet permission, no account, no analytics. All data stays on the device.

> **Read this first.** All of this code was written without a compiler, emulator or device. It has been
> statically checked (every project import resolves, brackets balance) but **never built or run**. Expect a
> handful of compile or version fixes on first sync, then run the test suites (see TESTING.md) before trusting
> any figure. Dependency versions are the latest I knew of; bump them if Android Studio suggests.

## Open and run
1. Android Studio (Ladybug or newer), JDK 17. Open this folder and let Gradle sync (it creates the wrapper jar).
2. Run the `app` configuration on a device or emulator (API 26+).
3. First build exports the Room schema to `app/schemas/`. Commit those files: they are what future migrations are tested against.

## What is in it
| Area | What exists |
|---|---|
| Foundation | 5-tab navigation (Home, Transactions, Budget, Analytics, More), dark/light/system theme, Clean-Architecture layers, Hilt, Room v2 with a tested 1→2 migration |
| Accounts and transactions | 7 account types, income/expense/transfer entry, edit/delete/duplicate, search and filters, paging, reconciliation adjustments, archive |
| Budgets | Weekly/monthly/yearly/custom, per-category allocations, "category budget" mode, alert thresholds, history, suggestion from your last 90 days of spending |
| Savings goals | Targets, dates, contributions and withdrawals, required per day/week/month, progress |
| Bills and subscriptions | Due dates, frequencies, reminders, mark-paid (optionally records the expense), renewal history, monthly and annual cost |
| Debts | Loans, cards, borrowed money; interest/principal split per payment; payoff projection; snowball vs avalanche |
| Recurring items | Salary, rent etc. Never auto-recorded: you confirm or skip each occurrence |
| Calculators | EMI, compound interest, savings goal, emergency fund, debt payoff, affordability, budget planner, India income-tax estimate (FY 2024-25 to 2026-27, both regimes) |
| Analytics | Period selector, category donut, 6-month income vs expense bars, comparisons, merchants, accounts, rule-based insights (dismissible) |
| Forecast | Deterministic projection from recorded bills, subscriptions, loan payments and recurring items, with assumptions shown |
| Reports | 9 report types, PDF / Excel (.xlsx) / CSV, share and print |
| Receipts | Camera or gallery, encrypted with a Keystore key, on-device text recognition that only *suggests* values |
| Multi-currency | Per-account currency, base currency, manual exchange rates (never guessed), original + base amount stored |
| India | INR with lakh grouping, April financial year, UPI/NEFT/RTGS/IMPS payment methods, GST tagging |
| Security | PIN (PBKDF2 + Keystore), biometric unlock, auto-lock, screenshot blocking, hide-amounts mode |
| Backup | Versioned JSON, optional AES-256-GCM password encryption, integrity checks, all-or-nothing restore |
| Reminders | Daily WorkManager check: bills, loans, subscriptions, budget thresholds, goals, recurring, summaries, backup; de-duplicated; per-type toggles |
| Onboarding, settings, demo mode | Skippable setup; full settings; demo mode uses a *separate* database so sample data never mixes with yours |

## Design rules that matter
- **Money is a `Long` of minor units.** `BigDecimal` for parsing and conversion; no floats in any ledger arithmetic. Estimates (compound interest) are labelled.
- **Balances are never stored.** Opening balance + transactions, computed in SQL, so edits and deletes can't leave a stale figure.
- **Transfers, goal savings and reconciliation adjustments never count as income or expense.**
- **Base amounts.** Each transaction keeps its original amount/currency and a base-currency amount; budgets, analytics and reports use the base amount. Foreign accounts missing an exchange rate are *excluded and listed*, not guessed.
- **Nothing automatic with your money.** Recurring items, bills and renewals only record an expense when you confirm.
- **No destructive migration, ever.** Schema is exported; every change needs a `Migration`.
- **Insights, forecasts and "AI-like" features are plain rules over recorded data.** No machine learning, no cloud.

## Known gaps and honest limits
- **Not compiled or run.** See top.
- **No full-database encryption (SQLCipher).** The database relies on Android's app sandbox and file-based encryption. PINs, receipt photos and backups *are* encrypted. Adding SQLCipher means changing key management and migrating existing databases.
- **Single user.** Family/shared budgets need accounts and sync, which would contradict "offline, no account". Not implemented.
- **No online exchange-rate fetching.** By design rates are entered by you; no trusted-source integration exists.
- **Receipt photos are not part of backups** (restore removes their rows). OCR is Latin-script only.
- **Not implemented:** CSV import, tags, goal images, custom analytics date ranges, loan payment frequencies other than monthly, languages other than English (UI text is still hard-coded in Compose; extracting it to `strings.xml` is the first localisation step).
- **Reminders are approximate:** one WorkManager check a day around 8 am; aggressive battery optimisation can delay it.
- **Tax estimator** covers resident individuals under 60, no surcharge, no capital gains, no TDS. Rules change every Budget: review `IndianTaxRules` yearly.
- **Accessibility** is designed in (content descriptions, text alternatives for every chart, 48dp targets) but was never run through TalkBack or large-font testing.
- **`AppInfo.SUPPORT_EMAIL` is a placeholder** and the privacy policy/terms are plain-language drafts that need professional review.
- Play Store requirements (target API level, testing-track rules) change: verify them in Play Console before release. `targetSdk` is 35 here and may need to be higher.

## Project layout
`core/` money, time · `domain/` models, calc engines (pure, unit-tested), validation, use cases, repository interfaces ·
`data/` Room (entities, DAOs, migration), repositories, prefs, security, backup, export, notifications, files ·
`presentation/` Compose screens + ViewModels per feature · `di/` Hilt.

See TESTING.md, RELEASE.md and PLAY_STORE.md.
