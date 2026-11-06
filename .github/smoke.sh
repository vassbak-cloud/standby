#!/bin/bash
# Εγκαθιστά το APK, ανοίγει τις οθόνες της εφαρμογής και τυπώνει κρασαρίσματα από το logcat.
set -x
PKG=gr.vassbak.standby
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
fail=0
for act in .MainActivity .PreviewActivity; do
  adb shell am start -W -n $PKG/$act
  sleep 6
  adb shell input tap 300 300; sleep 1
  adb shell input swipe 300 300 300 900 400; sleep 2
  adb shell am force-stop $PKG
done
# Κάθετη διάταξη και αυτόματη φορά
for rot in -1 0; do
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs; echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\"?><map><int name=\\\"rotation\\\" value=\\\"$rot\\\" /></map>\" > shared_prefs/standby.xml'"
  adb shell am start -W -n $PKG/.PreviewActivity
  sleep 6
  adb shell am force-stop $PKG
done
echo "================ CRASHES ================"
adb logcat -d -b crash
adb logcat -d | grep -A40 "FATAL EXCEPTION" && fail=1
echo "========================================="
exit $fail
