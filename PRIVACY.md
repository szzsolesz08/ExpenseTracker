# KiadásKövető – Adatvédelem / Privacy

## Magyarul

- **Minden adat a telefonon marad.** Blokkok, tételek, termékek, kategóriák, keretek, beállítások és az eredeti
  képek/PDF-ek az alkalmazás privát tárhelyén vannak.
- **Nincs internet-hozzáférés.** Az alkalmazás manifestje kifejezetten eltávolítja az `INTERNET` és az
  `ACCESS_NETWORK_STATE` engedélyt; egyetlen beépített könyvtár sem kaphat hálózati jogot.
- **Helyi feldolgozás.** Szövegfelismerés (Tesseract, beépített magyar modellek), értelmezés, kategorizálás,
  kimutatások és PDF-jelentés a készüléken készül.
- **Nincs fiók, felhő, reklám, analitika vagy hibajelentés-küldés.**
- **Nincs automatikus felhőmentés.** Az Android Auto Backup ki van kapcsolva (`allowBackup=false`,
  `data_extraction_rules.xml` minden tartományt kizár). Mentést a felhasználó készít egy általa választott helyre.
- **Engedélyek:** `CAMERA` (blokk és vonalkód fényképezése), `POST_NOTIFICATIONS` (keret-figyelmeztetések),
  `USE_BIOMETRIC` (opcionális alkalmazászár). A WorkManager könyvtár által hozzáadott `WAKE_LOCK`,
  `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` csak a helyi ütemezett feladatokhoz (napi keret-ellenőrzés,
  automatikus helyi mentés) kell.
- **Adat csak akkor hagyja el az alkalmazást**, ha a felhasználó exportál (CSV, JSON, PDF) vagy mentést készít.
- **Teljes törlés:** Beállítások → Összes adat törlése (kétlépcsős megerősítéssel), vagy az alkalmazás eltávolítása.

## English

All data stays on the device; the app has no network permission (explicitly removed in the manifest), no account,
no cloud sync, no ads, no analytics, no crash reporting, and Android Auto Backup is disabled. OCR (Tesseract with
bundled Hungarian models), parsing, categorization, analytics and reports run locally. Data leaves the app only through
user-initiated exports or backups to a location the user picks. "Összes adat törlése" erases everything.

Permissions: `CAMERA`, `POST_NOTIFICATIONS`, `USE_BIOMETRIC`; WorkManager adds `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`
and `FOREGROUND_SERVICE` for local scheduled jobs only.

Optional encrypted database storage (SQLCipher) is **not** implemented in this version; the database relies on
Android's mandatory file-based encryption of app-private storage (Android 10+) plus the optional biometric app lock.
