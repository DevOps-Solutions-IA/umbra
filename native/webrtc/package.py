#!/usr/bin/env python3
"""Combine reviewed per-ABI source artifacts. Does not enable production voice."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

SOURCE='73cb8180f7258ee292878d6edd05177f41883962'
BUILDER='2040a2c92dd8e0408556fb13a3230b50292dc46b'
DEPOT='ca054941f756b50e1a3d83727270d879bec1f331'
PATCH='41df998e0088d3bf675c77547e87b10a5f825038b5297119946cd7d14cf02017'
ABIS={'arm64-v8a':183,'armeabi-v7a':40,'x86':3,'x86_64':62}

def digest(data): return hashlib.sha256(data).hexdigest()

def files(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names=archive.namelist()
        if len(names)!=len(set(names)): raise ValueError('Duplicate archive entries')
        return {name:archive.read(name) for name in names if not name.endswith('/')}

def normalized(entries):
    target=io.BytesIO()
    with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
        for name,data in sorted(entries.items()):
            entry=zipfile.ZipInfo(name,(1980,1,1,0,0,0));entry.create_system=3
            entry.external_attr=0o100644<<16;entry.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(entry,data,compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
    return target.getvalue()

def combine(root,output):
    combined={}; provenance={};common=None
    for abi,machine in ABIS.items():
        folder=root/f'native-voice-{abi}-{BUILDER}'
        expected={f'webrtc-{abi}.aar','LICENSE','LICENSE.md','PATENTS','args.gn','dependencies.txt','source.patch','source-revision.txt','depot-revision.txt'}
        checks={}
        for line in (folder/'SHA256SUMS').read_text().splitlines():
            sha,name=line.split(None,1);name=name.strip()
            if name not in expected or name in checks: raise ValueError('Unexpected source artifact inventory')
            checks[name]=sha
        if set(checks)!=expected: raise ValueError('Incomplete source artifact inventory')
        for name,sha in checks.items():
            if digest((folder/name).read_bytes())!=sha: raise ValueError('Source artifact hash mismatch')
        if (folder/'source-revision.txt').read_text().strip()!=SOURCE or (folder/'depot-revision.txt').read_text().strip()!=DEPOT or checks['source.patch']!=PATCH:
            raise ValueError('Unreviewed native source or patch')
        args=(folder/'args.gn').read_text()
        if 'use_remoteexec = false' not in args or 'use_reclient = false' not in args: raise ValueError('Build was not explicitly local')
        content=files((folder/f'webrtc-{abi}.aar').read_bytes())
        native=f'jni/{abi}/libjingle_peerconnection_so.so'
        if set(content)!={'AndroidManifest.xml','classes.jar',native}: raise ValueError('Unexpected AAR contents')
        java=files(content['classes.jar'])
        current=(content['AndroidManifest.xml'],java)
        if common is not None and common!=current: raise ValueError('ABI builds disagree on Java API or manifest')
        common=current
        binary=content[native]
        if binary[:6]!=b'\x7fELF'+bytes([2 if machine in (62,183) else 1,1]) or int.from_bytes(binary[18:20],'little')!=machine:
            raise ValueError('Native ABI mismatch')
        combined[native]=binary
        combined[f'assets/umbra-webrtc/LICENSES-{abi}.md']=(folder/'LICENSE.md').read_bytes()
        provenance[abi]={'inputHashes':checks,'args':args,'dependencies':(folder/'dependencies.txt').read_text()}
    combined['AndroidManifest.xml']=common[0];combined['classes.jar']=normalized(common[1])
    combined['assets/umbra-webrtc/LICENSE']=(folder/'LICENSE').read_bytes()
    combined['assets/umbra-webrtc/LICENSE-VOICE-MODULATOR']=(Path(__file__).parent/'voice-modulator/LICENSE').read_bytes()
    combined['assets/umbra-webrtc/PATENTS']=(folder/'PATENTS').read_bytes()
    receipt={'sourceRevision':SOURCE,'builderCommit':BUILDER,'depotRevision':DEPOT,'sourcePatchSha256':PATCH,'sourceBuildRun':36187887900,'abiBuilds':provenance}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_bytes(normalized(combined))
    pin={**receipt,'coordinate':'app.umbra:webrtc-source:150.7871.01-umbra.5','aarSha256':digest(output.read_bytes()),'entries':{name:digest(data) for name,data in sorted(combined.items())}}
    output.with_suffix('.json').write_text(json.dumps(pin,indent=2)+'\n')
    print('Combined four reviewed ABI builds; SHA256 '+pin['aarSha256'])

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('artifacts',type=Path);parser.add_argument('output',type=Path)
    args=parser.parse_args();combine(args.artifacts,args.output)
