# Security policy

Please do not report security problems in public issues.

Report them privately through GitHub's security advisories: open the repository's **Security** tab and choose **Report a vulnerability**. Include what you found, how to reproduce it, and what an attacker could do with it. We will acknowledge the report, work on a fix, and credit you in the advisory if you wish.

In scope: the gateway (`server/gateway`), the Android app, the Hermes plugin, the web HUD bridge and the tools in this repository. Problems in RayNeo's firmware, Beeper, Hermes or T3 Code should go to their maintainers.

When you deploy the gateway: keep it behind HTTPS, use long random tokens, give each client only the scope it needs (`GATEWAY_CLIENTS`), and never build an APK with an embedded token (`-PembedToken=true`) that you share with anyone.
