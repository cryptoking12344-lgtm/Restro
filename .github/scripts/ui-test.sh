#!/bin/bash
# Runs inside the emulator job: installs Restro, restores real test photos, takes screenshots.
set -x
mkdir -p shots restored
shot() { sleep 1; python3 .github/scripts/ui.py dismiss; adb exec-out screencap -p > "shots/$1.png"; }
ui() { python3 .github/scripts/ui.py "$@"; }
top() { for k in 1 2 3 4; do adb shell input swipe 540 700 540 2000 150; done; sleep 1; }
bottom() { for k in 1 2 3 4 5; do adb shell input swipe 540 2000 540 500 150; done; sleep 1; }
down() { adb shell input swipe 540 1800 540 900 400; sleep 1; }
# wait until a view id shows up (polls the screen); $1 id, $2 seconds
wait_id() {
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    if ! ui point id "$1" | grep -q "NOT FOUND"; then return 0; fi
    sleep 8
  done
  return 1
}
wait_text() {
  local end=$((SECONDS + $2))
  while [ $SECONDS -lt $end ]; do
    if ! ui point text "$1" | grep -q "NOT FOUND"; then return 0; fi
    sleep 8
  done
  return 1
}
send() {  # open a photo in Restro through the share sheet intent
  local id
  id=$(adb shell content query --uri content://media/external/images/media --projection _id:_display_name | grep "$1" | sed -E 's/.*_id=([0-9]+).*/\1/' | head -1)
  adb shell am start -a android.intent.action.SEND -t image/png --eu android.intent.extra.STREAM "content://media/external/images/media/$id" -n com.ck.restro/.MainActivity
  sleep 6
}
restore_and_wait() {  # $1 name, $2 timeout seconds
  bottom
  ui tap id btnRestore; sleep 4
  top; shot "$1_a_working"
  if wait_id txtResultTitle "$2"; then top; shot "$1_b_done"; else shot "$1_b_timeout"; fi
}

adb install -r Restro.apk
adb shell pm grant com.ck.restro android.permission.POST_NOTIFICATIONS || true
adb shell mkdir -p /sdcard/Pictures/RestroTest
adb push testphotos/. /sdcard/Pictures/RestroTest/
adb shell content call --uri content://media --method scan_volume --arg external_primary || true
sleep 8
adb logcat -c
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true

adb shell am start -n com.ck.restro/.MainActivity
sleep 8; shot 01_studio_empty
down; shot 02_options_a
down; shot 02_options_b
bottom; shot 02_options_c
top

# 1) Quick mode (built-in Fast model), no downloads needed
send oldphoto.jpg; shot 03_photo_loaded
down; ui tap text Quick; sleep 1; shot 04_quick_selected
restore_and_wait 05_quick 400
# wipe the compare handle to the left, then zoom in
read CX CY < <(python3 .github/scripts/ui.py point id compare 0.5 0.5 | tail -1)
adb shell input swipe $CX $CY $((CX - 300)) $CY 600; sleep 1; shot 06_compare_wiped
adb shell input tap $CX $CY; sleep 0.15; adb shell input tap $CX $CY; sleep 2; shot 07_compare_zoomed
adb shell input tap $CX $CY; sleep 0.15; adb shell input tap $CX $CY; sleep 1

ui tap id nav_models; sleep 3; shot 08_models

# 2) wait for the big models to be on the release (the models workflow may still be running)
for k in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -L -r 0-10 https://github.com/cryptoking12344-lgtm/Restro/releases/download/models-v1/face_gfpgan14.onnx)
  code2=$(curl -s -o /dev/null -w '%{http_code}' -L -r 0-10 https://github.com/cryptoking12344-lgtm/Restro/releases/download/models-v1/ultra.onnx)
  echo "release check $k: $code $code2"
  if [ "$code" = "206" -o "$code" = "200" ] && [ "$code2" = "206" -o "$code2" = "200" ]; then break; fi
  sleep 20
done

# 3) Old photo mode: Fast + face restore (downloads GFPGAN)
ui tap id nav_studio; sleep 2
send family.png
top; down; ui tap text "Old photo"; sleep 1
bottom; ui tap id btnRestore; sleep 5; shot 09_downloading
wait_text Restore 900; sleep 2; shot 10_downloaded
restore_and_wait 11_oldphoto 900

# 4) Portrait mode: Ultra detail + faces (downloads Ultra)
send oldphoto.jpg
top; down; ui tap text Portrait; sleep 1
bottom; ui tap id btnRestore; sleep 5
wait_text Restore 600; sleep 2
restore_and_wait 12_portrait 1800

ui tap id nav_models; sleep 3; shot 13_models_ready
ui tap id nav_library; sleep 4; shot 14_library

# themes
ui tap id nav_studio; sleep 2
ui tap id btnAppearance; sleep 3; shot 15_appearance
ui tap text Midnight; sleep 5; shot 16_midnight
ui tap id btnAppearance; sleep 3
ui tap text Orbit; sleep 5; shot 17_orbit
ui tap id btnAppearance; sleep 3
ui tap text "Liquid Glass"; sleep 5

adb pull /sdcard/Pictures/Restro/. restored/ || true
ls -la restored
adb shell dumpsys notification --noredact | grep -A3 "com.ck.restro" | head -40 > shots/notifications.txt || true
adb logcat -d > logcat_full.txt
grep -E "com.ck.restro|AndroidRuntime|onnxruntime|ORT|mlkit|Restro" logcat_full.txt | tail -3000 > logcat.txt
grep -B2 -A40 "FATAL EXCEPTION" logcat_full.txt > crashes.txt || echo "no crashes" > crashes.txt
