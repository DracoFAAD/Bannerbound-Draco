Tutorial clip drop folder (TUTORIAL_POPUP_PLAN.md)
===================================================

Each clip = two files sharing one name (datapack/addon authors: same layout in your namespace):
  codex_clips/<name>.json       metadata ({"video": ..., "loop": true, "autoplay": true})
  codex_clips/<name>.mp4        the video  <- drop here

Clips autoplay looping in-game, decoded in pure Java (JCodec) - no external tools needed.

OPTIONAL: textures/codex/clips/<name>_poster.png - an instant still shown only for the split
second before the first frame decodes (and as the fallback if a video fails to decode). Skip it;
the player takes over almost immediately.

Standalone images referenced by popup "image" fields go to:
  textures/codex/images/<name>.png  (e.g. path_settlement_img0.png)

Encode contract (Premiere export settings):
  Format H.264, 854x480 (16:9) or smaller, <= 20 fps, Profile: BASELINE (required - the pure-Java
  decoder does not handle Main/High), VBR 1-pass target ~1.2 Mbps (max 2), NO audio, ~5-12 s.
  Keep raw master recordings in case the encode contract ever changes.
