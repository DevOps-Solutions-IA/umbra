"""Whitelist-only crash metadata for the owned synthetic package; never raw logcat."""
import re


def summarize(text, package):
    if package not in ('app.umbra.privatechat.dev', 'app.umbra.privatechat.medialab',
                       'app.umbra.privatechat.offline.dev', 'app.umbra.privatechat.vaultlab',
                       'app.umbra.privatechat.offline.vaultlab'):
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
        modules = re.findall(r'\b(lib(?:jingle_peerconnection_so|c|art|android_runtime|media_jni|stagefright|stagefright_foundation|mediandk|codec2|codec2_soft_avcenc|codec2_vndk)\.so)\b', block)
        result.append({'signals': sorted(set(signals)), 'exceptionTypes': sorted(set(exceptions))[:12],
                       'nativeModules': sorted(set(modules)),
                       'jniCheckFailure': 'JNI DETECTED ERROR' in block,
                       'nativeCheckFailure': 'Check failed:' in block or 'CHECK(' in block,
                       'nativeFailureKinds': [kind for kind, tokens in {
                           'undefined-behavior': ('ubsan', 'UndefinedBehaviorSanitizer'),
                           'invalid-shift': ('shift exponent', 'shift-exponent', 'left shift'),
                           'file-size-bits': ('fileSizeBits', '_PC_FILESIZEBITS', 'fpathconf'),
                           'time-scale': ('mTimeScale',),
                           'fortify': ('FORTIFY',),
                           'mutex': ('pthread_mutex',),
                           'descriptor-ownership': ('fdsan',),
                           'checked-assertion': ('CHECK(', 'Check failed:', 'assertion failed'),
                       }.items() if any(token in block for token in tokens)],
                       'nativeSites': [site for site in ('MediaMuxer', 'MPEG4Writer', 'MediaCodec', 'CCodecBufferChannel', 'ABuffer', 'MediaImage', 'DirectByteBuffer',
                           'MPEG4Writer::init', 'MPEG4Writer::start', 'MPEG4Writer::stop',
                           'MPEG4Writer::addSource', 'MPEG4Writer::write', 'MPEG4Writer::endBox',
                           'MPEG4Writer::Track::Track', 'MPEG4Writer::Track::setTimeScale',
                           'MPEG4Writer::Track::getCodecSpecificDataFromInputFormatIfPossible',
                           'MPEG4Writer::Track::writeAvccBox', 'MPEG4Writer::Track::writeMp4aEsdsBox',
                           'MPEG4Writer::Track::makeAVCCodecSpecificData', 'MPEG4Writer::Track::threadEntry') if site in block]})
    return {'ownedCrashRecords': result[:4], 'rawLogPersisted': False,
            'scope': 'recent crash buffer for owned package; may include earlier scenarios'}
