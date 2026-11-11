# Testing

None of these have been executed yet (no build environment was available). Run them first.

```
./gradlew testDebugUnitTest            # JVM unit tests
./gradlew connectedDebugAndroidTest    # needs an emulator/device (API 26+)
```

## Unit tests (JVM)
| File | Covers |
|---|---|
| `MoneyTest`, `ConversionAndMoneyExtrasTest` | Parsing, formatting (Indian/western grouping, zero-decimal currencies), conversion rounding, consolidation, financial-year and period maths |
| `LoanAndSavingsMathTest` | EMI, amortisation, compound interest, savings plans, emergency fund, debt payoff, affordability, exact allocation |
| `TaxEstimatorTest` | New/old regime, rebate, marginal relief, cess, FY differences |
| `RecurrenceAndBudgetTest` | Month-end anchoring, budget utilisation/levels/days remaining, forecast engine |
| `InsightAndReceiptTest` | Insight rules and dismiss keys, receipt text parsing |
| `TransactionValidatorTest`, `SaveTransactionUseCaseTest`, `CurrencyTransactionTest` | Validation, edit/duplicate rules, foreign-currency base amounts, GST |
| `PlanningUseCasesTest`, `PlanningModelsTest` | Bill/subscription/loan/recurring/goal/budget business rules, duplicate prevention |
| `BackupCodecTest` | Round trips, wrong password, tampering, version and consistency checks |
| `ExportersTest` | CSV escaping and formula-injection defence, XLSX structure/well-formedness/numeric cells |

## Instrumented tests (device)
| File | Covers |
|---|---|
| `LedgerIntegrationTest` | Real Room queries: balances after income/expense/transfer/edit/delete/reconcile |
| `MigrationTest` | Builds a genuine v1 database with data, migrates to v2, checks data, new tables, foreign keys |
| `DatabasePerformanceTest` | 10,000 rows: correctness and time limits, integrity and foreign-key checks |
| `ComposeSmokeTest` | Empty state, amount masking, currency format, chart text alternatives |

## Not automated: do these by hand before release
- Upgrade test: install the earlier build with data, then install this one over it.
- PDF output: open each report type's PDF; check page breaks and wide tables.
- Excel output: open the .xlsx in Excel and Google Sheets; numeric columns must sum.
- Camera and gallery receipt flow, OCR on real receipts (printed, crumpled, low light).
- Notifications on Android 13+ (permission denied, granted, revoked), after reboot, with battery saver.
- PIN lock-out, biometric enrol/un-enrol, auto-lock timeouts, screenshot blocking, rotation while locked.
- Backup on one device, restore on another; wrong password; restore of a truncated file.
- Time zone and daylight-saving changes; device date set to the 31st, Feb 29.
- TalkBack walkthrough of every main screen; 200% font size; dark and light themes; RTL layout.
- Low-end device smoke test with thousands of transactions (scrolling, search, analytics).
