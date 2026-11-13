#!/bin/sh
# Downloads the sherpa-onnx Android AAR (on-device Piper TTS) into app/libs. Needed once before building.
set -e
cd "$(dirname "$0")/../app" && mkdir -p libs
gh release download v1.13.8 -R k2-fsa/sherpa-onnx -p "sherpa-onnx-1.13.8.aar" -D libs --clobber
