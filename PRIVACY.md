# Privacy (development prototype)

- Windows collector records the executable filename and timing only. It does not record window titles, websites, screenshots, text entry, or keyboard content.
- Android collector uses Usage Access only after a user grants special access in Settings. It records app package identifiers and observed lifecycle timing intervals.
- Neither component includes an Internet connection nor analytics tracker. No automatic upload exists in this prototype.
- Windows records: `%LOCALAPPDATA%\TimeTrace\segments.jsonl`, `device_id.txt`.
- Android snapshot is in its private app files and can be exported to a user-selected location.
- Deleting logs and app data deletes stored data; exporting files creates additional copies controlled by the user.
- Device identifiers are randomly generated installation IDs, not hardware serial numbers. Logs may still reveal personal behavior and should be kept private.
