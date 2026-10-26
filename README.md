# heycam :)

A document scanner for Android. Scan pages, clean them up, read their text and export searchable PDFs, all on
your phone. No account, no ads.

- Scanning and text recognition use Google ML Kit through Google Play services. Scanning needs Play services and
  about 2 GB of RAM; text recognition covers Latin, Devanagari, Chinese, Japanese and Korean.
- Your scans stay on your phone. heycam's own code makes no network requests; Play services downloads the scanner
  and text models and sends ML Kit diagnostics to Google ([details](https://developers.google.com/ml-kit/android-data-disclosure)).
- Privacy policy: https://heycam.edgr.in/privacy/

## Build

Needs JDK 21 and the Android SDK (platform 37).

```sh
./gradlew assembleDebug                 # debug app
./gradlew testDebugUnitTest             # JVM, Robolectric and Compose tests
./gradlew :build-logic:convention:test  # tests of the build's own rules (below)
./gradlew lint checkModuleGraph checkImports :app:checkPermissions
./gradlew :app:bundleRelease            # minified, unsigned release bundle
```

`checkModuleGraph` and `checkImports` enforce the module rules (features never depend on each other; ML Kit,
PDFBox and Room stay in their modules; no networking code, no `android.util.Log`). `checkPermissions` fails the
build if the release manifest asks for a permission that is not in `config/permissions-allowlist.txt`.

Screenshot goldens live in `src/test/screenshots` of `core/designsystem` and the six feature modules. They are
recorded on an Apple-silicon Mac, and CI compares them on one too, because Robolectric's native graphics draw
slightly differently elsewhere:

```sh
./gradlew verifyRoborazziDebug   # compare with the goldens
./gradlew recordRoborazziDebug   # re-record them, then look at every changed PNG
```

The searchable-PDF fixtures (gate G2) need `qpdf` and poppler's `pdftotext`:

```sh
./gradlew :core:pdf:testDebugUnitTest --tests '*PdfCompatFixturesTest*' --rerun
scripts/check-pdf-fixtures.sh
```

Device tests run on an emulator, against the debug build or the minified release build. A test APK that crashes at
startup still passes with 0 tests in Gradle, so each module's connected run ends with a count check
(`check<Debug|Release>DeviceTestCount`) that fails the build. The script prints the whole run:

```sh
./gradlew connectedDebugAndroidTest
python3 -I scripts/check-test-counts.py debug
./gradlew -Pheycam.testBuildType=release :app:connectedReleaseAndroidTest
python3 -I scripts/check-test-counts.py release
```

The release run keeps only what the tests call (`app/r8-test-entrypoints.pro`); it first runs
`scripts/check-test-apk.py`, which prints any rule that file is missing.

Modules: `app` (shell and navigation), `core/*` (data, imaging, OCR, scanner, PDF, export, domain, design system,
security, test fakes) and `feature/*` (library, scan, document, export, tools, settings).

## Release

Push a tag `vMAJOR.MINOR.PATCH` on a commit that is on `main`. `release.yml` checks that, runs the tests and rules,
and builds an unsigned bundle (versionCode `MAJOR×1,000,000 + MINOR×10,000 + PATCH×100`, see
`scripts/version-from-tag.sh`). After approval in the `play` environment it signs the bundle with the upload key
(`scripts/sign-bundle.sh`, an RSA or EC key) and sends it to the Play internal track. Without the signing secrets the
publish job stops after the build with a notice.

To run it by hand (Actions → Release → Run workflow), set "Use workflow from" to the tag: the `play` environment
accepts tags only, and the build uses that ref. `upload_to_play` off signs the bundle and keeps it as an artifact.
`release_status` `draft` uploads a draft release, for as long as Play still treats the app as a draft.

To sign a release build on your own machine, set these before running Gradle:

| Variable | Value |
|---|---|
| `HEYCAM_KEYSTORE_FILE` | path to the upload keystore (PKCS12) |
| `HEYCAM_KEYSTORE_PASSWORD` | keystore password |
| `HEYCAM_KEYSTORE_ALIAS` | key alias, e.g. `upload` |
| `HEYCAM_KEYSTORE_KEY_PASSWORD` | key password, if it differs from the keystore password |

## Licence

Apache License 2.0, see [LICENSE](LICENSE). Third-party notices are in [NOTICE](NOTICE).
