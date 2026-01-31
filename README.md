# Freecam

Freecam adds a toggleable freecam camera for Hytale servers, letting players detach the camera from their character while preserving their original state and position. It includes adjustable speed controls and blocks interaction while freecam is active.

## Features
- Toggleable freecam with `/freecam` or `/fc`.
- Tripod camera mode with `/tripod` (fixed camera while you can still move normally).
- Adjustable speed: `/freecam --speed 3` or `/fc --speed 3` (range 1-10).
- Optional freecam look lock for stream scenes (prevents camera drift while tabbed out).
- Restores player state and position when disabling freecam.
- Prevents block breaking while freecam is active.

## Commands
- `/freecam` - Toggle freecam on/off.
- `/fc` - Shortcut for `/freecam`.
- `/freecam <1-10>` - Set freecam speed (also enables freecam if it is off).
- `/freecam --speed <1-10>` or `/freecam --speed=<1-10>` - Set freecam speed.
- `/freecam lock` - Enable freecam look lock (camera stays still while tabbed out).
- `/freecam unlock` - Disable freecam look lock.
- `/tripod` - Toggle tripod camera mode.

## Notes
- This mod is server-side and does not modify client files.
- Freecam will auto-dismount you before enabling to avoid seat-related crashes.
- Use responsibly; this mod does not promote cheats, griefing tools, or disruptive behavior.

## Permissions
- The command is available to all players by default.

## Support
If you encounter issues, include your server build version and logs when reporting bugs.

### Credits
Created by Riloox. Website: https://riloox.site
