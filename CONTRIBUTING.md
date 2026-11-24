# Contributing

Thanks for helping. Bug reports, hardware findings and pull requests are all welcome.

## Before you start

- For anything larger than a small fix, open an issue first so we can agree on the approach.
- Read [docs/requirements.md](docs/requirements.md) for what each screen should do (requirement IDs are referenced from code and tests) and [docs/architecture.md](docs/architecture.md) for the phone-to-gateway contract.
- The simulator (`simulator/index.html`) is the visual reference. A change to a screen usually touches the simulator, the Kotlin renderer and a golden image together.

## Development

```sh
node --test server/gateway/test/                                  # gateway (no network, no credentials)
cd android && ./gradlew --no-daemon testDebugUnitTest assembleDebug  # app, golden screens, APK
node tools/docs/check-links.mjs                                   # documentation links
node tools/docs/configuration.mjs                                 # regenerate docs/configuration.md after a config change
```

[android/DEBUG.md](android/DEBUG.md) explains the adb loop for testing on a real phone and glasses. Golden images are regenerated with the `UPDATE_*_GOLDENS` variables described in [android/README.md](android/README.md); review every changed image before committing.

## Guidelines

- Keep the gateway dependency-free (Node 24 built-ins only).
- New gateway settings go in the schema in `server/gateway/lib/config.mjs`, then run `node server/gateway/tools/config-examples.mjs` and `node tools/docs/configuration.mjs`.
- Never commit credentials, real names, addresses, phone numbers, hostnames or IP addresses. Demo and test data must be clearly invented. `secrets.properties`, `local.properties`, `.env` and `gateway.config.json` are ignored for a reason.
- Never log secrets, authorization headers, message text or audio.
- Hardware claims need evidence: say which firmware version and phone you tested on.

By contributing you agree that your contribution is licensed under the [MIT License](LICENSE).
