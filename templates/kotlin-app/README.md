# xg-glass Kotlin app template

This is a template project used by xg-glass init.

## What you need to change

In most cases, developers only need to modify the business logic module’s ExampleAppEntry.kt (the sample entry; recommended to rename/replace it):

- `xgglass_app_logic/src/main/java/.../ExampleAppEntry.kt`

## How to build / install / run

From your project root (the directory that contains `xg-glass.yaml`):

- `xg-glass build`
- `xg-glass install`
- `xg-glass run`

## Notes

The `settings.gradle.kts` / `app/build.gradle.kts` / `AndroidManifest.xml` in this template contain placeholders:

- `__XG_SDK_PATH__`
- `__XG_ENTRY_CLASS__`

These will be replaced with actual values by `xg-glass init`.

### Meta AI Glasses note

If you want to enable **Meta AI glasses** support in a generated app, configure the Meta DAT GitHub Packages token before building:

```properties
# ~/.gradle/gradle.properties
github_token=ghp_xxxxxxxxxxxxx
```

or:

```bash
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxx
```

Notes:

- The token needs at least GitHub `read:packages` scope.
- When Meta support is enabled, the template host app automatically uses `minSdk 29`.

### Rokid note (CXR-M v1.0.4)

If you are connecting to **Rokid** glasses, CXR-M **v1.0.4** requires an SN authorization file (`.lc`) and your developer `clientSecret`.

#### Option A – In-app UI (recommended for end users)

1. Select **ROKID** from the device spinner.
2. Tap **Select SN License (.lc)** and pick your `.lc` file from the device.
3. Enter your **Client Secret** in the text field.
4. Tap **Connect**.

Credentials are persisted locally so you only need to do this once.

#### Option B – Build-time config (for developers)

- Put the SN authorization file into: `app/src/main/res/raw/`
  - Example: `app/src/main/res/raw/sn_0a9813....lc` (resource name is `sn_0a9813....`, **without** extension)
- Add the following to your project root `local.properties` (**do not commit it**):

```properties
rokid.clientSecret=xxxxxxxxxxxxxxxx
rokid.snRawName=sn_0a9813....
```

Alternatively, you can set environment variables: `ROKID_CLIENT_SECRET` and `ROKID_SN_RAW_NAME`.

> **Priority**: runtime credentials (Option A) take precedence over build-time config (Option B).
