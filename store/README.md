# Play listing

Listing text and graphics for Google Play, in the folder layout used by gradle-play-publisher, so they can become
listing-as-code later (PLAN D22). The owner pastes them into Play Console until then.

| File | Play field | Limit |
|---|---|---|
| `listings/en-US/title.txt` | App name | 30 characters |
| `listings/en-US/short-description.txt` | Short description | 80 characters |
| `listings/en-US/full-description.txt` | Full description | 4000 characters |
| `listings/en-US/graphics/icon/icon.png` | App icon | 512 x 512, 32-bit PNG, 1 MB |
| `listings/en-US/graphics/feature-graphic/feature-graphic.png` | Feature graphic | 1024 x 500, 24-bit PNG, no alpha |
| `listings/en-US/graphics/phone-screenshots/1-home.png` to `6-home-night.png` | Phone screenshots | 2 to 8, 1080 x 1920, 24-bit PNG, no alpha, 8 MB |
| `whatsnew/whatsnew-en-US` | Release notes (the release workflow uploads them) | 500 characters |
| `contact-website.txt`, `default-language.txt` | Store settings | |

`StoreListingTest` in `core/designsystem` checks these limits, the image formats and the words Play's metadata
policy rules out, on every unit-test run.

## Phone screenshots

Play needs at least two before a release on any track other than internal testing can go to review. We ship six,
in upload order: `1-home.png` (Home with documents), `2-pages.png` (Review or the page grid with a filter),
`3-text.png` (the Text view, Latin and Devanagari), `4-export.png` (the Export sheet with sizes),
`5-search.png` (a search hit inside the text) and `6-home-night.png` (Home in Night ink). No device frame.

Capture them from the debug build on the API 37 emulator, with documents that have plain names (not the
stress-test fixtures). A 1080 x 2400 capture is too tall for Play, so resize the screen first. From store/:

```
adb shell wm size 1080x1920 && adb shell wm density 420
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0900
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true -e mobile hide
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
# the status bar should now show only the clock, Wi-Fi and battery; for each screen:
adb exec-out screencap -p > shot.png
magick shot.png -alpha off -define png:color-type=2 listings/en-US/graphics/phone-screenshots/1-home.png
# when done:
adb shell am broadcast -a com.android.systemui.demo -e command exit
adb shell wm size reset && adb shell wm density reset
```

`screencap` writes RGBA; the `magick` step drops the alpha channel Play rejects. Retake them when a pictured
screen changes. The current six came from an AVD whose status bar kept a gear and a shield icon in demo mode; those
two icons were painted over with the bar's own colour (nothing else in the pictures was changed). A fresh AVD shows
none.

Copy rules: say what the app does today, in short plain sentences. No "best", "#1", "free", rankings,
testimonials or other apps' names. Keep the honest limits (Play services and RAM, the five scripts, ML Kit
diagnostics).
