# SyncCam

Android multi-phone synchronized Wi-Fi camera prototype.

One device hosts a six-digit group. Other phones join over the same Wi-Fi. The clients estimate host/client monotonic clock offset using repeated NTP-style UDP exchanges. The host sends a future capture timestamp so each device fires locally at that scheduled instant, reducing packet-arrival jitter.

Build output: `app/build/outputs/apk/debug/app-debug.apk`.
