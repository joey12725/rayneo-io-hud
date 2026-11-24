# Setup

From nothing to the glasses showing your messages. You need:

- **RayNeo iO** smart glasses (stock firmware is fine).
- An **Android phone**, Android 10 (API 29) or newer.
- An **8BitDo Micro** controller (any Bluetooth keyboard works for testing).
- Headphones paired with the phone, for media and voice calls (the glasses have no speakers).
- For live data: a machine that can run the [gateway](gateway.md) (Node 24 or Docker) and that the phone can reach over HTTPS.

Without a gateway the app runs in demo mode with synthetic data, which is enough to try the controls on real glasses.

## 1. Build and install the app

Requirements: JDK 17 and the Android SDK (platform and build-tools 35). The Gradle wrapper is checked in.

```sh
git clone https://github.com/<you>/rayneo-io-hud && cd rayneo-io-hud
export JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
./android/gradlew -p android assembleDebug
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `android\gradlew.bat -p android assembleDebug` and set `$env:JAVA_HOME` instead. Android Studio can open the `android/` folder directly.

No secrets are needed to build. To install a build next to another one, give it its own id: `./android/gradlew -p android assembleDebug -PappId=com.example.hud`. Other build options are in [configuration.md](configuration.md#build-properties).

For a faster development loop over adb (build, install, grant permissions, read the app's state), see [android/DEBUG.md](../android/DEBUG.md):

```sh
python android/tools/glassesctl.py doctor   # checks JDK, SDK, adb, phone and app
python android/tools/glassesctl.py deploy   # build, install, restart
python android/tools/glassesctl.py setup    # grant permissions, accessibility, notification access, battery exemption
```

## 2. Grant permissions

Open **RayNeo iO HUD** on the phone. The **Tools → Setup guide** walks through each step; in short:

- **Accessibility**: enable *RayNeo iO HUD* in Android's accessibility settings. This captures the controller's keys while the app is in the background.
- **Notification access**: enable *RayNeo notifications and media*. This is how notifications and media sessions reach the glasses.
- **Microphone**, **notifications** and **Nearby devices** (Bluetooth): allow when asked.
- **Battery**: exempt the app from battery optimisation so the link survives in your pocket.
- Optional: phone-call permissions (answer, hang up and place calls from the glasses; see [android/README.md](../android/README.md#optional-phone-calls)), location (places for recordings), **Start when the phone starts**.

## 3. Pair the controller

Put the 8BitDo Micro in **keyboard mode** and pair it with the **phone**, not the glasses. In the app, open **Tools → Controller**, tap **Start** under **Teach the buttons** and press each requested button once (**Skip this button** skips one). The app saves the map when the last button is learned. For a landscape grip, turn off **Settings → Hold the controller upright** (on by default).

To keep the controller working while the phone is locked with the screen off, set up Shizuku (optional): [android/README.md](../android/README.md#shizuku-setup-recommended-for-a-locked-screen-off-phone).

The control scheme is in [requirements.md](requirements.md#5-input-and-control-scheme); the simulator shows what every button does on every screen.

## 4. Pair the glasses

1. Unbind the glasses in RayNeo's official app, forget them in Android's Bluetooth settings, and force-stop the official app (it competes for the link).
2. Hold the glasses button about five seconds, until the blue LED blinks.
3. In RayNeo iO HUD, open the glasses setup and choose your glasses.

The details, what the glasses can show on stock firmware, and the first hardware test are in [android/GLASSES.md](../android/GLASSES.md). Read [firmware-decision.md](firmware-decision.md) before accepting a firmware update or trying custom firmware: the app never flashes firmware.

## 5. Connect the gateway

Run the gateway ([gateway.md](gateway.md)), then on the phone open **Settings**:

1. **Gateway address**: your gateway's glasses API, for example `https://gateway.example.com/glasses/v1`.
2. **Access token**: the value of the gateway's `GLASSES_API_TOKEN` secret. It is stored encrypted with the Android Keystore.
3. **Test**, then **Save**, then turn on **Live data**.
4. **Load from gateway** (under Notifications) fills in the assistant name, your name, the integrations and the notification policy from the gateway. You can change any of them on the phone afterwards.

Switch off integrations you do not use under **Integrations**; their apps disappear from the glasses.

## 6. Connect the integrations

These three are the core and have first-class screens:

- **Hermes** (the assistant): [gateway.md § Hermes](gateway.md#hermes).
- **Beeper** (every chat network): [gateway.md § Beeper](gateway.md#beeper).
- **T3 Code** (coding agents): [gateway.md § T3 Code](gateway.md#t3-code).

Optional: Spotify, Google Calendar, Home Assistant, Audiobookshelf, Oura, Weight Gurus, a morning report, a YouTube list, People. Each is configuration only; see [gateway.md § Optional integrations](gateway.md#optional-integrations).

## 7. Choose an avatar (optional)

The assistant's on-lens character is an avatar pack. A small CC0 placeholder ships with the app. To use your own, build a pack from PNG frames or GIFs and import it under **Settings → Avatar**: [avatar-packs.md](avatar-packs.md).

## Troubleshooting

- **The glasses show nothing**: check the Home tab on the phone (link state and last error). Make sure RayNeo's official app is not connected.
- **Buttons do nothing in the background**: accessibility access was revoked (Android does this after some updates); enable it again.
- **"Check token"**: the access token does not match `GLASSES_API_TOKEN` on the gateway, or `GATEWAY_CLIENTS` does not give it the `glasses` scope.
- **Recordings never arrive**: exactly one gateway process must run with `GATEWAY_WORKER=true`; `GET /glasses/v1/ambient/status` shows the last delivery error.
- For everything else, [android/DEBUG.md](../android/DEBUG.md) explains how to read the app's live state from a computer.
