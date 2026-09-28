"""Whitelist-only crash metadata for the owned synthetic package; never raw logcat."""
import re


def summarize(text, package):
    if package not in ('app.umbra.privatechat.dev', 'app.umbra.privatechat.medialab'):
        raise ValueError('Not an isolated multimedia target')
    # Each AndroidRuntime/native DEBUG header begins a distinct crash record.
    blocks = re.split(r'(?=^.*(?:FATAL EXCEPTION:|\*\*\* \*\*\* \*\*\*).*$)', text, flags=re.M)
    result = []
    for block in blocks:
        if not (re.search(r'Process: '+re.escape(package)+r', PID: \d+\b', block)
                or re.search(r'>>> '+re.escape(package)+r' <<<', block)):
            continue
        signals = [int(v) for v in re.findall(r'\bsignal (\d+) \(', block)]
        exceptions = re.findall(r'\b((?:java|android|app\.umbra|org\.webrtc)\.[A-Za-z0-9_.$]*(?:Exception|Error))(?=:|\s|$)', block)
        modules = re.findall(r'\b(lib(?:jingle_peerconnection_so|c|art|android_runtime)\.so)\b', block)
        result.append({'signals': sorted(set(signals)), 'exceptionTypes': sorted(set(exceptions))[:12],
                       'nativeModules': sorted(set(modules)),
                       'jniCheckFailure': 'JNI DETECTED ERROR' in block,
                       'nativeCheckFailure': 'Check failed:' in block})
    return {'ownedCrashRecords': result[:4], 'rawLogPersisted': False}
