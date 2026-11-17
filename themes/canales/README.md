# canales

A light "channels" Home, drawn by the launcher's `channel_home` switch: four columns and three rows of tiles that
turn a page at a time, blank tiles where the board has no app yet, and a bar with a curved top edge along the
bottom of the screen.

- In the bar's dip: the clock and the full date (`Lunes 16 de noviembre de 2026` in Spanish).
- Left: the profile button and the Play Store. The profile button shows `profile.jpg` (or `profile.png`) from this
  folder when there is one, otherwise the initial of `profile_name`. The launcher has no internet access, so it
  cannot fetch the Google account's picture: copy one here and push the theme.
- Right: the network (Wi-Fi with its signal in bars, or Ethernet) and Settings.
- Apps that ship only a phone icon fill their tile in the icon's own colour.

Down from the last row lands on the button beneath that column; Up from the bar goes back to the tile you left.

```bash
TV_HOST=<tv ip> tools/theme.sh themes/canales
```
