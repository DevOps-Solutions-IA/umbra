#!/usr/bin/env python3
"""Install reviewed per-call APM overlay into the pinned, already-patched tree."""
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(sys.argv[1]).resolve()
source = Path(__file__).resolve().parent
build = root / 'sdk/android/BUILD.gn'
text = build.read_text()
java = 'api/org/webrtc/UmbraVoiceProcessor.java'
anchor = '      "api/org/webrtc/ExternalAudioProcessingFactory.java",'
if text.count(anchor) != 2 or java in text:
    raise SystemExit('Unexpected native source; refusing ambiguous overlay')
text = text.replace(anchor, anchor + '\n      "' + java + '",')
anchor = '      "src/jni/pc/external_audio_processing_factory.cc",'
if text.count(anchor) != 1:
    raise SystemExit('Unexpected JNI target')
text = text.replace(anchor, anchor + '\n      "src/jni/pc/umbra_voice_processor.cc",\n      "src/jni/pc/umbra_voice_processor_core.h",')
build.write_text(text)
added = []
for file in sorted((source / 'overlay').rglob('*')):
    if file.is_file():
        target = root / file.relative_to(source / 'overlay')
        if target.exists():
            raise SystemExit('Overlay refuses existing file: ' + str(target))
        shutil.copyfile(file, target)
        added.append(str(target.relative_to(root)))
target = root / 'sdk/android/src/jni/pc/umbra_voice_processor_core.h'
shutil.copyfile(source / 'processor.h', target)
added.append(str(target.relative_to(root)))
# Include new files in git diff --binary provenance, not just modified BUILD.gn.
subprocess.run(['git', '-C', str(root), 'add', '-N', '--', *added], check=True)
