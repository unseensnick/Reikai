#!/bin/bash
# Build the transparent animated WebP showcase from framed/ -> ../screens.webp
# 5 phones on a transparent canvas, each cross-fading between its states via animated opacity (preserves
# the phone alpha / transparent surround so it blends on any README background).
# Panels: 1 library (manga, novels, all) | 2 detail | 3 reader | 4 history (light<->dark) |
#         5 view modes (tabbed, single list, all tabbed)
#
# LOSSLESS is required: lossy WebP uses yuva420p (subsampled/lossy alpha) which leaves a faint tinted
# background where it should be transparent. Lossless keeps alpha perfect, so the render size is kept
# modest (it shows ~180px/phone on GitHub) to keep the file small.
set -e
cd "$(dirname "$0")"
H=480; F=20; L=9.3; GAP=12
PW=$(( (H*1472/3111/2)*2 ))          # phone width (framed aspect 1472:3111), forced even
STEP=$((PW+GAP))                      # horizontal spacing between phones
CW=$((STEP*4+PW)); CHH=$((H+20))      # canvas size
# A layer's opacity over the loop: fades in over 0.3s from $1, holds, fades out over 0.3s from $2.
# Three-state panels hold each state 2.8s (B in at 2.8, C in at 5.9, C out at 9.0, so A shows again);
# B stays opaque under C until C is fully in. Two-state panels hold each state about 4.35s.
op() { echo "st(1,mod(T,$L)); alpha(X,Y)*(if(lt(ld(1),$1),0,if(lt(ld(1),$1+0.3),(ld(1)-$1)/0.3,if(lt(ld(1),$2),1,if(lt(ld(1),$2+0.3),1-(ld(1)-$2)/0.3,0)))))"; }
SC="scale=-2:$H,fps=$F,format=rgba,setsar=1"
geq() { echo "$SC,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='$(op "$1" "$2")'"; }
B3=$(geq 2.8 6.2); C3=$(geq 5.9 9.0); B2=$(geq 4.35 9.0)

still() { echo "-loop 1 -t $L -i framed/$1.png"; }
ffmpeg -y -v error \
 $(still p1a_manga_lib) $(still p1b_novel_lib) $(still p1c_all_lib) \
 $(still p2a_manga_detail) $(still p2b_novel_detail) \
 $(still p3a_manga_reader) $(still p3b_novel_reader) \
 $(still p4a_history_all_light) $(still p4b_history_all_dark) \
 $(still p5a_manga_tabbed) $(still p1a_manga_lib) $(still p5c_all_tabbed) \
 -f lavfi -t $L -i "color=c=black@0.0:s=${CW}x${CHH}:r=$F" \
 -filter_complex "
  [0]$SC[a1];[1]$B3[b1];[2]$C3[c1];[a1][b1]overlay=0:0:format=auto[ab1];[ab1][c1]overlay=0:0:format=auto[p1];
  [3]$SC[a2];[4]$B2[b2];[a2][b2]overlay=0:0:format=auto[p2];
  [5]$SC[a3];[6]$B2[b3];[a3][b3]overlay=0:0:format=auto[p3];
  [7]$SC[a4];[8]$B2[b4];[a4][b4]overlay=0:0:format=auto[p4];
  [9]$SC[a5];[10]$B3[b5];[11]$C3[c5];[a5][b5]overlay=0:0:format=auto[ab5];[ab5][c5]overlay=0:0:format=auto[p5];
  [12]format=rgba[bg];
  [bg][p1]overlay=0:10:format=auto[c1];
  [c1][p2]overlay=$STEP:10:format=auto[c2];
  [c2][p3]overlay=$((STEP*2)):10:format=auto[c3];
  [c3][p4]overlay=$((STEP*3)):10:format=auto[c4];
  [c4][p5]overlay=$((STEP*4)):10:format=auto,format=rgba[out]
 " -map "[out]" -c:v libwebp_anim -loop 0 -lossless 1 -compression_level 6 ../screens.webp
echo "built ../screens.webp ($CW x $CHH)"
