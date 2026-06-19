#!/bin/bash
# Composite each app screenshot (stills/) into the Pixel 10 Pro XL device frame and round the
# screen corners so they sit inside the bezel. Output -> framed/ (RGBA, transparent surround).
# Screen is 1344x2992 at offset (60,55), corner radius 108 (from the pixel_10_pro_xl skin layout).
set -e
cd "$(dirname "$0")"
mkdir -p framed
BACK=frame/back.png
FORE=frame/mask.png
RND="format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='st(1,clip(X,108,1236));st(2,clip(Y,108,2884));255*clip(108-hypot(X-ld(1)\,Y-ld(2))+0.5,0,1)'"

frame_one() { # filename in stills/
  ffmpeg -y -v error -i "$BACK" -i "stills/$1" -i "$FORE" -filter_complex \
    "[1]scale=1344:2992,$RND[s];[0][s]overlay=60:55[b];[b][2]overlay=60:55,format=rgba[o]" \
    -map "[o]" -frames:v 1 "framed/$1"
}

for n in p1a_manga_lib p1b_novel_lib p2a_manga_detail p2b_novel_detail \
         p3a_manga_reader p3b_novel_reader p4a_history_all_light p4b_history_all_dark \
         p5a_manga_tabbed; do
  frame_one "$n.png"
done
echo "framed 9 stills"
