# Portfolio

Harry's Android portfolio tracker with a home-screen widget, in the same style as the Budget app.

## How it works

- **Holdings live in a Google Sheet** (tab `Holdings`): Ticker, Name, Units, Avg cost, Price, Prev close.
  Price and Prev close come from `GOOGLEFINANCE`, which Google keeps up to date itself (delayed about 20 minutes).
- The Sheet is **published to the web as CSV**. The app and widget read that link directly.
  **No Google Apps Script runs**, so this uses none of the 90 minutes a day of trigger time.
- The app does the maths (`PortfolioCore.java`): value, today's change, total gain, allocation.
  The widget uses the same code, so the two always agree.
- The widget refreshes about every 30 minutes (Android's minimum) and whenever the app is open.
  Its layout follows its size: 1 row shows the total and today's change; 2 rows add total gain,
  a value chart and the top holding; 3+ rows show up to 4 holdings. Tap it to open the app.
  The chart builds up on the phone from each refresh (kept for 90 days).

## Changing holdings

Edit the Sheet (add a row, change Units, delete a row). Rows with 0 units are ignored.
Crypto works with tickers like `CURRENCY:BTCAUD` (no Prev close, so no day change).

## One-time GitHub setup

Repo **Settings → Secrets and variables → Actions → New repository secret**, add both values from
`github-secrets.txt` exactly as written:

| Name | Value |
|---|---|
| `ANDROID_KEYSTORE_PASSWORD` | The line under `ANDROID_KEYSTORE_PASSWORD` |
| `ANDROID_KEYSTORE_BASE64` | The long line under `ANDROID_KEYSTORE_BASE64` |

Every update must be signed with this key, or the phone won't install it over the old version.
Keep the file in your password manager.

## Installing and updating

Each new number in `VERSION` makes GitHub build a signed APK under **Releases** (the repo is
public so the app can download updates without signing in; no personal data is in the code).

- **Updates:** the app checks Releases when opened (at most every 6 hours, or via Settings →
  Check for updates) and shows **Update available**. Tap **Install**, then confirm Android's prompt.
  The first time, Android asks you to allow Portfolio to install apps. Updates keep your settings.
- **First install:** download the `.apk` from the latest release on the phone and open it.

## Code

- `android/app/src/main/java/app/harry/portfolio/` – maths (`PortfolioCore`), Sheet download and settings
  (`PortfolioStore`), widget (`PortfolioWidget`), app screen host (`MainActivity`).
- `android/app/src/main/assets/www/` – the app screen (plain HTML, CSS and JavaScript). Opening
  `index.html` in a desktop browser shows a preview with sample holdings.
- `.github/workflows/android.yml` – tests and builds the APK.
