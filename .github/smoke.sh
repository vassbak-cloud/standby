#!/bin/bash
# Εγκαθιστά το APK, ανοίγει τις οθόνες της εφαρμογής και τυπώνει κρασαρίσματα από το logcat.
set -x
PKG=gr.vassbak.standby
# root: για να ανοίξει και η (μη εξαγόμενη) οθόνη StandBy απευθείας
adb root; sleep 3; adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
fail=0
for act in .MainActivity .PreviewActivity; do
  adb shell am start -W -n $PKG/$act | tee /tmp/start.txt
  grep -q "Status: ok" /tmp/start.txt || fail=1
  sleep 6
  adb shell input tap 300 300; sleep 1
  adb shell input swipe 300 300 300 900 400; sleep 2
  adb shell am force-stop $PKG
done
# Κάθετη διάταξη και αυτόματη φορά
for rot in -1 0; do
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs; echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\"?><map><int name=\\\"rotation\\\" value=\\\"$rot\\\" /></map>\" > shared_prefs/standby.xml'"
  adb shell am start -W -n $PKG/.PreviewActivity | tee /tmp/start.txt
  grep -q "Status: ok" /tmp/start.txt || fail=1
  sleep 6
  adb shell input swipe 500 500 100 500 200; sleep 2     # σύρσιμο στο ρολόι
  adb shell am force-stop $PKG
done
echo "================ CRASHES ================"
adb logcat -d -b crash
adb logcat -d | grep -A40 "FATAL EXCEPTION" && fail=1
echo "========================================="

# ---- Screenshots (για έλεγχο εμφάνισης): μικρά JPEG σε base64 μέσα στο log ----
shot() {
  adb exec-out screencap -p > /tmp/$1.png
  convert /tmp/$1.png -resize 480x480 -quality 70 /tmp/$1.jpg
  echo "SHOT-BEGIN $1"; base64 -w0 /tmp/$1.jpg; echo; echo "SHOT-END $1"
}
for rot in 90 -1; do
  adb shell "run-as $PKG sh -c 'echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\"?><map><int name=\\\"rotation\\\" value=\\\"$rot\\\" /></map>\" > shared_prefs/standby.xml'"
  adb shell am start -W -n $PKG/.PreviewActivity >/dev/null
  sleep 5
  shot home_$rot
  # πάτημα σε πλακίδιο: δεξιά πάνω στην οριζόντια, κάτω στο κέντρο στην κάθετη
  size=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${size%x*}; H=${size#*x}
  if [ "$rot" = "90" ]; then adb shell input tap $((H*70/100)) $((W*25/100)); else adb shell input tap $((W*30/100)) $((H*55/100)); fi
  sleep 2
  shot open_$rot
  adb shell am force-stop $PKG
done
exit $fail
