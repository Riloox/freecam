# Freecam

Freecam adds a toggleable freecam camera for Hytale servers, letting players detach the camera from their character while preserving their original state and position. It includes adjustable speed controls and blocks interaction while freecam is active.

## Features
- Toggleable freecam with `/freecam` or `/fc`.
- Independent tripod (planted camera) mode: freeze the camera at your current view while moving your character in frame.
- Adjustable speed: `/freecam --speed 3` or `/fc --speed 3` (range 1-10).
- Restores player state and position when disabling freecam.
- Prevents block breaking while freecam is active.

## Commands
- `/freecam` - Toggle freecam on/off.
- `/fc` - Shortcut for `/freecam`.
- `/freecam <1-10>` - Set freecam speed (also enables freecam if it is off).
- `/freecam --speed <1-10>` or `/freecam --speed=<1-10>` - Set freecam speed.
- `/tripod` (aliases `/trip` and `/t`) - Plant the camera at your current view; run again to return to the normal camera.

## Notes
- This mod is server-side and does not modify client files.
- Hytale uses the active camera as its only aim and interaction ray. In tripod mode, mouse look cannot independently rotate the character's aim, and native punching or placement away from the fixed camera's aim is unavailable.
- Use responsibly; this mod does not promote cheats, griefing tools, or disruptive behavior.

## Permissions
- The command is available to all players by default.

## Support
If you encounter issues, include your server build version and logs when reporting bugs.

---

## About Riloox
Created by Riloox. Website: https://riloox.site
