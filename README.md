# PracticalBluetoothRecon
Digital situational awareness tool for logging WiFi, BLE, and Cellular data

Android app that logs nearby Wi‑Fi, Bluetooth (classic + BLE), and cellular signals with GPS + timestamp. Exports CSV/KML, supports dwell mode, co‑traveler views, whitelisting, and scheduled runs.

Practical Bluetooth Recon is a situational‑awareness tool that leverages the Wi‑Fi, Bluetooth, GPS, and cellular radios on an Android device. It scans and logs selected devices, pairing each record with a timestamp and GPS coordinates, and exports data to CSV and/or KML.

A "sighting" begins when a device is first logged; subsequent sightings are only recorded after the distance between the previous and next observation reaches at least 100 meters. Dwell Mode overrides this behavior and records all observations. The Co Travelers view groups sightings by distance buckets in 2‑mile increments (2, 4, 6, 8, 10+ miles) once the 100‑meter threshold is met. Whitelisting by user selection will not log selected devices; this should be used for your own devices or any devices you do not want recorded.

This is an experimental security tool intended for passive, local device collection. If you have ideas or enhancements, feel free to reach out. Logs and reproducible reports are welcome for debugging.
