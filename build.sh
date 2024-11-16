#!/usr/bin/env bash

./gradlew app:clean app:assembleRelease
mkdir -p outputs/
cp -fv app/build/outputs/apk/release/app-release.apk outputs/ee.nekoko.revolver1s.apk

for filename in outputs/*.apk; do
  java -jar signer/apksigner.jar sign --v1-signing-enabled --v2-signing-enabled --v3-signing-enabled=false \
       --ks signer/sakura_key/sakurasim.keystore --ks-pass pass:sakurasim --next-signer \
       --ks signer/CommunityKey/CommunityKey.jks -ks-pass pass:CommunityKey --next-signer \
       --ks signer/9esim_key/9eSIMCommunityKey.jks -ks-pass pass:147258369 $filename
done
