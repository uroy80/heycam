# PDFBox-Android optionally references the JPEG 2000 decoder (com.gemalto.jp2), which heycam does not ship.
-dontwarn com.gemalto.jp2.**
# BouncyCastle is excluded: it is only used by PDFBox's public-key security handlers and signing.
-dontwarn org.bouncycastle.**
