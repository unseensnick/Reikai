#!/bin/bash
# Build the transparent animated WebP showcase from framed/ -> ../screens.webp
# 5 phones on a transparent canvas, each cross-fading A<->B via animated opacity (preserves the
# phone alpha / transparent surround so it blends on any README background).
# Panels: 1 library (manga<->novel) | 2 detail | 3 reader | 4 history (light<->dark) | 5 tabbed<->single-list
#
# LOSSLESS is required: lossy WebP uses yuva420p (subsampled/lossy alpha) which leaves a faint tinted
# background where it should be transparent. Lossless keeps alpha perfect, so the render size is kept
# modest (it shows ~180px/phone on GitHub) to keep the file small.
set -e
cd "$(dirname "$0")"
H=480; F=20; L=6.2; GAP=12
PW=$(( (H*1472/3111/2)*2 ))          # phone width (framed aspect 1472:3111), forced even
STEP=$((PW+GAP))                      # horizontal spacing between phones
CW=$((STEP*4+PW)); CHH=$((H+20))      # canvas size
# B-layer opacity over the loop: hold A (0..2.8), fade (2.8..3.1), hold B (3.1..5.9), fade back (5.9..6.2)
OP="st(1,mod(T,$L)); alpha(X,Y)*(if(lt(ld(1),2.8),0,if(lt(ld(1),3.1),(ld(1)-2.8)/0.3,if(lt(ld(1),5.9),1,1-(ld(1)-5.9)/0.3))))"
SC="scale=-2:$H,fps=$F,format=rgba,setsar=1"
GEQ="$SC,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='$OP'"

ffmpeg -y -v error \
 -loop 1 -t $L -i framed/p1a_manga_lib.png      -loop 1 -t $L -i framed/p1b_novel_lib.png \
 -loop 1 -t $L -i framed/p2a_manga_detail.png   -loop 1 -t $L -i framed/p2b_novel_detail.png \
 -loop 1 -t $L -i framed/p3a_manga_reader.png   -loop 1 -t $L -i framed/p3b_novel_reader.png \
 -loop 1 -t $L -i framed/p4a_history_all_light.png -loop 1 -t $L -i framed/p4b_history_all_dark.png \
 -loop 1 -t $L -i framed/p5a_manga_tabbed.png   -loop 1 -t $L -i framed/p1a_manga_lib.png \
 -f lavfi -t $L -i "color=c=black@0.0:s=${CW}x${CHH}:r=$F" \
 -filter_complex "
  [0]$SC[a1];[1]$GEQ[b1];[a1][b1]overlay=0:0:format=auto[p1];
  [2]$SC[a2];[3]$GEQ[b2];[a2][b2]overlay=0:0:format=auto[p2];
  [4]$SC[a3];[5]$GEQ[b3];[a3][b3]overlay=0:0:format=auto[p3];
  [6]$SC[a4];[7]$GEQ[b4];[a4][b4]overlay=0:0:format=auto[p4];
  [8]$SC[a5];[9]$GEQ[b5];[a5][b5]overlay=0:0:format=auto[p5];
  [10]format=rgba[bg];
  [bg][p1]overlay=0:10:format=auto[c1];
  [c1][p2]overlay=$STEP:10:format=auto[c2];
  [c2][p3]overlay=$((STEP*2)):10:format=auto[c3];
  [c3][p4]overlay=$((STEP*3)):10:format=auto[c4];
  [c4][p5]overlay=$((STEP*4)):10:format=auto,format=rgba[out]
 " -map "[out]" -c:v libwebp_anim -loop 0 -lossless 1 -compression_level 6 ../screens.webp
echo "built ../screens.webp ($CW x $CHH)"
